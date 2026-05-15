package com.mythara.branding

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import com.mythara.R
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import kotlin.random.Random

/**
 * Per-frame painter for [MytharaLiveWallpaperService].
 *
 * Two layers:
 *
 *   1. **Static layers bitmap** — `R.drawable.wallpaper_static_layers`
 *      is a 1280×2856 PNG baked by `tools/branding/render_wallpaper.py
 *      --no-rose ...` and bundled into the APK. Contains the
 *      gradient, node-graph mesh, warrior silhouette, and the
 *      "MYTHARA 1.0" wordmark + backronym tagline. Loaded once,
 *      scaled to the surface, blitted every frame.
 *
 *   2. **Animated rose** — drawn fresh each frame as a centred
 *      10-petal star with `Canvas.rotate()` applied at ~4 °/s and
 *      its cyan hexagon nucleus pulsed in opacity at a 12 cycles/min
 *      breath rate. Rendered AFTER the static layers so it always
 *      sits on top.
 *
 * Per-frame cost: one bitmap blit (hardware-accelerated when the
 * canvas is hardware-backed, which it is on modern Android), ten
 * polygon fills for the petals, one for the hex. Easily inside the
 * 80 ms / frame budget the service throttles to.
 */
class WallpaperRenderer(private val ctx: Context) {

    // ─── Palette (matches the Python renderer + the watch face) ──────
    private val purple = Color.rgb(0x6B, 0x50, 0xFF)
    private val lavender = Color.rgb(0x9B, 0x86, 0xFF)
    private val cyan = Color.rgb(0x68, 0xFF, 0xD6)
    private val charmtoneFallback = Color.rgb(0x06, 0x04, 0x0C)

    // ─── Surface state ───────────────────────────────────────────────
    private var w: Int = 0
    private var h: Int = 0
    private var staticBitmap: Bitmap? = null
    private val staticSrcRect = Rect()
    private val staticDstRect = Rect()

    // Brighter "active neuron" overlay nodes — generated once per
    // surface size, animated via simple lissajous drift each frame.
    private var neurons: List<Neuron> = emptyList()

    // ─── Painters reused frame-to-frame to avoid GC churn ────────────
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val petalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hexPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val neuronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val petalPath = Path()
    private val hexPath = Path()

    /** A single drifting overlay node — base anchor + Lissajous drift
     *  parameters. Position at time t:
     *    x = baseX + driftAmp * cos(2π·t/periodX + phaseX)
     *    y = baseY + driftAmp * sin(2π·t/periodY + phaseY)
     *  Independent x/y periods + random phases keep the motion
     *  organic — none of the dozen nodes ever look like they're
     *  marching in step. */
    private data class Neuron(
        val baseX: Float,
        val baseY: Float,
        val driftAmp: Float,
        val periodX: Float,    // seconds for one full x-loop
        val periodY: Float,
        val phaseX: Float,     // radians
        val phaseY: Float,
    )

    /**
     * Called by the service whenever the surface size changes. Loads
     * the bundled static-layers PNG (or reloads it at the new
     * resolution). Subsequent calls with the same dimensions are
     * no-ops, so it's safe to invoke on every surface event.
     */
    fun setSize(width: Int, height: Int) {
        if (width == w && height == h && staticBitmap != null) return
        w = width
        h = height
        loadStaticLayers()
        neurons = generateNeurons(w, h)
    }

    /** Lay out N drifting "active neuron" nodes scattered across the
     *  canvas with random anchors, drift amplitudes, periods, and
     *  phase offsets. Seeded so layout is identical across renders /
     *  service restarts (matters when a user notices "the bright
     *  one near my clock" — they should keep finding it there).
     *  Drift periods are deliberately long (8-22 s) so the motion
     *  reads as ambient atmosphere, not a screensaver. */
    private fun generateNeurons(width: Int, height: Int): List<Neuron> {
        val rng = Random(0xC0FFEE)
        val ampScale = width / 1280f   // keep amplitudes proportional
        return List(NEURON_COUNT) {
            Neuron(
                baseX = rng.nextFloat() * width,
                baseY = rng.nextFloat() * height,
                driftAmp = (18f + rng.nextFloat() * 28f) * ampScale,
                periodX = 8f + rng.nextFloat() * 14f,
                periodY = 8f + rng.nextFloat() * 14f,
                phaseX = rng.nextFloat() * 2f * PI.toFloat(),
                phaseY = rng.nextFloat() * 2f * PI.toFloat(),
            )
        }
    }

