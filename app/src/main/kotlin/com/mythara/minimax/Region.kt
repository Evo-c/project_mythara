package com.mythara.minimax

/**
 * Kept as Region for compatibility with existing SettingsStore/UI code.
 * Both entries now point to Gemini's OpenAI-compatible endpoint.
 */
enum class Region(val label: String, val baseUrl: String) {
    Global("Gemini API", "https://generativelanguage.googleapis.com/v1beta/openai/"),
    China("Gemini API", "https://generativelanguage.googleapis.com/v1beta/openai/");

    companion object {
        val Default: Region = Global
        fun fromId(id: String?): Region = entries.firstOrNull { it.name == id } ?: Default
    }
}
