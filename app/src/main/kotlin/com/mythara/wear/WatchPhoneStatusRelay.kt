package com.mythara.wear

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pushes the phone's battery level to the watch face.
 *
 * A WFF watch face can read the watch's own battery ([BATTERY_PERCENT])
 * but not the paired phone's — so the phone publishes its own battery
 * over the Wearable Data Layer on a slow timer. The watch caches it and
 * feeds [com.mythara.wear.complications.PhoneBatteryComplicationService],
 * which the Tactical face renders in its top stat band.
 */
@Singleton
class WatchPhoneStatusRelay @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        scope.launch {
            while (true) {
                pushBattery()
                delay(PUSH_INTERVAL_MS)
            }
        }
    }

    /** Force-push the current battery state. Called by the manual
     *  "sync to watch now" path + the periodic 15-min worker so the
     *  wrist gets fresh data without waiting for the in-process loop. */
    fun pushNow() = pushBattery()

    private fun pushBattery() {
        val pct = batteryPercent() ?: return
        val bytes = pct.toString().toByteArray(Charsets.UTF_8)
        val nodeClient = Wearable.getNodeClient(ctx)
        val msgClient = Wearable.getMessageClient(ctx)
        nodeClient.connectedNodes
            .addOnSuccessListener { nodes ->
                for (node in nodes) {
                    msgClient.sendMessage(node.id, PHONE_BATTERY_PATH, bytes)
                }
                if (nodes.isNotEmpty()) {
                    Log.d(TAG, "pushed phone battery $pct% to ${nodes.size} node(s)")
                }
            }
            .addOnFailureListener { e -> Log.w(TAG, "node list failed: ${e.message}") }
    }

    private fun batteryPercent(): Int? {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        val p = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (p in 0..100) p else null
    }

    companion object {
        private const val TAG = "Mythara/PhoneStatus"
        private const val PUSH_INTERVAL_MS = 2 * 60 * 1000L

        /** Keep in sync with the wear module's WearPaths.PHONE_BATTERY. */
        const val PHONE_BATTERY_PATH = "/mythara/phone_battery"
    }
}
