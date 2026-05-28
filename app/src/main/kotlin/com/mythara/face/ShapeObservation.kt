package com.mythara.face

import com.mythara.analytics.interactions.ContactInteractionRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Generates a **calm, one-line observation** about the user from the
 * current [LivingShapeEngine.LivingShape] + recent history.
 *
 * The voice is deliberately gentle, lowercase, sentence-fragment style
 * — Mythara reflecting quietly back to the user, never preachy. These
 * lines are surfaced as flash messages on Home (not spoken aloud),
 * fade in for ~5 seconds then disappear.
 *
 * Selection logic: build a candidate pool with several axes (mood,
 * mood-transition, social temperature, shape family reflection,
 * intensity), then pick one biased toward the more relevant signal
 * (e.g. a mood transition trumps a static mood read).
 */
@Singleton
class ShapeObservation @Inject constructor(
    private val historyStore: MoodHistoryStore,
    private val interactionRepo: ContactInteractionRepository,
) {

    /** Mint a single observation line. Suspends briefly to read the
     *  recent history; on Pixel 9+ this is sub-millisecond. */
    suspend fun generate(state: LivingShapeEngine.LivingShape): String {
        val recent = runCatching { historyStore.list().takeLast(5) }.getOrDefault(emptyList())
        val mood = state.mood
        val intensity = state.intensity
        val social = state.socialTemperature

        // Priority 1 — mood transitions (more interesting than static mood).
        val prevMood = recent.takeLast(2).firstOrNull()?.mood
        if (prevMood != null && prevMood != mood && mood != null) {
            transitionPhrase(prevMood, mood)?.let { return it }
        }

        // Priority 2 — strong social signal.
        when {
            social > 0.75f -> return "you've connected with people today."
            social < 0.20f -> return "a quieter day. that's okay."
        }

        // Priority 3 — strong intensity at peaks.
        if (intensity > 0.78f) {
            return when (mood) {
                "excited" -> "strong present moment. all in."
                "anxious" -> "breathe slow. you're held."
                "happy" -> "this feels like joy."
                "frustrated" -> "you're carrying something heavy."
                else -> "a strong reading today."
            }
        }

        // Priority 4 — mood-coloured shape reflection (the shape mirroring you).
        val shapePhrase = shapeReflection(state.family, mood)
        if (shapePhrase != null) return shapePhrase

        // Priority 5 — default static reflection on mood.
        return when (mood) {
            "calm" -> "you seem calm now."
            "happy" -> "there's a warmth in you today."
            "excited" -> "your energy is up."
            "sad" -> "today carries some weight."
            "anxious" -> "breathe slow."
            "frustrated" -> "this passes."
            else -> "this shape is yours. it remembers you."
        }
    }

    private fun transitionPhrase(from: String, to: String): String? {
        if (from == to) return null
        return when {
            from == "anxious" && to == "calm" -> "you've settled. nice."
            from == "sad" && to == "happy" -> "lighter than before."
            from == "frustrated" && to == "calm" -> "the tension lifted."
            from == "calm" && to == "excited" -> "you're moving into something."
            from == "happy" && to == "calm" -> "from joy to quiet. both yours."
            from == "excited" && to == "calm" -> "back to centre."
            from == "sad" && to == "calm" -> "softening."
            from == "calm" && to == "anxious" -> "something's pressing. notice it."
            else -> "you've shifted from $from to $to."
        }
    }

    private fun shapeReflection(family: CreativeShapes.Family, mood: String?): String? {
        return when (family) {
            CreativeShapes.Family.Supershape ->
                if (mood == "happy" || mood == "excited") "your shape grows. lively."
                else "the shape is reaching. forming."
            CreativeShapes.Family.SphericalHarmonic ->
                if (mood == "calm" || mood == "sad") "smooth waves. quiet thought."
                else null
            CreativeShapes.Family.LissajousKnot ->
                if (mood == "excited" || mood == "frustrated") "knotted. interconnected."
                else "moving in loops."
            CreativeShapes.Family.MetaballBlob ->
                if (mood == "calm" || mood == "sad") "soft. gathering itself."
                else null
            CreativeShapes.Family.RandomPolytope ->
                if (mood == "anxious" || mood == "frustrated") "structured. you're holding shape."
                else "crystalline. clear."
        }
    }
}
