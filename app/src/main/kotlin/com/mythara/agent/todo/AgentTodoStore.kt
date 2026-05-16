package com.mythara.agent.todo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The agent's INTERNAL todo list — work it has decided to do
 * (or has been asked to do) but hasn't yet completed.
 *
 * This is the substrate for the "agent maintains a todo and keeps
 * waking until done" behaviour. The store is a simple persistent
 * queue; the [AgentAutoContinueController] is what actually polls
 * it after each turn and submits the next item.
 *
 * Items are derived from THREE sources:
 *   1. **User intent** — when a chat message implies follow-up
 *      ("set up a meeting with Anurag and remind me 10 min before"
 *      → two items: schedule_meeting + create_reminder).
 *   2. **Memory patterns** — daily review surfaces patterns the
 *      agent should act on ("you've missed 3 evening reminders
 *      this week with reason: tired" → suggest moving them
 *      earlier).
 *   3. **Self-decided** — the agent's own continuation logic
 *      ("user asked for X, I did partial X, the next step is Y").
 *
 * Sources 1+3 are wired through this store from
 * [com.mythara.agent.AgentRunner] when a chat turn finishes (the
 * agent emits suggested follow-ups in its final message). Source
 * 2 is the daily-review extractor — currently scaffolded in
 * [com.mythara.behavior.BehaviorLearningSummarizer] but the
 * extractor that translates summary → todo items is the next-
 * round build.
 *
 * Persistence is DataStore Preferences (single JSON-encoded list)
 * — modest scale (typically 0-20 active items) so we don't need
 * Room. List shape preserves insertion order so the agent works
 * through items in the order they landed.
 */
@Singleton
class AgentTodoStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "mythara_agent_todo")

    /** A single pending item the agent should address. */
    @Serializable
    data class Item(
        val id: String,
        val text: String,
        /** Where this item came from. Lets the agent + UI label
         *  it ("auto-derived" vs "from chat" vs "self-continuation"). */
        val source: Source,
        val createdAtMs: Long,
        /** Status — pending | done | failed | skipped. Done /
         *  failed / skipped items get pruned periodically. */
        val status: Status = Status.Pending,
    )

    @Serializable
    enum class Source { UserIntent, MemoryPattern, SelfContinuation, External }

    @Serializable
    enum class Status { Pending, Done, Failed, Skipped }

    @Serializable
    private data class Wrapper(val items: List<Item> = emptyList())

    private val key = stringPreferencesKey("items.json")
    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }

    fun observeAll(): Flow<List<Item>> =
        ctx.dataStore.data.map { decode(it[key]).items }

    suspend fun all(): List<Item> = decode(ctx.dataStore.data.first()[key]).items

    /** Pending items only, in insertion order. */
    suspend fun pending(): List<Item> = all().filter { it.status == Status.Pending }

    suspend fun add(item: Item) {
        ctx.dataStore.edit { prefs ->
            val current = decode(prefs[key]).items
            // Idempotent — same id is a no-op (caller may re-emit
            // the same suggested follow-up across turns).
            if (current.any { it.id == item.id }) return@edit
            prefs[key] = json.encodeToString(Wrapper.serializer(), Wrapper(current + item))
        }
    }

    suspend fun markStatus(id: String, status: Status) {
        ctx.dataStore.edit { prefs ->
            val current = decode(prefs[key]).items
            val updated = current.map { if (it.id == id) it.copy(status = status) else it }
            prefs[key] = json.encodeToString(Wrapper.serializer(), Wrapper(updated))
        }
    }

    /** Drop everything not Pending — used periodically to keep
     *  the JSON blob small. */
    suspend fun pruneCompleted() {
        ctx.dataStore.edit { prefs ->
            val current = decode(prefs[key]).items.filter { it.status == Status.Pending }
            prefs[key] = json.encodeToString(Wrapper.serializer(), Wrapper(current))
        }
    }

    suspend fun clearAll() {
        ctx.dataStore.edit { it.remove(key) }
    }

    private fun decode(raw: String?): Wrapper {
        if (raw.isNullOrBlank()) return Wrapper()
        return runCatching {
            json.decodeFromString(Wrapper.serializer(), raw)
        }.getOrDefault(Wrapper())
    }
}
