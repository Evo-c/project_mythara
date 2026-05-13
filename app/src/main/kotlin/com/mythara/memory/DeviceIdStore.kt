package com.mythara.memory

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stable per-install identifier stamped onto every memory record so the
 * GitHub repo (which collects writes from multiple Mythara installs the
 * same user runs) stays attributable line-by-line.
 *
 * Properties:
 *   - **Generated once** on first call, then persisted in a plaintext
 *     DataStore. It is NOT a secret — the whole point is for it to ride
 *     publicly on every record in the user's own private repo.
 *   - **Survives** chat-history clear / vault wipe. Only "uninstall +
 *     reinstall" or a "Forget everything" that drops this DataStore will
 *     rotate it. That's intentional — rotating per session would defeat
 *     attribution.
 *   - **Readable** in commit logs. Format `<model-slug>-<8-base36>` e.g.
 *     `pixel9pro-7k2m9pq3`. The model slug is best-effort from [Build.MODEL];
 *     entropy is from [SecureRandom].
 *
 * Not derived from Android ID / IMEI / advertising ID — those carry
 * cross-app reset semantics we don't want and policy risk we don't need.
 */
@Singleton
class DeviceIdStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "mythara_device_id")

    private val keyId = stringPreferencesKey("device.id")

    @Volatile private var cached: String? = null

    /**
     * Get the device ID, generating + persisting one on first call. Safe
     * to call from any coroutine. The first invocation does a DataStore
     * read; subsequent calls hit the in-memory cache.
     */
    suspend fun id(): String {
        cached?.let { return it }
        val existing = ctx.dataStore.data.first()[keyId]
        if (!existing.isNullOrBlank()) {
            cached = existing
            return existing
        }
        val fresh = generate()
        ctx.dataStore.edit { it[keyId] = fresh }
        cached = fresh
        return fresh
    }

    /**
     * Best-effort synchronous accessor. Returns the cached value if [id]
     * has been called at least once this process, otherwise [FALLBACK].
     * Callers that need correctness from a non-suspend path should call
     * [id] from a coroutine at app start and cache the result themselves.
     */
    fun idOrFallback(): String = cached ?: FALLBACK

    private fun generate(): String {
        val slug = slugify(Build.MODEL).ifBlank { "android" }.take(12)
        val rnd = SecureRandom().nextLong().toULong().toString(36).padStart(13, '0').take(8)
        return "$slug-$rnd"
    }

    private fun slugify(s: String?): String =
        (s ?: "").lowercase().replace(Regex("[^a-z0-9]+"), "").take(12)

    companion object {
        /** Used only when the synchronous accessor is hit before warm-up. */
        const val FALLBACK = "unknown-device"
    }
}
