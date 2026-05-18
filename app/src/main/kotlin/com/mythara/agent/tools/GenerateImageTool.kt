package com.mythara.agent.tools

import android.content.Context
import android.util.Base64
import android.util.Log
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.data.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `generate_image` — generate an image from a text prompt.
 *
 * Routing (first available wins):
 *   1. **Gemini** (`gemini-2.5-flash-image-preview`, aka "Nano Banana")
 *      — preferred when the user has a Gemini API key configured.
 *      Returns image bytes inline in the response (base64), so there
 *      is NO second download step that could fail (the previous
 *      MiniMax path 'download_failed' on expired signed CDN URLs).
 *   2. **MiniMax `image_generation`** — fallback when no Gemini key.
 *      Returns a CDN URL we then GET in a second hop.
 *
 * Either way, the resulting image is saved to
 * `filesDir/canvas/images/<uuid>.<ext>` and the absolute path
 * returned. The agent can then pass that path into
 * [RenderCanvasTool] (via an `<img src="file://…">`) to display it.
 *
 * If neither key is configured the tool returns an actionable error
 * telling the user where to paste a key.
 */
@Singleton
class GenerateImageTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
) : Tool {
    override val name = "generate_image"
    override val description =
        "Generate an image from a text prompt. Routes to Gemini's image-gen model " +
            "when a Gemini API key is configured (no second download — bytes inline), " +
            "else MiniMax. Returns a local file path the canvas can display via " +
            "`<img src=\"file://…\">`."

    override val parameters = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("prompt", buildJsonObject {
                put("type", "string")
                put("description", "What to generate. Concrete + visual; e.g. 'sunset over a forest, painterly'.")
            })
            put("style", buildJsonObject {
                put("type", "string")
                put("description", "Optional style hint (e.g. 'photoreal', 'watercolor', 'low-poly'). Folded into prompt.")
            })
            put("aspect", buildJsonObject {
                put("type", "string")
                put("description", "Optional aspect: '1:1' (default), '16:9', '9:16', '4:3', '3:4'. MiniMax-only; Gemini ignores.")
            })
        })
        put("required", JsonArray(listOf(JsonPrimitive("prompt"))))
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun execute(args: JsonObject): ToolResult {
        val rawPrompt = args["prompt"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
        if (rawPrompt.isBlank()) return ToolResult.fail("prompt must be non-empty")
        val style = args["style"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
        val aspect = args["aspect"]?.jsonPrimitive?.contentOrNull()?.trim()?.ifBlank { null } ?: "1:1"
        val prompt = if (style.isBlank()) rawPrompt else "$rawPrompt, $style"

        val geminiKey = settings.geminiKeyFlow().first().orEmpty()
        val minimaxKey = settings.apiKeyFlow().first().orEmpty()

        if (geminiKey.isBlank() && minimaxKey.isBlank()) {
            return ToolResult.fail(
                "image_gen_no_key: open Settings → paste a Gemini API key (preferred) " +
                    "or a MiniMax API key, then retry.",
            )
        }

        return withContext(Dispatchers.IO) {
            // Try Gemini first when configured — inline bytes, no
            // second hop that can fail with an expired signed URL.
            if (geminiKey.isNotBlank()) {
                val r = runCatching { generateViaGemini(prompt, geminiKey) }
                    .getOrElse { e ->
                        Log.w(TAG, "Gemini image-gen threw: ${e.message}")
                        null
                    }
                if (r != null) return@withContext r
                Log.i(TAG, "Gemini image-gen fell through; trying MiniMax")
            }

            if (minimaxKey.isNotBlank()) {
                return@withContext runCatching { generateViaMiniMax(prompt, aspect, minimaxKey) }
                    .getOrElse { ToolResult.fail("image_gen_error: ${it.message ?: it.javaClass.simpleName}") }
            }

            ToolResult.fail(
                "image_gen_failed: Gemini path didn't return an image and no MiniMax key is set as fallback.",
            )
        }
    }

    // ─── Gemini path ─────────────────────────────────────────────────
    // POST https://generativelanguage.googleapis.com/v1beta/models/
    //   gemini-2.5-flash-image-preview:generateContent?key=<KEY>
    // body: { contents:[{parts:[{text:"<prompt>"}]}],
    //         generationConfig:{responseModalities:["IMAGE"]} }
    // response contains parts with inlineData{mimeType,data:base64}.
    private fun generateViaGemini(prompt: String, apiKey: String): ToolResult? {
        val bodyJson = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", prompt) })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("responseModalities", buildJsonArray {
                    add(JsonPrimitive("IMAGE"))
                })
            })
        }
        val body = bodyJson.toString().toRequestBody("application/json".toMediaTypeOrNull())
        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
            "$GEMINI_IMAGE_MODEL:generateContent?key=$apiKey"
        val req = Request.Builder().url(url).post(body)
            .header("Content-Type", "application/json")
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "Gemini image-gen http ${resp.code}: ${resp.body?.string()?.take(300)}")
                return null
            }
            val text = resp.body?.string().orEmpty()
            val (bytes, mime) = parseGeminiInlineImage(text) ?: run {
                Log.w(TAG, "Gemini image-gen: no inlineData in response (${text.take(200)})")
                return null
            }
            val file = saveBytes(bytes, mimeToExt(mime))
            return ToolResult.ok(
                """{"path":"${file.absolutePath.escape()}","backend":"gemini","model":"$GEMINI_IMAGE_MODEL","prompt":"${prompt.escape()}"}""",
            )
        }
    }

    private fun parseGeminiInlineImage(json: String): Pair<ByteArray, String>? = runCatching {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
        val candidates = root["candidates"]?.jsonArray ?: return@runCatching null
        for (cand in candidates) {
            val parts = cand.jsonObject["content"]?.jsonObject?.get("parts")?.jsonArray
                ?: continue
            for (part in parts) {
                val inline = part.jsonObject["inlineData"]?.jsonObject ?: continue
                val mime = inline["mimeType"]?.jsonPrimitive?.contentOrNull() ?: continue
                val b64 = inline["data"]?.jsonPrimitive?.contentOrNull() ?: continue
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                if (bytes.isNotEmpty()) return@runCatching bytes to mime
            }
        }
        null
    }.getOrNull()

    // ─── MiniMax fallback path ───────────────────────────────────────
    private fun generateViaMiniMax(prompt: String, aspect: String, apiKey: String): ToolResult {
        val body = buildJsonObject {
            put("model", "image-01")
            put("prompt", prompt)
            put("aspect_ratio", aspect)
            put("response_format", "url")
            put("n", 1)
        }.toString().toRequestBody("application/json".toMediaTypeOrNull())

        val req = Request.Builder()
            .url("https://api.minimax.io/v1/image_generation")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                return ToolResult.fail(
                    "image_gen_failed: http ${resp.code} — your MiniMax tier may not include image generation.",
                )
            }
            val text = resp.body?.string().orEmpty()
            val urls = parseImageUrls(text)
            if (urls.isEmpty()) return ToolResult.fail("image_gen_failed: no image url in response — ${text.take(200)}")
            val firstUrl = urls.first()
            val downloaded = downloadImage(firstUrl)
                ?: return ToolResult.fail(
                    "download_failed: $firstUrl — MiniMax returned a URL but the bytes weren't reachable. " +
                        "Configure a Gemini API key (inline-bytes path, no second download) to avoid this.",
                )
            return ToolResult.ok(
                """{"path":"${downloaded.absolutePath.escape()}","backend":"minimax","url":"$firstUrl","prompt":"${prompt.escape()}"}""",
            )
        }
    }

    /** Extract image URLs from the MiniMax response JSON.
     *  Shape: `{"data":{"image_urls":["https://...", ...]}, ...}` */
    private fun parseImageUrls(json: String): List<String> = runCatching {
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
        val data = parsed["data"]?.jsonObject ?: return@runCatching emptyList<String>()
        val urls = data["image_urls"]?.jsonArray ?: return@runCatching emptyList<String>()
        urls.mapNotNull { it.jsonPrimitive.contentOrNull() }
    }.getOrElse { emptyList() }

    private fun downloadImage(url: String): File? = runCatching {
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val bytes = resp.body?.bytes() ?: return@use null
            val ext = url.substringAfterLast('.', "png").take(4).lowercase()
                .takeIf { it in setOf("png", "jpg", "jpeg", "webp") } ?: "png"
            saveBytes(bytes, ext)
        }
    }.getOrNull()

    /** Common write path for both backends. */
    private fun saveBytes(bytes: ByteArray, ext: String): File {
        val dir = File(context.filesDir, "canvas/images").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.$ext")
        file.writeBytes(bytes)
        return file
    }

    private fun mimeToExt(mime: String): String = when (mime.lowercase()) {
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        else -> "png"
    }

    private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()
    private fun String.escape() = JSONObject.quote(this).removeSurrounding("\"")

    companion object {
        private const val TAG = "Mythara/ImageGen"
        /** Gemini's image-generation model. "Nano Banana" — returns
         *  PNG bytes inline in the `:generateContent` response under
         *  the same Gemini API key used for vision captioning. */
        private const val GEMINI_IMAGE_MODEL = "gemini-2.5-flash-image-preview"
    }
}
