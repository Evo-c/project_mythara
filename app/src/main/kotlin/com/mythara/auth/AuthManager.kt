package com.mythara.auth

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outer-app auth state with grace-period auto-lock.
 *
 * The state machine:
 *   - Construction: state = Locked, backgroundedAt = 0.
 *   - User authenticates via BiometricPrompt: state → Unlocked.
 *   - App goes to background (ProcessLifecycleOwner.onStop):
 *     [markBackgrounded] records the timestamp. State STAYS
 *     Unlocked at this point — locking happens only on the
 *     foreground transition, so a quick switch-to-WhatsApp-and-back
 *     doesn't re-trigger biometric.
 *   - App returns to foreground (ProcessLifecycleOwner.onStart):
 *     [markForegrounded] reads [AuthSettings.timeoutMs] and decides:
 *       * elapsed < timeout → keep Unlocked, clear timestamp
 *       * elapsed ≥ timeout → flip to Locked
 *       * timeout == NEVER_MS → keep Unlocked, no decision
 *       * timeout == IMMEDIATE_MS → matches the pre-grace-period
 *         behaviour: lock on every backgrounding
 *   - Process death: singleton recreated as Locked. Cold start path.
 *
 * The grace period only relaxes lock-on-foreground-transition;
 * background services (AgentRunner, NotificationListener, etc.)
 * never consulted auth state in the first place, so they keep
 * running regardless of the lock state.
 */
@Singleton
class AuthManager @Inject constructor(
    private val settings: AuthSettings,
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Locked)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    /** 0 = not currently backgrounded; otherwise epoch ms when we lost foreground. */
    @Volatile private var backgroundedAtMs: Long = 0L

    fun lock() {
        _state.value = AuthState.Locked
        backgroundedAtMs = 0L
    }

    fun unlock() {
        _state.value = AuthState.Unlocked
        backgroundedAtMs = 0L
    }

    /**
     * Called by [com.mythara.MainActivity] when the entire process
     * leaves the foreground. Just records the timestamp; the
     * decision to re-lock happens on the way back via
     * [markForegrounded].
     */
    fun markBackgrounded() {
        if (!isUnlocked) return // nothing to do — already locked
        if (backgroundedAtMs == 0L) {
            backgroundedAtMs = System.currentTimeMillis()
            Log.d(TAG, "markBackgrounded — grace period started")
        }
    }

    /**
     * Called by [com.mythara.MainActivity] when the process returns
     * to foreground. Compares elapsed-since-backgrounded against
     * the user's configured timeout and flips to Locked when
     * exceeded.
     */
    suspend fun markForegrounded() {
        if (!isUnlocked) return // already locked; AuthGate will show
        val stamp = backgroundedAtMs
        if (stamp == 0L) return // never backgrounded since unlock
        val timeout = runCatching { settings.timeoutMs() }
            .getOrDefault(AuthSettings.DEFAULT_TIMEOUT_MS)
        backgroundedAtMs = 0L
        if (timeout == AuthSettings.NEVER_MS) return  // never auto-lock
        val elapsed = System.currentTimeMillis() - stamp
        if (elapsed >= timeout) {
            Log.d(TAG, "auto-lock fired — elapsed=$elapsed timeout=$timeout")
            _state.value = AuthState.Locked
        } else {
            Log.d(TAG, "auto-lock skipped — elapsed=$elapsed timeout=$timeout")
        }
    }

    val isUnlocked: Boolean
        get() = _state.value is AuthState.Unlocked

    companion object {
        private const val TAG = "Mythara/Auth"
    }
}
