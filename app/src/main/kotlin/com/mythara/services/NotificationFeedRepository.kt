package com.mythara.services

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Makes [NotificationListener]'s otherwise-ephemeral rolling buffer
 * observable so the in-app Notification Hub ([com.mythara.ui.notifications.NotificationHubScreen])
 * can render a LIVE list of the phone's notifications and update as
 * they post / dismiss.
 *
 * The listener calls [publish] with a fresh `snapshot()` on every
 * capture / removal / (dis)connect; the hub collects [feed]. No Room
 * persistence — the buffer is ephemeral by design (privacy: nothing
 * notification-derived is written to disk here), so the hub shows
 * whatever the listener currently holds and goes empty when the
 * listener disconnects (e.g. permission revoked / reboot).
 *
 * Singleton so the one listener instance and the one VM share it.
 */
@Singleton
class NotificationFeedRepository @Inject constructor() {

    private val _feed = MutableStateFlow<List<NotificationListener.Recent>>(emptyList())

    /** Live snapshot of the listener's buffer, most-recent first. */
    val feed: StateFlow<List<NotificationListener.Recent>> = _feed.asStateFlow()

    /** Called by [NotificationListener] whenever its buffer changes. */
    fun publish(items: List<NotificationListener.Recent>) {
        _feed.value = items
    }
}

/**
 * Open the source app of a captured notification: try the captured
 * tap [PendingIntent][android.app.PendingIntent] first (this is how
 * the system shade opens an app on notification tap — it lands on
 * the exact screen the notification points to), and fall back to
 * `PackageManager.getLaunchIntentForPackage(packageName)` if no
 * content intent was attached. Returns true if something launched.
 */
fun openNotificationSource(ctx: Context, recent: NotificationListener.Recent): Boolean {
    // 1. Prefer the captured contentIntent — lands on the exact
    //    activity the notification points to (a specific WhatsApp
    //    chat, a calendar event, etc). Use the (ctx, code, intent)
    //    overload so we can inject FLAG_ACTIVITY_NEW_TASK — required
    //    when launching from a non-Activity context (which is what
    //    we're in: Application context from the VM). Without the
    //    flag, send() succeeds API-wise but the target activity
    //    never appears.
    recent.contentIntent?.let { pi ->
        val fillIn = Intent().apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        // Android 14+ enforces Background-Activity-Launch (BAL):
        // even when we (the sender) are foreground, the PendingIntent
        // creator must explicitly grant BAL — most apps don't. The
        // NotificationManager grants a 30 s allowlist on post, but
        // once that expires (or for older entries replayed from our
        // buffer), pi.send() lands as BAL_BLOCK and silently fails.
        //
        // ActivityOptions.setPendingIntentBackgroundActivityStartMode
        // (MODE_BACKGROUND_ACTIVITY_START_ALLOWED) is the documented
        // escape hatch: WE (the foreground sender) explicitly grant
        // BAL for this single firing. Available since API 34.
        val opts = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                android.app.ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                    )
                    .toBundle()
            } else null
        }.getOrNull()
        val sent = runCatching {
            // 7-arg overload: (context, requestCode, intent, onFinished,
            // handler, requiredPermission, options). Only the last
            // matters for BAL — keep the rest null/default.
            pi.send(ctx, 0, fillIn, null, null, null, opts)
        }
        if (sent.isSuccess) {
            Log.d("Mythara/NotifFeed", "contentIntent.send ok for ${recent.packageName}")
            return true
        }
        Log.w(
            "Mythara/NotifFeed",
            "contentIntent.send failed for ${recent.packageName}: ${sent.exceptionOrNull()?.message}",
        )
    }
    // 2. Fallback: launch the app's main activity.
    val launch = runCatching { ctx.packageManager.getLaunchIntentForPackage(recent.packageName) }
        .getOrNull() ?: run {
            Log.w("Mythara/NotifFeed", "no launch intent for ${recent.packageName}")
            return false
        }
    return runCatching {
        ctx.startActivity(launch.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        Log.d("Mythara/NotifFeed", "fallback launch ok for ${recent.packageName}")
        true
    }.onFailure {
        Log.w("Mythara/NotifFeed", "fallback launch failed for ${recent.packageName}: ${it.message}")
    }.getOrDefault(false)
}
