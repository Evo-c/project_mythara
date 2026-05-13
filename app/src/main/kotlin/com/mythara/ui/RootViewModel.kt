package com.mythara.ui

import androidx.lifecycle.ViewModel
import com.mythara.wake.LumiListenerStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject

/**
 * Thin HiltViewModel that exposes app-wide event streams to MytharaRoot.
 * Today only the Lumi wake-query flow lives here; future bus-style
 * signals (push notification taps, intent-extra hooks) can ride along
 * without bloating AuthViewModel.
 *
 * Composables consume via `hiltViewModel<RootViewModel>()`. Hilt scopes
 * this to the Activity, which is exactly the lifetime we want for the
 * single MytharaRoot composition.
 */
@HiltViewModel
class RootViewModel @Inject constructor(
    store: LumiListenerStore,
) : ViewModel() {
    val wakeQueries: SharedFlow<LumiListenerStore.WakeQuery> = store.wakeQueries
}
