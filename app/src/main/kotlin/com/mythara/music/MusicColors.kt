package com.mythara.music

import android.graphics.Color as AndroidColor

/**
 * Pitch → colour mapping for Music Mode's visual learning aid. Each
 * note in [MusicVocabulary.OM_HARMONICS] gets a fixed hue along the
 * spectrum, so the user learns to associate a colour with a tone:
 *
 *   272.2 Hz   red
 *   408.3 Hz   orange
 *   544.4 Hz   yellow
 *   680.5 Hz   green
 *   816.6 Hz   cyan
 *   952.7 Hz   blue
 *   1088.8 Hz  indigo
 *   1224.9 Hz  violet
 *
 * A motif's "word colour" is the average of its notes' colours,
 * computed in HSV (so blending two adjacent hues like red+orange
 * stays in the warm band rather than collapsing to brown via
 * RGB averaging).
 *
 * Returned as ARGB Ints so consumers can wrap them in either
 * `androidx.compose.ui.graphics.Color(it)` or pass directly to
 * Android Canvas APIs.
 */
object MusicColors {

    /** Hue (HSV) per OM-harmonic note, in degrees 0..360. Spaced
     *  evenly across the spectrum, lowest pitch → red. */
    private val NOTE_HUES: Map<Float, Float> = MusicVocabulary.OM_HARMONICS
        .mapIndexed { i, hz ->
            val hue = (i.toFloat() / MusicVocabulary.OM_HARMONICS.size) * 330f
            hz to hue
        }.toMap()

    /** Saturation kept high so colours pop against the dark UI;
     *  brightness slightly below max so words don't bloom out. */
    private const val SAT = 0.78f
    private const val VAL = 0.95f

    /** ARGB Int for a single note frequency. Falls back to a neutral
     *  grey for any frequency not in [MusicVocabulary.OM_HARMONICS]
     *  (shouldn't happen — minter only emits OM frequencies). */
    fun colorForNote(hz: Float): Int {
        val hue = NOTE_HUES[hz] ?: return AndroidColor.HSVToColor(floatArrayOf(0f, 0f, 0.6f))
        return AndroidColor.HSVToColor(floatArrayOf(hue, SAT, VAL))
    }

    /** Average colour of a motif — averages hue circularly so two
     *  hues 180° apart blend through neutral rather than wrapping
     *  to a third unrelated hue. */
    fun colorForMotif(motif: Motif): Int {
        if (motif.notes.isEmpty()) {
            return AndroidColor.HSVToColor(floatArrayOf(0f, 0f, 0.6f))
        }
        // Convert each note's hue to a unit vector, sum, take the
        // angle of the resultant — the standard "circular mean"
        // formula. Falls back to red if all components cancel
        // (only possible with exactly opposing hues at equal weight).
        var sx = 0.0
        var sy = 0.0
        for (n in motif.notes) {
            val hueDeg = NOTE_HUES[n] ?: continue
            val rad = Math.toRadians(hueDeg.toDouble())
            sx += Math.cos(rad)
            sy += Math.sin(rad)
        }
        val angle = Math.toDegrees(Math.atan2(sy, sx))
        val hue = ((angle + 360.0) % 360.0).toFloat()
        return AndroidColor.HSVToColor(floatArrayOf(hue, SAT, VAL))
    }
}
