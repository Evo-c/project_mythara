package com.mythara.analytics

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide pulse that fires whenever something writes into the
 * contact / relationship graph in a way that would change what the
 * graph-view UI ([com.mythara.ui.insights.InsightsViewModel]) renders.
 *
 * Producers:
 *  - [com.mythara.ui.chat.ChatViewModel.saveLifelineNote] — after a
 *    user-typed photo note runs through [com.mythara.memory.graph.GraphTurnExtractor]
 *    (which may have created/updated contact rows from extracted names).
 *  - Future: any other path that mutates contact rows or graph edges
 *    (manual contact edits, glasses face-recognition autotag, etc.).
 *
 * Consumers:
 *  - [com.mythara.ui.insights.InsightsViewModel] collects this flow
 *    and kicks `refresh()` so the on-screen relationship graph updates
 *    without waiting for the user to navigate away + back.
 *
 * Backpressure: `DROP_OLDEST` with a buffer of 1 so a burst of edits
 * coalesces into a single rebuild (collectLatest on the UI side does
 * the same conceptually — both sides cooperate so we don't run the
 * builder N times for N tiny writes within a single tick).
 *
 * No payload — consumers just re-query the source-of-truth (Room) on
 * each pulse. Keeps the contract trivial; the "what changed" detail
 * lives in the database, not in this signal.
 */
@Singleton
class GraphChangeNotifier @Inject constructor() {
    private val _changes = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    /** Emit a "graph might have changed" pulse. Safe to call from any
     *  thread (MutableSharedFlow.tryEmit is non-suspending). Callers
     *  should fire AFTER the underlying writes commit so the consumer
     *  reads fresh state when it queries Room. */
    fun notifyChanged() {
        _changes.tryEmit(Unit)
    }
}
