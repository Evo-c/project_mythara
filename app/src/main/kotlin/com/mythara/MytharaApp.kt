package com.mythara

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.mythara.growth.GrowthScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry. Hosts the Hilt SingletonComponent, the WorkManager
 * configuration (so HiltWorker-annotated workers get their dependencies),
 * and the GrowthScheduler bootstrap that registers the nightly + weekly
 * self-learning cadences on first launch.
 *
 * The scheduler is idempotent — calling start() on every boot is safe
 * (UPDATE policy on unique periodic work). No need for a separate
 * BootReceiver: WorkManager survives reboots on its own.
 */
@HiltAndroidApp
class MytharaApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var growthScheduler: GrowthScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        growthScheduler.start()
    }
}