    private fun loadStaticLayers() {
        // Decode at native pixel size. ARGB_8888 is overkill for an
        // opaque wallpaper but it lets the canvas blit hit the GPU's
        // fast path on most devices. Memory cost ≈ w*h*4 bytes
        // (~14 MB at 1280×2856) — acceptable for a service.
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val bmp = runCatching {
            BitmapFactory.decodeResource(ctx.resources, R.drawable.wallpaper_static_layers, opts)
        }.getOrNull()
        staticBitmap = bmp
        if (bmp != null) {
            staticSrcRect.set(0, 0, bmp.width, bmp.height)
        }
        staticDstRect.set(0, 0, w, h)
    }

    /**
     * Render a single frame. `tMs` is the milliseconds elapsed since
     * the engine started — drives the rose's slow rotation, the
     * pulse rate on the hexagon nucleus + active neurons (which
     * track live HR when available, fall back to a calm-breath
     * default when not), and the lissajous drift on each neuron.
     */
    fun render(canvas: Canvas, tMs: Long) {
        // Compute the pulse rate ONCE per frame, share between the
        // hex nucleus and the active neurons so they breathe in
        // unison — visual coherence is what makes the wallpaper
        // feel like a single living organism rather than a deck of
        // independently-animated layers.
        val pulseHz = effectivePulseHz()

        // 1. Static layers (or a flat fallback if decoding ever fails).
        val bmp = staticBitmap
        if (bmp != null) {
            canvas.drawBitmap(bmp, staticSrcRect, staticDstRect, paint)
        } else {
            canvas.drawColor(charmtoneFallback)
        }

        // 2. Active neurons — drifting bright dots overlaid on the
        // baked dim mesh. They pulse in sync with the heart rate so
        // a glance at the wallpaper conveys "you are calm" / "your
        // heart is racing" without numerals.
        renderNeurons(canvas, tMs, pulseHz)

        // 3. Animated rose at canvas-middle. The static bitmap has
        // *no* rose baked in (the Python renderer was invoked with
        // --no-rose for the bundled asset), so this is the only
        // rose pixels on screen.
        renderRose(canvas, tMs, pulseHz)
    }

    /** Rose hex + neuron pulse rate. Maps live BPM → Hz when fresh,
     *  falls back to a calm 0.2 Hz (12 cycles/min, resting breath)
     *  otherwise. The mapping `bpm / 300` is calibrated so a resting
     *  60 BPM matches the fallback exactly (smooth handoff when the
     *  HR source disconnects), 100 BPM speeds visibly to ~0.33 Hz,
     *  and 150 BPM races to ~0.5 Hz. Above ~200 BPM the pulse
     *  saturates at 0.8 Hz so the wallpaper doesn't actually
     *  strobe. */
    private fun effectivePulseHz(): Float {
        val bpm = LiveWallpaperPulseSink.bpm()
        val hz = if (bpm == null) {
            DEFAULT_PULSE_HZ
        } else {
            (bpm / 300f).coerceIn(DEFAULT_PULSE_HZ, MAX_PULSE_HZ)
        }
        // Log only on transitions (HR became live, dropped to stale,
        // or BPM bucket changed by ≥5) so the wallpaper doesn't spam
        // logcat 12× per second. Useful for confirming the sink is
        // wired without adding ongoing overhead.
        val rounded = bpm?.let { (it / 5) * 5 } ?: -1
        if (rounded != lastLoggedBpmBucket) {
            lastLoggedBpmBucket = rounded
            Log.d(TAG, "pulse rate: bpm=$bpm → ${"%.2f".format(hz)} Hz")
        }
        return hz
    }

