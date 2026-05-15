package com.mythara.wear

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single entry point for an immediate, full sync of every surface
 * the phone publishes to the watch:
 *  - the insight line (next scheduled task) via [WatchNextTaskRelay]
 *  - all cluster data (tasks, people, calendar, reminder, audit)
 *    via [WatchClusterDataPusher]
 *  - phone-battery snapshot via [WatchPhoneStatusRelay]
 *
 * Used by:
 *  - the **"sync to watch now"** button in Settings
 *  - the **15-min periodic [WatchSyncWorker]** that keeps the wrist
 *    fresh even when the user hasn't touched the app
 */
@Singleton
class WatchSyncManager @Inject constructor(
    private val nextTaskRelay: WatchNextTaskRelay,
    private val clusterPusher: WatchClusterDataPusher,
    private val phoneStatusRelay: WatchPhoneStatusRelay,
) {

    suspend fun syncNow() {
        Log.d(TAG, "syncNow → pushing all watch surfaces")
        runCatching { nextTaskRelay.pushNow() }
            .onFailure { Log.w(TAG, "next-task push failed: ${it.message}") }
        runCatching { clusterPusher.pushAllNow() }
            .onFailure { Log.w(TAG, "cluster push failed: ${it.message}") }
        runCatching { phoneStatusRelay.pushNow() }
            .onFailure { Log.w(TAG, "phone status push failed: ${it.message}") }
    }

    companion object { private const val TAG = "Mythara/WatchSync" }
}
