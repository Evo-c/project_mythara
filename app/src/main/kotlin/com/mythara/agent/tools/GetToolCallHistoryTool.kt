package com.mythara.agent.tools

import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.audit.AuditEntry
import com.mythara.audit.AuditRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `get_tool_call_history` — surfaces the audit log of recent
 * tool calls (and adjacent agent events: redirects, user
 * cancellations, sub-agent spawns, system markers) so the
 * agent can answer "what did you just do?" / "did the SMS
 * actually send?" / "why didn't the call go through?".
 *
 * Reads from [AuditRepository] which is the same source the
 * audit panel in Settings renders. Each row carries:
 *   - kind     ("tool" | "redirect" | "user_canceled" |
 *               "subagent" | "system")
 *   - tool_name (when kind == "tool")
 *   - args_preview (200-char truncation of the args JSON)
 *   - result_ok + result_preview (200-char truncation)
 *   - latency_ms
 *   - device_id (which Mythara instance fired the action)
 *   - contact_name (resolved when the args carried a phone
 *     number that matched Favorites or the address book)
 *
 * Filters:
 *   - `tool_name` (optional) — return only entries for that
 *     specific tool name (e.g. "send_sms_direct" → only SMS
 *     send calls).
 *   - `outcome` (optional) — "ok" or "fail" to filter by
 *     success state.
 *   - `limit` (optional, default 20, max 100) — how many
 *     most-recent entries to return.
 *
 * Read-only — no ConfirmationGate.
 */
@Singleton
class GetToolCallHistoryTool @Inject constructor(
    private val audit: AuditRepository,
) : Tool {

    override val name: String = "get_tool_call_history"
    override val description: String =
        "Recent tool-call audit log — what tools the agent has run, when, with what args, " +
            "the result, latency, and the device it fired on. Use to answer 'what did you just " +
            "do?' or 'did the SMS go through?'. Optional filters: tool_name, outcome (ok|fail), limit."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "tool_name",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Optional — filter to entries for one specific tool (e.g. 'send_sms_direct').",
                        )
                    },
                )
                put(
                    "outcome",
                    buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { add(JsonPrimitive("ok")); add(JsonPrimitive("fail")) })
                        put("description", "Optional — keep only successful or only failing entries.")
                    },
                )
                put(
                    "limit",
                    buildJsonObject {
                        put("type", "integer")
                        put("description", "Most-recent N entries to return. Default 20, max 100.")
                    },
                )
            },
        )
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val toolName = (args["tool_name"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotBlank() }
        val outcome = (args["outcome"] as? JsonPrimitive)?.content?.trim()?.lowercase()
        val limit = ((args["limit"] as? JsonPrimitive)?.content?.toIntOrNull() ?: DEFAULT_LIMIT)
            .coerceIn(1, MAX_LIMIT)

        // Pull a working window large enough that a busy filter
        // (e.g. "show me only failing send_sms_direct calls" on a
        // log with mostly other entries) still returns useful
        // results. Cap at 500 so this query stays cheap.
        val raw = runCatching { audit.dao.listRecent(limit = WINDOW_LIMIT) }.getOrDefault(emptyList())
        val filtered = raw.asSequence()
            .filter { row -> toolName == null || row.toolName == toolName }
            .filter { row ->
                when (outcome) {
                    "ok" -> row.resultOk
                    "fail" -> !row.resultOk
                    else -> true
                }
            }
            .take(limit)
            .toList()

        return ToolResult.ok(
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("count", JsonPrimitive(filtered.size))
                put("entries", JsonArray(filtered.map { entryToJson(it) }))
            }.toString(),
        )
    }

    private fun entryToJson(entry: AuditEntry): JsonObject = buildJsonObject {
        put("ts", JsonPrimitive(HUMAN_FMT.format(Date(entry.tsMillis))))
        put("kind", JsonPrimitive(entry.kind))
        entry.toolName?.let { put("tool", JsonPrimitive(it)) }
        entry.argsPreview?.takeIf { it.isNotBlank() }?.let { put("args", JsonPrimitive(it.take(200))) }
        put("ok", JsonPrimitive(entry.resultOk))
        entry.resultPreview?.takeIf { it.isNotBlank() }?.let { put("result", JsonPrimitive(it.take(200))) }
        if (entry.latencyMs > 0) put("latency_ms", JsonPrimitive(entry.latencyMs))
        entry.note?.takeIf { it.isNotBlank() }?.let { put("note", JsonPrimitive(it.take(200))) }
        entry.deviceId?.takeIf { it.isNotBlank() }?.let { put("device_id", JsonPrimitive(it)) }
        entry.contactName?.takeIf { it.isNotBlank() }?.let { put("contact", JsonPrimitive(it)) }
    }

    companion object {
        private const val DEFAULT_LIMIT = 20
        private const val MAX_LIMIT = 100
        private const val WINDOW_LIMIT = 500
        private val HUMAN_FMT = SimpleDateFormat("MMM d HH:mm:ss", Locale.getDefault())
    }
}