    private var lastLoggedBpmBucket: Int = Int.MIN_VALUE

    /** Draw the dozen drifting neurons. Each pulses brightness +
     *  radius in unison with the heart rate, drifts on its own
     *  Lissajous curve over a multi-second period. A dimmer halo
     *  twice the radius gives a slight "glow" without a real
     *  blur pass. */
    private fun renderNeurons(canvas: Canvas, tMs: Long, pulseHz: Float) {
        if (neurons.isEmpty()) return
        val tSec = tMs / 1000f
        val phase = tSec * pulseHz * 2f * PI.toFloat()
        val pulse = (sin(phase) + 1f) * 0.5f                // 0..1
        val coreAlpha = (NEURON_ALPHA_MIN + pulse * (255 - NEURON_ALPHA_MIN)).toInt().coerceIn(0, 255)
        val haloAlpha = coreAlpha / 4
        val radiusBase = NEURON_RADIUS_BASE * (w / 1280f)
        val radius = radiusBase + pulse * (NEURON_RADIUS_PULSE * (w / 1280f))
        val haloR = radius * 2.5f

        for (n in neurons) {
            val x = n.baseX + n.driftAmp * cos(tSec * (2f * PI.toFloat() / n.periodX) + n.phaseX)
            val y = n.baseY + n.driftAmp * sin(tSec * (2f * PI.toFloat() / n.periodY) + n.phaseY)
            // Halo first so the core sits on top of it.
            neuronPaint.color = lavender
            neuronPaint.alpha = haloAlpha
            canvas.drawCircle(x, y, haloR, neuronPaint)
            neuronPaint.alpha = coreAlpha
            canvas.drawCircle(x, y, radius, neuronPaint)
        }
    }

    /**
     * The same 10-petal rose used by `splash_icon.xml`, scaled to ~67%
     * of canvas width and rotated as a function of `tMs`. Hexagon
     * nucleus opacity breathes between ~140 and 255 alpha at the
     * shared `pulseHz` (live HR when fresh, 0.2 Hz fallback) so the
     * brand mark literally beats with the user.
     */
    private fun renderRose(canvas: Canvas, tMs: Long, pulseHz: Float) {
        // Match the Python renderer's geometry exactly: source viewport
        // 108×108, scale = 6.5 * (w / 1280) when the silhouette is
        // present. Live wallpaper always renders the silhouette
        // composition so we stick with the 6.5 scale.
        val scale = 6.5f * (w / 1280f)
        val cx = w / 2f
        // _wordmark_block_height() in the Python renderer ≈ 230 px at
        // the default fonts — same metrics here since we use the
        // same font files. cy = h/2 - 115 lifts the rose so the
        // *combined* rose+wordmark block centres on canvas-middle.
        val cy = h / 2f - 115f

        // Slow rotation — one full revolution every ROT_PERIOD_MS.
        val rotDeg = ((tMs % ROT_PERIOD_MS).toFloat() / ROT_PERIOD_MS) * 360f

        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(rotDeg)

        // Big purple petals at 0/72/144/216/288°. Local diamond
        // coords (in source units): (0,0) (-3,-16) (0,-30) (3,-16).
        petalPaint.color = purple
        for (deg in intArrayOf(0, 72, 144, 216, 288)) {
            drawPetal(canvas, deg.toFloat(), scale, BIG_PETAL)
        }

        // Small lavender petals at 36/108/180/252/324°. Local diamond:
        // (0,0) (-2,-10) (0,-18) (2,-10).
        petalPaint.color = lavender
        for (deg in intArrayOf(36, 108, 180, 252, 324)) {
            drawPetal(canvas, deg.toFloat(), scale, SMALL_PETAL)
        }

        // Cyan hexagon nucleus, breathing in opacity. Scale stays
        // constant — pulsing scale would shift the visual centre,
        // pulsing alpha doesn't. Phase shared with the active
        // neurons (computed by the caller from the same pulseHz)
        // so the entire composition breathes in unison.
        val phase = (tMs.toFloat() / 1000f) * pulseHz * 2f * PI.toFloat()
        val pulse = (sin(phase) + 1f) * 0.5f
        val hexAlpha = (HEX_ALPHA_MIN + pulse * (255 - HEX_ALPHA_MIN)).toInt().coerceIn(0, 255)
        hexPaint.color = cyan
        hexPaint.alpha = hexAlpha
        hexPath.reset()
        for ((i, p) in HEX_POINTS.withIndex()) {
            val x = p.first * scale
            val y = p.second * scale
            if (i == 0) hexPath.moveTo(x, y) else hexPath.lineTo(x, y)
        }
        hexPath.close()
        canvas.drawPath(hexPath, hexPaint)

        canvas.restore()
    }

