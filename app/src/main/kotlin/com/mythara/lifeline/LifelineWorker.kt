package com.mythara.lifeline

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nightly safety net for [PhotoScanner] + [LifelineCaptioner]. The
 * [MediaStoreObserver] catches photos in real time; this worker covers
 * the gaps:
 *  - Photos taken while the app process was killed and no observer
 *    was registered
 *  - PENDING rows whose caption call failed earlier (network blip, key
 *    cycling) and need retry
 *
 * Fires every 12 hours on a charging + Wi-Fi constraint so the user
 * never pays for captioning over mobile data.
 */
@HiltWorker
class LifelineWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val scanner: PhotoScanner,
    private val captioner: LifelineCaptioner,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            val scan = scanner.scan()
            Log.d(TAG, "scan: ${scan.scanned} photos found, ${scan.inserted} new")
            val captioned = captioner.captionPending(maxRows = MAX_CAPTIONS_PER_RUN)
            Log.d(TAG, "captionPending: $captioned captioned this run")
            Result.success()
        }.getOrElse { e ->
            Log.w(TAG, "lifeline worker failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "Mythara/LifelineWk"
        const val UNIQUE_NAME = "mythara_lifeline_periodic"

        /** Cap so a backlog doesn't blow the user's vision-token bill in one run. */
        private const val MAX_CAPTIONS_PER_RUN = 30
    }
}

/**
 * Schedules [LifelineWorker]. Called from MytharaApp.onCreate. Idempotent
 * (UPDATE policy on the unique periodic work).
 */
@Singleton
class LifelineScheduler @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val wm: WorkManager get() = WorkManager.getInstance(ctx)

    fun start() {
        val req = PeriodicWorkRequestBuilder<LifelineWorker>(Duration.ofHours(12))
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build(),
            )
            .setInitialDelay(Duration.ofMinutes(15))
            .build()
        wm.enqueueUniquePeriodicWork(
            LifelineWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            req,
        )
    }
}

/**
 * Live MediaStore watcher. Registers a [ContentObserver] on the
 * external images collection at app boot; on every change, debounces
 * (~3s) and then runs scan + captionPending so newly-taken photos
 * appear in the chat scrollback within seconds.
 *
 * Debouncing matters: the system fires onChange a couple of times in
 * quick succession when a photo is saved (file create + EXIF write +
 * MediaStore index). One scan is enough, and the debounce keeps
 * Gemini calls from racing against each other.
 *
 * The observer is held by the process — when MytharaApp is alive
 * (which is most of the time thanks to AgentForegroundService +
 * the AutoReply path) photos are captioned within seconds. When the
 * process is killed, the nightly [LifelineWorker] catches up.
 */
@Singleton
class MediaStoreObserver @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val scanner: PhotoScanner,
    private val captioner: LifelineCaptioner,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var debounceJob: Job? = null
    @Volatile private var started = false

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            scheduleScan()
        }
        override fun onChange(selfChange: Boolean) {
            scheduleScan()
        }
    }

    fun start() {
        if (started) return
        started = true
        runCatching {
            ctx.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                /* notifyForDescendants = */ true,
                observer,
            )
        }.onFailure { Log.w(TAG, "registerContentObserver failed: ${it.message}") }
        Log.d(TAG, "MediaStoreObserver started")
        // Run an initial scan on boot so any photos taken while the
        // process was dead get caught up immediately rather than
        // waiting for the next nightly worker fire.
        scheduleScan()
    }

    private fun scheduleScan() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            val scan = runCatching { scanner.scan() }.getOrNull() ?: return@launch
            if (scan.inserted > 0) {
                runCatching { captioner.captionPending(maxRows = MAX_CAPTIONS_PER_BURST) }
            }
        }
    }

    companion object {
        private const val TAG = "Mythara/MediaObs"
        private const val DEBOUNCE_MS = 3_000L

        /**
         * Caps caption calls per observer-triggered burst so a "shot
         * 50 photos in 30 seconds at the kid's soccer game" pattern
         * spreads its token cost across the nightly worker rather
         * than draining the user's quota in one go.
         */
        private const val MAX_CAPTIONS_PER_BURST = 5
    }
}
