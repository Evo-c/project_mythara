package com.mythara.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.Wearable
import com.mythara.analytics.ContactProfileRepository
import com.mythara.audit.AuditRepository
import com.mythara.tasks.TaskRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Publishes a compact snapshot of cluster state — recent tasks,
 * favorite people + their latest insights, today's calendar, the next
 * reminder, and the recent agent audit log — to the watch companion
 * app over the Wearable Data Layer, so the watch can show those lists
 * alongside push-to-talk.
 *
 * Payload is a tiny delimited format using ASCII control characters
 * (record / unit / group separators) rather than JSON, so the :wear
 * module needs no serialization dependency to parse it. Pushed on a
 * slow timer; the watch always renders the last cached snapshot.
 */
@Singleton
class WatchClusterDataPusher @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val tasks: TaskRepository,
    private val contacts: ContactProfileRepository,
    private val audit: AuditRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun start() {
        scope.launch {
            while (true) {
                pushAllNow()
                delay(PUSH_INTERVAL_MS)
            }
        }
    }

    /** Fire every cluster-data push immediately. Public so the
     *  manual "sync to watch now" button + the periodic watch-sync
     *  worker can trigger a full refresh outside the in-process
     *  loop. Failures per push are individually swallowed so one
     *  bad surface doesn't take the others down. */
    suspend fun pushAllNow() {
        runCatching { pushTasks() }.onFailure { Log.w(TAG, "task push: ${it.message}") }
        runCatching { pushPeople() }.onFailure { Log.w(TAG, "people push: ${it.message}") }
        runCatching { pushCalendar() }.onFailure { Log.w(TAG, "calendar push: ${it.message}") }
        runCatching { pushReminder() }.onFailure { Log.w(TAG, "reminder push: ${it.message}") }
        runCatching { pushAudit() }.onFailure { Log.w(TAG, "audit push: ${it.message}") }
    }

    private suspend fun pushTasks() {
        val rows = tasks.dao.listRecent(30)
        val payload = rows.joinToString(REC) { t ->
            listOf(
                clean(t.title).take(120),
                clean(t.status),
                clean(t.resultText.orEmpty()).take(400),
            ).joinToString(UNIT)
        }
        send(PATH_TASKS, payload)
    }

    private suspend fun pushPeople() {
        val favs = contacts.dao.listAll().filter { it.isFavorite }.take(20)
        val payload = favs.joinToString(REC) { p ->
            val insight = (
                p.personalityInsights?.takeIf { it.isNotBlank() }
                    ?: p.relationshipSummary?.takeIf { it.isNotBlank() }
                    ?: "No insights captured yet — keep chatting and Lumi will learn."
                ).let { clean(it).take(600) }
            val points = parseStringArray(p.keyPointsJson)
                .take(6)
                .joinToString(GROUP) { clean(it).take(160) }
            listOf(clean(p.displayName).take(60), insight, points).joinToString(UNIT)
        }
        send(PATH_PEOPLE, payload)
    }

    private suspend fun pushCalendar() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return // no calendar permission — nothing to push
        }
        val now = System.currentTimeMillis()
        // In-progress events (started up to 1h ago) through the next 18h,
        // so "the day" stays covered even late in the evening.
        val events = withContext(Dispatchers.IO) {
            queryEvents(now - 3_600_000L, now + 18 * 3_600_000L)
        }
        val payload = events.take(25).joinToString(REC) { e ->
            listOf(
                clean(e.title).take(80),
                e.startMs.toString(),
                clean(e.location).take(60),
            ).joinToString(UNIT)
        }
        send(PATH_CALENDAR, payload)
    }

    private fun queryEvents(startMs: Long, endMs: Long): List<CalEvent> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startMs.toString())
            .appendPath(endMs.toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.EVENT_LOCATION,
        )
        val out = mutableListOf<CalEvent>()
        runCatching {
            ctx.contentResolver.query(
                uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                val tIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                val bIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                val lIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
                while (c.moveToNext()) {
                    out.add(
                        CalEvent(
                            title = c.getString(tIdx)?.takeIf { it.isNotBlank() } ?: "(untitled)",
                            startMs = c.getLong(bIdx),
                            location = c.getString(lIdx).orEmpty(),
                        ),
                    )
                }
            }
        }.onFailure { Log.w(TAG, "calendar query failed: ${it.message}") }
        return out
    }

    private data class CalEvent(val title: String, val startMs: Long, val location: String)

    /** Push the soonest upcoming reminder (scheduled task) to the watch
     *  face's reminder complication. Empty payload = none upcoming. */
    private suspend fun pushReminder() {
        val now = System.currentTimeMillis()
        val next = tasks.dao.listRecent(200)
            .mapNotNull { t -> t.scheduledForMs?.let { sf -> t to sf } }
            .filter { (t, sf) -> sf > now && t.status in LIVE_TASK_STATUSES }
            .minByOrNull { it.second }
        val payload = if (next != null) {
            listOf(clean(next.first.title).take(80), next.second.toString()).joinToString(UNIT)
        } else {
            "" // no upcoming reminder
        }
        send(PATH_REMINDER, payload)
    }

    /** Push the recent agent audit log (most-recent-first) so the watch
     *  app can scroll through what Lumi has been doing. */
    private suspend fun pushAudit() {
        val rows = audit.dao.listRecent(40)
        val payload = rows.joinToString(REC) { e ->
            val title = e.toolName?.takeIf { it.isNotBlank() } ?: e.kind
            val detail = listOfNotNull(
                e.contactName?.takeIf { it.isNotBlank() }?.let { "→ $it" },
                e.resultPreview?.takeIf { it.isNotBlank() }
                    ?: e.note?.takeIf { it.isNotBlank() }
                    ?: e.argsPreview?.takeIf { it.isNotBlank() },
            ).joinToString("  ")
            listOf(
                e.tsMillis.toString(),
                clean(e.kind),
                clean(title).take(40),
                if (e.resultOk) "1" else "0",
                clean(detail).take(160),
            ).joinToString(UNIT)
        }
        send(PATH_AUDIT, payload)
    }

    private fun parseStringArray(raw: String): List<String> = runCatching {
        json.parseToJsonElement(raw).jsonArray.mapNotNull {
            runCatching { it.jsonPrimitive.content }.getOrNull()?.takeIf { s -> s.isNotBlank() }
        }
    }.getOrDefault(emptyList())

    /** Strip the delimiter control chars from a field value. */
    private fun clean(s: String): String =
        s.replace(REC, " ").replace(UNIT, " ").replace(GROUP, " ").replace('\n', ' ').trim()

    private fun send(path: String, payload: String) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val nodeClient = Wearable.getNodeClient(ctx)
        val msgClient = Wearable.getMessageClient(ctx)
        nodeClient.connectedNodes
            .addOnSuccessListener { nodes ->
                for (node in nodes) msgClient.sendMessage(node.id, path, bytes)
                if (nodes.isNotEmpty()) {
                    Log.d(TAG, "pushed $path (${bytes.size}B) to ${nodes.size} node(s)")
                }
            }
            .addOnFailureListener { e -> Log.w(TAG, "node list failed: ${e.message}") }
    }

    companion object {
        private const val TAG = "Mythara/ClusterPush"
        private const val PUSH_INTERVAL_MS = 2 * 60 * 1000L

        const val PATH_TASKS = "/mythara/tasks"
        const val PATH_PEOPLE = "/mythara/people"
        const val PATH_CALENDAR = "/mythara/calendar"
        const val PATH_REMINDER = "/mythara/reminder"
        const val PATH_AUDIT = "/mythara/audit"

        private val LIVE_TASK_STATUSES = setOf("PENDING", "CLAIMED", "RUNNING")

        // Delimiters — ASCII control chars (RS / US / GS) that won't
        // appear in real text. Keep in sync with :wear ClusterDataStore.
        const val REC = "\u001E"
        const val UNIT = "\u001F"
        const val GROUP = "\u001D"
    }
}
