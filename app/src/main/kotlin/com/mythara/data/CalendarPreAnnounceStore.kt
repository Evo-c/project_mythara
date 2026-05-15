package com.mythara.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings for the **Calendar Pre-Announcer** — a recurring scanner
 * that fires a short "<title> in 3 minutes — get ready" announcement
 * 3 minutes before every calendar event. Speaks via TTS by default;
 * if Music Mode is on, plays the announcement as a tone phrase
 * instead so the secret-language path stays consistent.
 *
 * Off by default — opt-in. The toggle lives in the Settings screen;
 * once on, the periodic worker registers AlarmManager exact alarms
 * for every upcoming event in the scan window.
 *
 * `alarmedEventIds` is the persistence layer for "we've already
 * scheduled an alarm for this calendar event id" — keys are
 * `<eventId>:<startMs>` so the same event recurring later still
 * gets re-scheduled, and one-off events that get rescheduled by
 * the user (different startMs) are caught.
 */
@Singleton
class CalendarPreAnnounceStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "mythara_calendar_preannounce")

    private val keyEnabled = booleanPreferencesKey("enabled")
    private val keyAlarmedEventIds = stringSetPreferencesKey("alarmed_event_ids")

    fun enabledFlow(): Flow<Boolean> = ctx.dataStore.data.map { it[keyEnabled] ?: false }

    suspend fun isEnabled(): Boolean = enabledFlow().first()

    suspend fun setEnabled(value: Boolean) {
        ctx.dataStore.edit { it[keyEnabled] = value }
    }

    /** "We've already scheduled an alarm for this event-instance"
     *  guard — keyed `<eventId>:<startMs>` so a recurring event's
     *  individual instances are tracked separately. */
    suspend fun isAlarmed(key: String): Boolean =
        ctx.dataStore.data.map { it[keyAlarmedEventIds] ?: emptySet() }
            .first()
            .contains(key)

    suspend fun markAlarmed(key: String) {
        ctx.dataStore.edit { prefs ->
            val current = prefs[keyAlarmedEventIds] ?: emptySet()
            prefs[keyAlarmedEventIds] = current + key
        }
    }

    /** Periodic cleanup — drop event-instance keys whose start time
     *  has passed by more than [GRACE_MS]. Keeps the DataStore set
     *  bounded over weeks of use. */
    suspend fun pruneStaleAlarmedKeys(nowMs: Long) {
        ctx.dataStore.edit { prefs ->
            val current = prefs[keyAlarmedEventIds] ?: return@edit
            val kept = current.filter { key ->
                val startMs = key.substringAfter(':').toLongOrNull() ?: return@filter true
                startMs > nowMs - GRACE_MS
            }.toSet()
            if (kept.size != current.size) prefs[keyAlarmedEventIds] = kept
        }
    }

    companion object {
        /** How long after an event's start we keep its alarmed-key
         *  in the set. 4 hours so a long meeting that runs past its
         *  scheduled start doesn't get re-announced if the worker
         *  re-scans mid-event. */
        const val GRACE_MS = 4L * 60 * 60 * 1000
    }
}
