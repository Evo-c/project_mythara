package com.mythara.music

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Plays sequences of pure-sine motifs for Music Mode. Architecturally
 * a much simpler cousin of [com.mythara.resonance.ResonanceAudioEngine]
 * — same AudioTrack-streaming foundation, but no binaural / isochronic
 * synthesis, no closed loop, no AudioFocusRequest dance: a Music Mode
 * tone is a short notification-style chirp, not an entrainment session.
 *
 * Safety still applies in microcosm:
 *  - per-note raised-cosine attack/release envelopes prevent clicks
 *  - frequencies are clamped to a vocal-comfortable 200–1500 Hz band
 *  - volume ceiling enforced in the render loop
 *  - one motif at a time — calling [play] while a motif is playing
 *    cancels the old one and starts the new (debounced barge-in)
 */
@Singleton
class MusicToneEngine @Inject constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var playJob: Job? = null
    @Volatile private var track: AudioTrack? = null

    /** "Which motif is playing right now?" — the chat bubble subscribes
     *  to this so it can light up the corresponding word in sync with
     *  the audio. [PlaybackState.sourceKey] disambiguates between
     *  bubbles (each Reply uses its own text as the key); the bubble
     *  only highlights when the key matches its own.
     *
     *  Null whenever nothing is playing (or the playback session
     *  didn't supply a source key — back-compat with the original
     *  fire-and-forget overload). */
    private val _nowPlaying = MutableStateFlow<PlaybackState?>(null)
    val nowPlaying: StateFlow<PlaybackState?> = _nowPlaying.asStateFlow()

    data class PlaybackState(val sourceKey: String, val motifIndex: Int)

    /** Back-compat overload — plays without emitting any nowPlaying
     *  state, so callers that don't care about word-sync don't have
     *  to invent a key. */
    fun play(motifs: List<Motif>) = play(motifs, sourceKey = "")

    /** Play a sequence of motifs back-to-back. The total duration is
     *  [motifs.size] × ([NOTE_DURATION_MS] × notes-per-motif + gap).
     *  Idempotent: cancels any in-flight playback before starting.
     *
     *  When [sourceKey] is non-empty, [nowPlaying] is updated before
     *  each motif so the chat bubble for that key can highlight the
     *  matching word in lockstep. */
    fun play(motifs: List<Motif>, sourceKey: String) {
        if (motifs.isEmpty()) return
        playJob?.cancel()
        playJob = scope.launch {
            renderSequence(motifs, sourceKey)
        }
    }

    /** Hard-stop any in-flight playback. */
    fun stop() {
        playJob?.cancel()
        playJob = null
        _nowPlaying.value = null
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.release() }
        track = null
    }

    private fun renderSequence(motifs: List<Motif>, sourceKey: String = "") {
        val sampleRate = SAMPLE_RATE
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        runCatching { t.play() }.onFailure {
            Log.w(TAG, "AudioTrack play() failed: ${it.message}")
            return
        }

        // Iterate motif → note → gap and write 16-bit PCM to the track.
        // Update nowPlaying before each motif so the bubble can light
        // up the matching word in real time. Skip the publish when
        // sourceKey is empty (back-compat overload).
        for ((mIdx, motif) in motifs.withIndex()) {
            if (sourceKey.isNotEmpty()) {
                _nowPlaying.value = PlaybackState(sourceKey, mIdx)
            }
            for ((nIdx, freq) in motif.notes.withIndex()) {
                val hz = freq.coerceIn(MIN_HZ, MAX_HZ)
                writeNote(t, hz, NOTE_DURATION_MS)
                if (nIdx < motif.notes.size - 1) writeSilence(t, INTRA_NOTE_GAP_MS)
            }
            if (mIdx < motifs.size - 1) writeSilence(t, INTER_MOTIF_GAP_MS)
        }
        // Flush the tail of the buffer so the last note actually plays
        // out (AudioTrack stops mid-buffer otherwise) before tearing
        // down the track.
        runCatching {
            t.stop()
            t.release()
        }
        if (track === t) track = null
        if (sourceKey.isNotEmpty()) _nowPlaying.value = null
    }

    private fun writeNote(t: AudioTrack, freqHz: Float, durationMs: Int) {
        val sampleRate = SAMPLE_RATE
        val totalSamples = (sampleRate * durationMs / 1000)
        val attackSamples = (sampleRate * ATTACK_MS / 1000).coerceAtMost(totalSamples / 2)
        val releaseSamples = (sampleRate * RELEASE_MS / 1000).coerceAtMost(totalSamples / 2)
        val sustainSamples = (totalSamples - attackSamples - releaseSamples).coerceAtLeast(0)

        // Sitar-flavoured timbre stack:
        //  - Constant 136.1 Hz "OM drone" beneath everything — the
        //    Sanskrit OM, always present so every motif carries deep
        //    tanpura body. NOT vibrato'd: the drone is the ground.
        //  - The note's fundamental + 5 harmonics, with a brighter
        //    upper spectrum than a vocal stack ([1, 0.55, 0.42, 0.34,
        //    0.24, 0.17]) — the extra weight on the 3rd–5th partials
        //    is what gives a sitar its characteristic "buzz" / jawari
        //    edge.
        //  - Pitch VIBRATO on the variable harmonics (±18 cents at
        //    6 Hz) — the meend wobble of a real sitar string. Drone
        //    stays unmodulated so the foundation never wavers.
        //  - Tremolo (AM) carries through from before for the OM-OM-OM
        //    pulse on top of all that.
        //
        // Harmonics above NYQUIST_GUARD are dropped to avoid aliasing.
        val sitarGains = floatArrayOf(1.0f, 0.55f, 0.42f, 0.34f, 0.24f, 0.17f)

        // Drone is index 0; sitar harmonics follow.
        val droneOmega = 2.0 * PI * OM_DRONE_HZ / sampleRate.toDouble()
        var droneGainSum = OM_DRONE_GAIN

        // Per-harmonic base frequencies (cached as doubles for the
        // per-sample omega recomputation under vibrato).
        data class Harm(val baseFreq: Double, val gain: Float)
        val sitarHarms = ArrayList<Harm>(sitarGains.size)
        for ((idx, g) in sitarGains.withIndex()) {
            val hz = freqHz * (idx + 1)
            if (hz > NYQUIST_GUARD_HZ) break
            sitarHarms.add(Harm(baseFreq = hz.toDouble(), gain = g))
            droneGainSum += g
        }
        // Normalise so peak amplitude stays bounded regardless of how
        // many partials stacked, with extra headroom for the tremolo +
        // vibrato modulations.
        val masterGain = (VOLUME / droneGainSum) *
            (1f / (1f + TREMOLO_DEPTH))

        // Phase accumulators — one per oscillator. Keep doubles for
        // 380 ms × 44100 Hz integration without precision drift.
        var dronePhase = 0.0
        val sitarPhases = DoubleArray(sitarHarms.size)

        // Tremolo (slow AM) and vibrato (slow FM) carriers.
        val tremoloOmega = 2.0 * PI * TREMOLO_RATE_HZ / sampleRate.toDouble()
        var tremoloPhase = 0.0
        val vibratoOmega = 2.0 * PI * VIBRATO_RATE_HZ / sampleRate.toDouble()
        var vibratoPhase = 0.0
        // ±18 cents → freq ratio swing of (2^(18/1200) - 1) ≈ 0.0104.
        val vibratoSwing = Math.pow(2.0, VIBRATO_DEPTH_CENTS / 1200.0) - 1.0
        // Twopi over sample rate, factored out of the per-sample loop.
        val twoPiOverSr = 2.0 * PI / sampleRate.toDouble()

        val chunk = ShortArray(CHUNK_SAMPLES)
        var i = 0
        while (i < totalSamples) {
            val n = minOf(CHUNK_SAMPLES, totalSamples - i)
            for (j in 0 until n) {
                val k = i + j
                val envelope = when {
                    k < attackSamples ->
                        // Raised-cosine attack — 0 → 1 over [0, attack].
                        0.5f * (1f - cos(PI.toFloat() * k / attackSamples))
                    k < attackSamples + sustainSamples -> 1f
                    else -> {
                        // Raised-cosine release — 1 → 0 over the tail.
                        val r = (k - attackSamples - sustainSamples).toFloat()
                        0.5f * (1f + cos(PI.toFloat() * r / releaseSamples))
                    }
                }

                // Drone — fixed pitch, contributes regardless of
                // vibrato so the OM ground never shifts.
                var harmonicSum = OM_DRONE_GAIN.toDouble() * sin(dronePhase)
                dronePhase += droneOmega
                if (dronePhase > 2 * PI) dronePhase -= 2 * PI

                // Vibrato factor for this sample — applied to all
                // sitar harmonics (drone stays steady).
                val vibratoFactor = 1.0 + vibratoSwing * sin(vibratoPhase)
                vibratoPhase += vibratoOmega
                if (vibratoPhase > 2 * PI) vibratoPhase -= 2 * PI

                for (h in sitarHarms.indices) {
                    val harm = sitarHarms[h]
                    val instOmega = twoPiOverSr * harm.baseFreq * vibratoFactor
                    sitarPhases[h] += instOmega
                    if (sitarPhases[h] > 2 * PI) sitarPhases[h] -= 2 * PI
                    harmonicSum += harm.gain * sin(sitarPhases[h])
                }

                val tremolo = 1.0 + TREMOLO_DEPTH * sin(tremoloPhase)
                tremoloPhase += tremoloOmega
                if (tremoloPhase > 2 * PI) tremoloPhase -= 2 * PI

                val sample = (harmonicSum * tremolo * masterGain * envelope * Short.MAX_VALUE).toInt()
                chunk[j] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            t.write(chunk, 0, n)
            i += n
        }
    }

    private fun writeSilence(t: AudioTrack, durationMs: Int) {
        val sampleRate = SAMPLE_RATE
        val totalSamples = sampleRate * durationMs / 1000
        val chunk = ShortArray(CHUNK_SAMPLES)
        var i = 0
        while (i < totalSamples) {
            val n = minOf(CHUNK_SAMPLES, totalSamples - i)
            t.write(chunk, 0, n)
            i += n
        }
    }

    fun release() {
        scope.cancel()
        stop()
    }

    companion object {
        private const val TAG = "Mythara/MusicTone"

        const val SAMPLE_RATE = 44_100
        private const val CHUNK_SAMPLES = 1024

        /** Per-note duration. Long enough for the layered-harmonic
         *  spectrum + tremolo to settle into a recognisable OM "ahhhh"
         *  rather than feel like a clipped beep. */
        const val NOTE_DURATION_MS = 380

        /** Silence between consecutive notes within a single motif —
         *  short, so each motif feels like one continuous phrase. */
        const val INTRA_NOTE_GAP_MS = 60

        /** Silence between motifs — long, so each word's tone phrase
         *  lands distinctly and the user can mentally map "this tone =
         *  this word" before the next one starts. The word-glow on
         *  the chat bubble updates inside this gap. */
        const val INTER_MOTIF_GAP_MS = 500

        /** Raised-cosine attack/release windows. Attack is now sharp
         *  enough for a plucked-string "twang" (a sitar's hallmark)
         *  rather than the slower swell of a chant. Release stays
         *  long so each note still fades with an OM "mmm" tail. */
        private const val ATTACK_MS = 6
        private const val RELEASE_MS = 110

        /** Pitch band wide enough to include the OM fundamental
         *  (136.1 Hz) at the bottom and the 9th harmonic (1224.9 Hz)
         *  at the top. Below ~120 Hz tones get muddy on phone
         *  speakers; above ~1500 Hz they start to feel piercing in a
         *  quiet room. */
        private const val MIN_HZ = 130f
        private const val MAX_HZ = 1500f

        /** Tremolo (slow amplitude modulation) parameters. ~5 Hz at
         *  ~14 % depth approximates the natural "om-om-om" pulse of
         *  a sustained chant. */
        private const val TREMOLO_RATE_HZ = 5.0
        private const val TREMOLO_DEPTH = 0.14

        /** Vibrato (slow frequency modulation) parameters — the
         *  pitch wobble that makes a sitar string sound alive vs.
         *  electronic. ~6 Hz wobble at ±18 cents (a small fraction
         *  of a semitone) is the "meend" of Indian classical
         *  fingerwork. Applied only to the variable note partials,
         *  never the OM drone (which stays as the steady ground). */
        private const val VIBRATO_RATE_HZ = 6.0
        private const val VIBRATO_DEPTH_CENTS = 18.0

        /** Constant OM-fundamental drone layered under every note —
         *  the Sanskrit OM frequency (136.1 Hz, Cousto). Always
         *  present in the spectrum so every motif carries the deep
         *  resonant body, regardless of what pitch the motif
         *  itself selected from OM_HARMONICS. */
        private const val OM_DRONE_HZ = 136.1f

        /** Drone gain — loud enough to feel underneath every note,
         *  quiet enough that the motif's variable top notes still
         *  carry the "which word is this" information. ~½ of the
         *  fundamental gain hits the right balance in casual phone
         *  listening. */
        private const val OM_DRONE_GAIN = 0.55f

        /** Cap on the highest harmonic frequency we synthesise — drop
         *  any overtone above this so high motif notes don't alias
         *  into ugly artefacts above the human-pleasant band. */
        private const val NYQUIST_GUARD_HZ = 5000f

        /** Output volume cap. Music Mode runs as USAGE_ASSISTANCE_
         *  SONIFICATION which routes to the notification stream; we
         *  still scale the synth to keep it polite. The render path
         *  divides this by the harmonic-stack gain sum so the peak
         *  amplitude stays bounded regardless of how many overtones
         *  layer in. */
        private const val VOLUME = 0.55f
    }
}
