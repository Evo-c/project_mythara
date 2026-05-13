package com.mythara.agent.tools

import android.util.Log
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.audit.AuditRepository
import com.mythara.memory.DeviceIdStore
import com.mythara.memory.MemorySettings
import com.mythara.memory.github.GitHubClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `list_mythara_devices` — enumerate every other Mythara install signed
 * into the same memory-sync repo.
 *
 * Two sources, unioned and tagged with a `source` field so the agent
 * can tell the user how confident the listing is:
 *
 *   1. **inbox files** (`device_messages/inbox/<device_id>.jsonl`)
 *      — canonical. Every device that's ever received a device-to-
 *      device message has a file here. Requires the memory repo to
 *      be configured + reachable.
 *
 *   2. **audit log distinct** — every device id that's ever stamped
 *      an audit row on THIS device's local audit DB. Useful when the
 *      memory repo is offline, but only sees devices whose actions
 *      have been imported via memory-sync into this device's audit
 *      table.
 *
 * The current device is always present and tagged `is_self: true` so
 * the agent doesn't say "I see one other device" when listing itself.
 *
 * Returned shape:
 * ```
 * {
 *   "ok": true,
 *   "this_device": "pixel9pro-7k2m9pq3",
 *   "devices": [
 *     {"id": "pixel10pro-aaaa1234", "is_self": false,
 *      "source": "inbox+audit", "inbox_size_bytes": 8742, "audit_entries": 23,
 *      "last_seen_ms": 1715628000000},
 *     ...
 *   ],
 *   "memory_sync_configured": true,
 *   "memory_sync_reachable": true
 * }
 * ```
 *
 * The agent uses `id` as the `target_device_id` for follow-up tools
 * like [RequestRemoteLocationTool].
 */
@Singleton
class ListMytharaDevicesTool @Inject constructor(
    private val deviceIdStore: DeviceIdStore,
    private val memorySettings: MemorySettings,
    private val auditRepo: AuditRepository,
) : Tool {

    override val name: String = "list_mythara_devices"

    override val description: String =
        "List every Mythara install signed into the same memory-sync repo as this device. " +
            "Returns the stable device ids the user can pass to other cross-device tools " +
            "(e.g. request_remote_location). Always includes THIS device (tagged is_self=true). " +
            "Use when the user asks 'what other devices do I have', 'what phones / tablets is " +
            "Mythara on', 'where's my other phone', 'list my Mythara installs', etc."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { /* no params */ })
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val myId = runCatching { deviceIdStore.id() }.getOrElse { "unknown-device" }

        // Source A: inbox directory listing from the memory repo.
        val cfg = memorySettings.snapshot()
        val configured = cfg.configured
        val inbox: Map<String, Long> // device-id → file size in bytes
        var reachable = false
        var inboxError: String? = null
        if (!configured) {
            inbox = emptyMap()
            inboxError = "memory sync not configured (set PAT + repo in Settings → Memory sync)"
        } else {
            val client = GitHubClient(cfg.pat!!)
            when (val r = client.listDirectory(cfg.owner, cfg.repo, "device_messages/inbox")) {
                is GitHubClient.Outcome.Ok -> {
                    reachable = true
                    inbox = r.value
                        .filter { it.type == "file" && it.name.endsWith(".jsonl") }
                        .associate { it.name.removeSuffix(".jsonl") to it.size }
                }
                is GitHubClient.Outcome.NotFound -> {
                    reachable = true // repo IS reachable; just no inbox yet
                    inbox = emptyMap()
                    inboxError = "no device_messages/inbox/ in the repo yet — " +
                        "this is the first device to sync, or no cross-device messages have been sent"
                }
                is GitHubClient.Outcome.Unauthorized -> {
                    inbox = emptyMap()
                    inboxError = "GitHub auth failed — token may have expired or been revoked"
                }
                is GitHubClient.Outcome.Conflict -> {
                    inbox = emptyMap()
                    inboxError = "GitHub conflict: ${r.message}"
                }
                is GitHubClient.Outcome.Error -> {
                    inbox = emptyMap()
                    inboxError = "GitHub error ${r.httpStatus}: ${r.message}"
                }
            }
        }

        // Source B: audit-log distinct device ids on this device.
        val auditRows = runCatching { auditRepo.dao.listDistinctDevices() }
            .onFailure { Log.w(TAG, "audit distinct query failed: ${it.message}") }
            .getOrDefault(emptyList())
        val auditByDevice: Map<String, com.mythara.audit.DistinctDeviceRow> =
            auditRows.associateBy { it.deviceId }

        // Union: every id seen in either source + always the local id.
        val allIds = buildSet {
            add(myId)
            addAll(inbox.keys)
            addAll(auditByDevice.keys)
        }

        val devices = allIds.map { id ->
            val inInbox = id in inbox
            val inAudit = id in auditByDevice
            val source = listOfNotNull(
                if (inInbox) "inbox" else null,
                if (inAudit) "audit" else null,
            ).joinToString("+").ifEmpty { "self" }
            buildJsonObject {
                put("id", id)
                put("is_self", id == myId)
                put("source", source)
                if (inInbox) put("inbox_size_bytes", inbox[id] ?: 0L)
                if (inAudit) {
                    val row = auditByDevice[id]!!
                    put("audit_entries", row.entries)
                    put("last_seen_ms", row.lastSeenMs)
                }
            }
        }.sortedBy { (it["id"] as JsonPrimitive).content }

        val result = buildJsonObject {
            put("ok", true)
            put("this_device", myId)
            put("memory_sync_configured", configured)
            put("memory_sync_reachable", reachable)
            if (inboxError != null) put("inbox_status", inboxError)
            put(
                "devices",
                buildJsonArray {
                    devices.forEach { add(it as kotlinx.serialization.json.JsonElement) }
                },
            )
        }
        return ToolResult(true, result.toString())
    }

    companion object {
        private const val TAG = "Mythara/ListDevices"
    }
}
