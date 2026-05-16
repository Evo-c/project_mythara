package com.mythara.glasses

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mythara.MainActivity
import com.mythara.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that holds the Meta DAT session alive across
 * screen-off and process-lifecycle changes, owns the
 * [GlassesGestureRouter] subscription, and pushes screen renders
 * to the glasses display whenever the [GlassesScreenStore] state
 * changes.
 *
 * Lifecycle mirrors [LockscreenIslandService] — start on user
 * opt-in, stop when the user disconnects glasses or backgrounds
 * for a long time. While stopped, the DAT facade still receives
 * `publishEvent` calls but no rendering happens.
 */
@AndroidEntryPoint
class GlassesConnectionService : Service() {

    @Inject lateinit var router: GlassesGestureRouter
    @Inject lateinit var screenStore: GlassesScreenStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // One-shot bookkeeping for "things that only need to happen once
    // per service-process lifetime" (router subscription, render-loop
    // collector). startSession() itself is fired on EVERY onStartCommand
    // — without that, a failed first attempt would leave the FGS sitting
    // idle and every subsequent "start session" tap would silently
    // re-deliver the intent into an already-alive service that thought
    // it was done.
    @Volatile private var oneShotsBound = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (!oneShotsBound) {
            oneShotsBound = true
            router.start()
            // Re-render to glasses on every screen-store change.
            scope.launch {
                screenStore.current.collect { screen ->
                    runCatching { GlassesDatFacade.render(screen) }
                        .onFailure { Log.w(TAG, "render failed: ${it.message}") }
                }
            }
        }
        // Always attempt a fresh session per intent — if the previous
        // attempt failed (DAT_APP_REQUIRED, NO_ELIGIBLE_DEVICE, etc),
        // re-tapping start session in the panel needs to actually retry.
        scope.launch {
            val ok = runCatching { GlassesDatFacade.startSession() }
                .getOrElse {
                    Log.w(TAG, "startSession threw: ${it.message}")
                    false
                }
            if (!ok) {
                // Session didn't open. Tear the FGS down so the next
                // "start session" tap gets a fresh service instance
                // with no stale `started`-style guard in the way.
                Log.d(TAG, "startSession returned false — stopping FGS so retry can re-deliver")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { GlassesDatFacade.stopSession() }
        scope.cancel()
    }

    private fun startForegroundCompat() {
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Mythara · glasses connected")
            .setContentText("Listening for neural-band gestures + glasses photos")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(tap)
            .build()
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, fgsType)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Mythara glasses",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Foreground service for the Meta Display Glasses session"
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val TAG = "Mythara/GlassesFGS"
        private const val NOTIF_ID = 9182
        private const val CHANNEL_ID = "mythara.glasses.fgs"

        fun start(ctx: Context) {
            val i = Intent(ctx, GlassesConnectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, GlassesConnectionService::class.java))
        }
    }
}
