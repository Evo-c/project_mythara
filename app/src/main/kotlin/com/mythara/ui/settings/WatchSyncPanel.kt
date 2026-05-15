package com.mythara.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import com.mythara.wear.WatchSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WatchSyncViewModel @Inject constructor(
    private val sync: WatchSyncManager,
) : ViewModel() {
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    sealed interface State {
        data object Idle : State
        data object Syncing : State
        data class Done(val tsMs: Long) : State
    }

    fun syncNow() {
        if (_state.value is State.Syncing) return
        viewModelScope.launch {
            _state.value = State.Syncing
            runCatching { sync.syncNow() }
            _state.value = State.Done(System.currentTimeMillis())
        }
    }
}

/**
 * Manual "sync to watch now" panel. Forces an immediate push of
 * every watch surface (insight line, cluster data, phone status)
 * — same operation the periodic 15-min WatchSyncWorker runs in
 * the background, exposed here so the user can refresh the wrist
 * on demand without waiting for the next tick.
 */
@Composable
fun WatchSyncPanel(vm: WatchSyncViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    val syncing = state is WatchSyncViewModel.State.Syncing
    val lastDoneMs = (state as? WatchSyncViewModel.State.Done)?.tsMs

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text(
            text = "${Glyph.DiamondOutline} watch sync",
            style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${Glyph.AccentBar} pushes the next-scheduled-task line, complications, and cluster data (tasks, people, calendar, audit) to every paired watch. Runs automatically every 15 minutes via WorkManager; tap below to refresh on demand.",
            style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.FgDim),
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = { vm.syncNow() },
                enabled = !syncing,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MytharaColors.Charple,
                    contentColor = MytharaColors.Fg,
                ),
            ) {
                Text(
                    text = if (syncing) "${Glyph.Refresh} syncing…" else "${Glyph.Refresh} sync to watch now",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (lastDoneMs != null) {
                Text(
                    text = relativeAgo(lastDoneMs),
                    color = MytharaColors.Bok,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun relativeAgo(tsMs: Long): String {
    val ageMs = (System.currentTimeMillis() - tsMs).coerceAtLeast(0)
    val s = ageMs / 1000
    val m = s / 60
    return when {
        s < 5 -> "synced just now"
        s < 60 -> "synced ${s}s ago"
        m < 60 -> "synced ${m}m ago"
        else -> "synced (≥1h ago)"
    }
}
