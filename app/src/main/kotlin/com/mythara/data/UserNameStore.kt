package com.mythara.data

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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "What should I call you" preference. Plain DataStore — the name
 * isn't a secret, just a UX detail.
 *
 * When set, AgentLoop prepends a one-liner to the system-message
 * stack so the model addresses the user by name when it feels
 * natural (greeting, acknowledgement, occasional callback). The
 * model is told NOT to over-use it — sprinkling "Ankur" into every
 * sentence reads as sycophantic.
 *
 * Empty string ⇒ no name injected; agent stays generic.
 */
@Singleton
class UserNameStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "mythara_user_name")

    private val keyName = stringPreferencesKey("name")

    fun nameFlow(): Flow<String> = ctx.dataStore.data.map { it[keyName].orEmpty() }

    suspend fun name(): String = ctx.dataStore.data.first()[keyName].orEmpty()

    suspend fun setName(value: String) {
        ctx.dataStore.edit { prefs ->
            val trimmed = value.trim()
            if (trimmed.isEmpty()) prefs.remove(keyName) else prefs[keyName] = trimmed
        }
    }
}
