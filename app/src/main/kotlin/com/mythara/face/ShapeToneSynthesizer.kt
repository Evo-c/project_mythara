package com.mythara.face

import com.mythara.music.Motif
import com.mythara.music.MusicToneEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps the current [LivingShapeEngine.LivingShape] to a short
 * frequency sequence and plays it through [MusicToneEngine].
 *
 * Conceptually: the shape "speaks" back to the user using the same
 * pitch alphabet Music Mode uses (OM-harmonic series, 136.1 Hz root),
 * but the **family chooses the rhythm + interval pattern**, the
 * **mood chooses the root + scale colour**, and the **intensity
 * scales the duration / repetition**.
 *
 * Always a quick, calm sequence (~2-3 s total). Never overlaps —
 * MusicToneEngine cancels any in-flight tone on a new play() call.
 */
@Singleton
class ShapeToneSynthesizer @Inject constructor(
    private val toneEngine: MusicToneEngine,
) {

    /** Play the current shape's tone sequence. Idempotent; tapping
     *  twice in quick succession just replays. */
    fun play(state: LivingShapeEngine.LivingShape) {
        val rootHz = rootForMood(state.mood)
        val pattern = patternForFamily(state.family, rootHz)
        // Wrap each note in its own Motif so MusicToneEngine spaces
        // them with INTER_MOTIF_GAP_MS (500 ms) silences — calm,
        // unhurried. A single multi-note motif plays them back-to-back
        // which feels rushed for this voice.
        val motifs = pattern.map { hz -> Motif(notes = listOf(hz)) }
        toneEngine.play(motifs, sourceKey = "shape-tone")
    }

    /** Mood → root pitch in Hz, drawn from the OM harmonic series so
     *  the tone fits the brand sound palette every other Mythara
     *  surface (live wallpaper hex pulse, Music Mode replies) uses. */
    private fun rootForMood(mood: String?): Float = when (mood) {
        // Low + warm — deep OM fundamental
        "calm", "sad" -> 136.1f
        // Mid — octave + 3rd
        "happy", "frustrated" -> 272.2f
        // Mid-bright — perfect fifth
        "anxious" -> 408.3f
        // Bright — two-octave 4th harmonic
        "excited" -> 544.4f
        else -> 272.2f
    }

    /** Family → tone pattern, expressed as multiples of the root.
     *  Each family has its own characteristic interval colour so the
     *  user learns "this kind of shape sounds like this." */
    private fun patternForFamily(family: CreativeShapes.Family, root: Float): List<Float> {
        return when (family) {
            // Supershape — organic rising / falling. Like growth.
            CreativeShapes.Family.Supershape -> listOf(
                root, root * 1.50f, root * 2.00f, root * 1.50f,
            )
            // SphericalHarmonic — smooth, contemplative. Hangs on the
            // root, drifts up a perfect 5th and back.
            CreativeShapes.Family.SphericalHarmonic -> listOf(
                root, root * 1.50f, root, root * 1.25f,
            )
            // LissajousKnot — interleaved intervals, evokes the knot's
            // criss-cross. Jumps wider.
            CreativeShapes.Family.LissajousKnot -> listOf(
                root, root * 2.00f, root * 1.50f, root * 2.50f, root,
            )
            // MetaballBlob — soft, close intervals. Like swelling.
            CreativeShapes.Family.MetaballBlob -> listOf(
                root, root * 1.125f, root * 1.25f, root,
            )
            // RandomPolytope — sharp, structured. Bell-like ascending
            // harmonics.
            CreativeShapes.Family.RandomPolytope -> listOf(
                root, root * 2.00f, root * 3.00f, root * 2.00f, root,
            )
        }
    }
}
