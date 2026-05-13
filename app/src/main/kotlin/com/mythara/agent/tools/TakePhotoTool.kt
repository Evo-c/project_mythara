package com.mythara.agent.tools

import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.camera.CameraCapture
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `take_photo` — capture a single still from the device's camera and
 * save it to private app storage. The tool returns JSON with the path,
 * dimensions, byte size, and lens used. A follow-up `vision_query`
 * tool (M5+) will route the saved image through MiniMax-VL-01 for
 * "what's in this photo" prompts.
 *
 * Model usage patterns:
 *  - "Take a picture of this" → call with default args (back lens)
 *  - "Take a selfie" → `lens: "front"`
 *
 * Failure modes the model needs to understand:
 *  - `permission_denied` — CAMERA not granted; user must allow it
 *  - `not_ready` — camera couldn't be opened (Mythara likely
 *    backgrounded, or another app is holding the camera)
 *  - `capture_failed` — CameraX returned a hardware/driver error
 *
 * Read-only-ish — captures don't modify external state, only write a
 * file to Mythara's private filesDir. ConfirmationGate is therefore
 * not required; the user is implicitly consenting by asking for a
 * photo. We disclose the saved path in the tool result so the model
 * can mention it if relevant ("I saved it at .../photos/...jpg").
 */
@Singleton
class TakePhotoTool @Inject constructor(
    private val capture: CameraCapture,
) : Tool {

    @Serializable
    data class Response(
        val path: String,
        val widthPx: Int,
        val heightPx: Int,
        val sizeBytes: Long,
        val lens: String,
        val captureTimeMs: Long,
    )

    override val name: String = "take_photo"

    override val description: String =
        "Capture one photo using the phone's camera and save it to Mythara's private storage. Returns the file path, dimensions, and lens used. Use when the user asks 'take a picture of this' or 'take a selfie'."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "lens",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Which camera to use. 'back' for the rear camera (default; what the user is pointing the phone at), 'front' for the selfie camera.",
                        )
                        put(
                            "enum",
                            kotlinx.serialization.json.JsonArray(
                                listOf(JsonPrimitive("back"), JsonPrimitive("front")),
                            ),
                        )
                    },
                )
            },
        )
        put("required", kotlinx.serialization.json.JsonArray(emptyList()))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val lensArg = (args["lens"] as? JsonPrimitive)?.content?.lowercase()
        val lens = when (lensArg) {
            "front", "selfie" -> CameraCapture.Lens.Front
            else -> CameraCapture.Lens.Back
        }
        return when (val r = capture.capture(lens)) {
            is CameraCapture.Result.Ok -> {
                val response = Response(
                    path = r.path,
                    widthPx = r.widthPx,
                    heightPx = r.heightPx,
                    sizeBytes = r.sizeBytes,
                    lens = r.lens,
                    captureTimeMs = r.captureTimeMs,
                )
                ToolResult(ok = true, output = JSON.encodeToString(Response.serializer(), response))
            }
            is CameraCapture.Result.Fail -> ToolResult(
                ok = false,
                output = """{"error":"${r.code}","detail":${JsonPrimitive(r.detail)}}""",
            )
        }
    }

    companion object {
        private val JSON = Json { encodeDefaults = false; prettyPrint = false }
    }
}
