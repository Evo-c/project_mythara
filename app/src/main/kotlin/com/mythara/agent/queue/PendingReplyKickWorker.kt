package com.mythara.agent.queue

import android.content.Context
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
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Periodic safety net for [PendingReplyQueue]. The in-process drain
 * picks up new enqueues immediately, but the process can be killed
 * between "row inserted as PENDING" and "row processed" — at which
 * point the next process launch needs to notice the PENDING row
 * and start draining.
 *
 * Most of the time [PendingReplyQueue.start] (called from
 * MytharaApp.onCreate) does the recovery itself. But MytharaApp.onCreate
 * only fires when something *causes* the process to start —
 * NotificationListener binding, BootReceiver, or the user opening
 * the app. If none of those happen for a while (phone idle), backed-up
 * rows sit untouched.
 *
 * This worker fires every ~30 min on a battery-not-low + connected
 * constraint, brings the process up (WorkManager spawns it as needed),
 * and asks the queue to drain. Idempotent — when the queue is already
 * empty / draining, kick() is a no-op.
 *
 * Note: not run as part of any aggressive cadence on top of the
 * in-process drain. The point is to recover from edge cases, not to
 * replace the always-on path.
 */
@HiltWorker
class PendingReplyKickWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val queue: PendingReplyQueue,
    private val repo: PendingReplyRepository,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            // Just having Hilt construct this worker has already
            // instantiated PendingReplyQueue (Singleton), and its
            // start() ran from MytharaApp.onCreate moments ago. So
            // by the time we get here, requeueStuck has already
            // recovered any orphaned IN_FLIGHT rows. We just have
            // to tickle the drain.
            val n = repo.dao.pendingCount()
            if (n > 0) {
                Log.d(TAG, "kick worker found $n pending row(s) — draining")
                queue.kick()
            } else {
                Log.d(TAG, "kick worker: queue empty, nothing to do")
            }
            Result.success()
        }.getOrElse { e ->
            Log.w(TAG, "kick worker failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "Mythara/ReplyKick"
        const val UNIQUE_NAME = "mythara_reply_queue_kick"
    }
}

/**
 * Registers [PendingReplyKickWorker] with WorkManager. Called from
 * [com.mythara.MytharaApp.onCreate] right after the queue's own
 * in-process start — UPDATE policy so we can change the cadence
 * (or constraints) over time without dropping the schedule.
 */
@Singleton
class PendingReplyKickScheduler @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val wm: WorkManager get() = WorkManager.getInstance(ctx)

    fun start() {
        val req = PeriodicWorkRequestBuilder<PendingReplyKickWorker>(Duration.ofMinutes(30))
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    // Most queue rows need MiniMax to actually complete, so
                    // requiring CONNECTED here means we don't wake the process
                    // just to mark rows as still-pending when the network is
                    // gone. The in-process drain still runs on its own
                    // schedule when the network comes back.
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInitialDelay(Duration.ofMinutes(5))
            .build()
        wm.enqueueUniquePeriodicWork(
            PendingReplyKickWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            req,
        )
    }
}
