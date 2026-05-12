package com.mythara.growth

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mythara.growth.LearningJournal.Entry
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Hilt-injected CoroutineWorker that runs the nightly/weekly growth
 * jobs. M8.0 is intentionally minimal — appends a journal entry so we
 * can validate the WorkManager cadence on the device.
 *
 * When M8.1+ lands this body fans out into:
 *   1. RawDataPurger.sweep()
 *   2. SelfOrganizer.compactDeltaSinceLastRun()
 *   3. CapabilityTuner.recomputeSchema()
 *   4. GoalGenerator → GrowthAgent.run()
 *
 * See docs/SELF_ORGANIZING_LEARNING.md for the full architecture.
 */
@HiltWorker
class GrowthWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val journal: LearningJournal,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val kind = inputData.getString(KEY_KIND) ?: "manual"
        return runCatching {
            journal.append(
                Entry(
                    tsMillis = System.currentTimeMillis(),
                    kind = kind,
                    note = "growth job fired (scaffold — real learning lands with M8.1)",
                ),
            )
            // Insert M8.1+ pipeline here.
            Result.success()
        }.getOrElse { e ->
            journal.append(
                Entry(
                    tsMillis = System.currentTimeMillis(),
                    kind = "${kind}_failed",
                    note = "error: ${e.message ?: e.javaClass.simpleName}",
                ),
            )
            Result.retry()
        }
    }

    companion object {
        const val KEY_KIND = "kind"
        const val UNIQUE_NIGHTLY = "mythara_growth_nightly"
        const val UNIQUE_WEEKLY = "mythara_growth_weekly"
    }
}
