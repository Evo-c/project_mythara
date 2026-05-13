package com.mythara.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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
 * Persisted set of "always allow this" decisions. The
 * [com.mythara.agent.ConfirmationGate] consults it before showing a
 * prompt — if the key is in the set, the destructive tool fires
 * without a confirmation dialog. The Settings allowlist-editor lets
 * the user inspect and revoke entries.
 *
 * Key shape is per-tool but always namespaced:
 *   - `send_sms_direct:+15551234`   — silent SMS to one number
 *   - `place_call_direct:+15551234` — silent dial to one number
 *   - `tap`                          — any tap (whole-tool grant)
 *   - `open_app:com.uber.driver`    — open one specific app
 *
 * Stored as a single set under `allowlist.keys`. DataStore handles
 * concurrent updates; a Tink layer isn't needed (these aren't secrets).
 */
@Singleton
class AllowlistStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "mythara_allowlist")

    private val keys = stringSetPreferencesKey("allowlist.keys")

    fun allowedFlow(): Flow<Set<String>> = ctx.dataStore.data.map { it[keys] ?: emptySet() }

    suspend fun isAllowed(key: String): Boolean {
        val current = ctx.dataStore.data.first()[keys] ?: emptySet()
        return key in current
    }

    suspend fun allow(key: String) {
        ctx.dataStore.edit { prefs ->
            val current = prefs[keys] ?: emptySet()
            prefs[keys] = current + key
        }
    }

    suspend fun revoke(key: String) {
        ctx.dataStore.edit { prefs ->
            val current = prefs[keys] ?: emptySet()
            prefs[keys] = current - key
        }
    }

    suspend fun clear() {
        ctx.dataStore.edit { prefs ->
            prefs.remove(keys)
        }
    }
}