    /** Build + fill one petal path for the current canvas rotation. */
    private fun drawPetal(canvas: Canvas, deg: Float, scale: Float, src: FloatArray) {
        // src layout: x0 y0 x1 y1 x2 y2 x3 y3
        val r = deg * PI.toFloat() / 180f
        val c = cos(r)
        val s = sin(r)
        petalPath.reset()
        var i = 0
        while (i < 8) {
            val sx = src[i]
            val sy = src[i + 1]
            val x = (sx * c - sy * s) * scale
            val y = (sx * s + sy * c) * scale
            if (i == 0) petalPath.moveTo(x, y) else petalPath.lineTo(x, y)
            i += 2
        }
        petalPath.close()
        canvas.drawPath(petalPath, petalPaint)
    }

    /**
     * Drop the bundled bitmap. Called when the engine is destroyed so
     * we don't keep ~14 MB pinned in process memory after the
     * wallpaper is replaced.
     */
    fun release() {
        staticBitmap?.recycle()
        staticBitmap = null
    }

    companion object {
        private const val TAG = "Mythara/WPRenderer"

        // Rose rotation period — one revolution per 90 s. Slow enough
        // that you only notice the motion when you stare; fast enough
        // that "yes, this is a live wallpaper" reads in a glance.
        private const val ROT_PERIOD_MS = 90_000L

        // Pulse-rate fallback when no fresh HR is available — 0.2 Hz
        // = 12 cycles per minute = a calm resting breath. On-brand
        // for a wellness assistant. Same value also acts as the
        // floor inside the BPM mapping so the wallpaper never beats
        // *slower* than a calm breath when live HR is in play.
        private const val DEFAULT_PULSE_HZ = 0.2f

        // Pulse-rate ceiling — caps the rose + neuron pulse at 0.8 Hz
        // (~48 visible cycles/min) so a panicking heart at 220 BPM
        // doesn't actually strobe the wallpaper.
        private const val MAX_PULSE_HZ = 0.8f

        // Hex alpha floor — never goes fully transparent; the
        // nucleus should always be visible, just dimmer at the
        // exhale phase of the breath.
        private const val HEX_ALPHA_MIN = 140

        // Active neuron tunables — count, alpha + radius envelopes
        // for the brightness pulse. Radius/amp scale with canvas
        // width inside generateNeurons / renderNeurons so a non-
        // 1280-wide surface keeps the same proportions.
        private const val NEURON_COUNT = 12
        private const val NEURON_ALPHA_MIN = 140
        private const val NEURON_RADIUS_BASE = 4f
        private const val NEURON_RADIUS_PULSE = 2.5f

        // Source-unit polygon coordinates (xs/ys flat-packed) — match
        // splash_icon.xml's pathData verbatim.
        private val BIG_PETAL = floatArrayOf(0f, 0f, -3f, -16f, 0f, -30f, 3f, -16f)
        private val SMALL_PETAL = floatArrayOf(0f, 0f, -2f, -10f, 0f, -18f, 2f, -10f)
        private val HEX_POINTS = listOf(
            0f to -5f,
            4.33f to -2.5f,
            4.33f to 2.5f,
            0f to 5f,
            -4.33f to 2.5f,
            -4.33f to -2.5f,
        )
    }
}
