package com.mythara.secret.observe.vosk

/**
 * Languages Mythara can transcribe via Vosk on-device.
 *
 * Each entry pins:
 *  - `code`         — locale-style tag we use as the on-disk directory
 *                     name (`filesDir/vosk-models/<code>/`) and as the
 *                     active-language preference value.
 *  - `displayName`  — Latin name shown in the picker.
 *  - `nativeName`   — same name in the language's own script so users
 *                     who can't read Latin can still spot their tongue.
 *  - `modelName`    — Alpha Cephei's model identifier; matches the
 *                     top-level folder inside the zip.
 *  - `modelUrls`    — ordered list of mirror URLs for the .zip. The
 *                     downloader tries each in order; first one that
 *                     succeeds wins. Primary is alphacephei.com (the
 *                     upstream), with HuggingFace + GitHub mirrors as
 *                     fallbacks for when the primary is unreachable
 *                     (expired TLS cert, transient outage, etc).
 *  - `sizeMB`       — approximate download size, for the UI's per-row
 *                     "(~XX MB)" label.
 *
 * All entries are the "small" Vosk variants — chosen deliberately for
 * mobile. Larger acoustic models exist for several of these languages
 * (~1GB+ apiece) but they don't fit a phone-friendly download budget.
 *
 * If you 404 on download, the most likely cause is that Alpha Cephei
 * bumped the model version. Update [modelName] + [modelUrls] to the
 * new release and bump nothing else.
 */
enum class Language(
    val code: String,
    val displayName: String,
    val nativeName: String,
    val modelName: String,
    val modelUrls: List<String>,
    val sizeMB: Int,
) {
    EnglishUS    ("en-US", "English (US)",       "English",              "vosk-model-small-en-us-0.15",
        listOf(
            // Primary: HuggingFace community mirror (ambind/vosk-model-small-en-us-0.15).
            // Same 41 MB binary as the upstream Alpha Cephei zip — the
            // `_c_` suffix is just the uploader's naming. Used as
            // primary because the upstream alphacephei.com TLS cert
            // expired May 16 2026 and chain validation is failing on
            // every device until they renew.
            "https://huggingface.co/ambind/vosk-model-small-en-us-0.15/resolve/main/vosk-model-small-en-us-0.15_c_.zip",
            // Fallback: alphacephei.com upstream — will resume working
            // once the cert is renewed.
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
        ), 40),
    EnglishIN    ("en-IN", "English (Indian)",    "English (Indian)",     "vosk-model-small-en-in-0.4",
        listOf("https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip"), 38),
    Spanish      ("es",    "Spanish",              "Español",              "vosk-model-small-es-0.42",
        listOf("https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"), 39),
    French       ("fr",    "French",               "Français",             "vosk-model-small-fr-0.22",
        listOf("https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip"), 41),
    German       ("de",    "German",               "Deutsch",              "vosk-model-small-de-0.15",
        listOf("https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip"), 45),
    Italian      ("it",    "Italian",              "Italiano",             "vosk-model-small-it-0.22",
        listOf("https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip"), 48),
    Portuguese   ("pt",    "Portuguese",           "Português",            "vosk-model-small-pt-0.3",
        listOf("https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip"), 31),
    Russian      ("ru",    "Russian",              "Русский",              "vosk-model-small-ru-0.22",
        listOf("https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"), 45),
    Dutch        ("nl",    "Dutch",                "Nederlands",           "vosk-model-small-nl-0.22",
        listOf("https://alphacephei.com/vosk/models/vosk-model-small-nl-0.22.zip"), 39),
    Polish       ("pl",    "Polish",               "Polski",               "vosk-model-small-pl-0.22",
        listOf("https://alphacephei.com/vosk/models/vosk-model-small-pl-0.22.zip"), 50),
    Turkish      ("tr",    "Turkish",              "Türkçe",               "vosk-model-small-tr-0.3",
        listOf("https://alphacephei.com/vosk/models/vosk-model-small-tr-0.3.zip"), 35),
    Vietnamese   ("vi",    "Vietnamese",           "Tiếng Việt",           "vosk-model-small-vn-0.4",
        listOf("https://alphacephei.com/vosk/models/vosk-model-small-vn-0.4.zip"), 32),
    Chinese      ("zh",    "Chinese (Mandarin)",   "中文",                 "vosk-model-small-cn-0.22",
        listOf("https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"), 42),
    Japanese     ("ja",    "Japanese",             "日本語",                "vosk-model-small-ja-0.22",
        listOf("https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip"), 48),
    Korean       ("ko",    "Korean",               "한국어",                "vosk-model-small-ko-0.22",
        listOf("https://alphacephei.com/vosk/models/vosk-model-small-ko-0.22.zip"), 82),
    Hindi        ("hi",    "Hindi",                "हिन्दी",                "vosk-model-small-hi-0.22",
        listOf("https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip"), 42),
    Catalan      ("ca",    "Catalan",              "Català",               "vosk-model-small-ca-0.4",
        listOf("https://alphacephei.com/vosk/models/vosk-model-small-ca-0.4.zip"), 42),
    Persian      ("fa",    "Persian (Farsi)",      "فارسی",                "vosk-model-small-fa-0.42",
        listOf("https://alphacephei.com/vosk/models/vosk-model-small-fa-0.42.zip"), 60),
    Czech        ("cs",    "Czech",                "Čeština",              "vosk-model-small-cs-0.4-rhasspy",
        listOf("https://alphacephei.com/vosk/models/vosk-model-small-cs-0.4-rhasspy.zip"), 44),
    Indonesian   ("id",    "Indonesian",           "Bahasa Indonesia",     "vosk-model-small-id-0.22",
        listOf("https://alphacephei.com/vosk/models/vosk-model-small-id-0.22.zip"), 50),
    Ukrainian    ("uk",    "Ukrainian",            "Українська",           "vosk-model-small-uk-v3-small",
        listOf("https://alphacephei.com/vosk/models/vosk-model-small-uk-v3-small.zip"), 80),
    Arabic       ("ar",    "Arabic (MSA)",         "العربية",              "vosk-model-ar-mgb2-0.4",
        listOf("https://alphacephei.com/vosk/models/vosk-model-ar-mgb2-0.4.zip"), 318),
    ;

    companion object {
        val Default: Language = EnglishUS

        fun fromCode(code: String?): Language =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: Default
    }
}
