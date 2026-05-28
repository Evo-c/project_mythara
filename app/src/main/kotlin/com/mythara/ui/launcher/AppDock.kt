package com.mythara.ui.launcher

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.launcher.InstalledAppsProvider
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.exp

@HiltViewModel
class AppDockViewModel @Inject constructor(
    private val provider: InstalledAppsProvider,
) : ViewModel() {

    private val _apps = MutableStateFlow<List<InstalledAppsProvider.App>>(emptyList())
    val apps: StateFlow<List<InstalledAppsProvider.App>> = _apps.asStateFlow()

    // Lazy icon cache — per-package ImageBitmap once decoded.
    private val _icons = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val icons: StateFlow<Map<String, ImageBitmap>> = _icons.asStateFlow()

    init {
        viewModelScope.launch {
            val list = provider.list()
            _apps.value = list
            // Decode icons in PARALLEL so the dock fills in fast
            // instead of waiting on sequential PackageManager calls.
            // Each async fans out on Dispatchers.IO; we accumulate
            // results into a mutex-guarded map and snapshot the
            // StateFlow as each icon arrives → the UI updates
            // progressively rather than after the whole batch.
            val mutex = kotlinx.coroutines.sync.Mutex()
            val map = LinkedHashMap<String, ImageBitmap>(list.size)
            val jobs = list.map { app ->
                async(kotlinx.coroutines.Dispatchers.IO) {
                    val bm = provider.iconBitmap(app.packageName, sizePx = 144)
                    if (bm != null) {
                        mutex.withLock {
                            map[app.packageName] = bm
                            _icons.value = map.toMap()
                        }
                    }
                }
            }
            jobs.awaitAll()
        }
    }

    fun launch(packageName: String) {
        // The provider returns a launch intent; the actual launch goes
        // through the host (which holds Context). We just expose the
        // package; the @Composable invokes provider.launchIntent.
    }

    val provider_: InstalledAppsProvider get() = provider
}

/**
 * macOS-style magnifying app dock for the spine launcher panel.
 * Renders every installed launcher app as a circular icon in a
 * horizontal scroll row; while the user presses + drags across the
 * dock, icons near the touch point scale up via a Gaussian falloff
 * (classic fisheye). Tap an icon to launch its app.
 */
@Composable
fun AppDock(
    onLaunch: (packageName: String) -> Unit,
    vm: AppDockViewModel = hiltViewModel(),
) {
    val apps by vm.apps.collectAsState()
    val icons by vm.icons.collectAsState()
    val density = LocalDensity.current
    val ctx = LocalContext.current

    if (apps.isEmpty()) {
        Spacer(Modifier.height(BASE_DP.dp + 16.dp))
        return
    }

    // Pointer X in the dock's local coords (px). Null = no touch active
    // → all icons at base scale.
    var pointerX by remember { mutableStateOf<Float?>(null) }
    val basePx = with(density) { BASE_DP.dp.toPx() }
    val spacingPx = with(density) { SPACING_DP.dp.toPx() }
    val containerHeightPx = with(density) { (BASE_DP * MAX_SCALE + 24f).dp.toPx() }

    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(with(density) { containerHeightPx.toDp() })
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    pointerX = down.position.x + scrollState.value
                    var lastX = down.position.x
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) {
                            pointerX = null
                            break
                        }
                        pointerX = ch.position.x + scrollState.value
                        lastX = ch.position.x
                    }
                    @Suppress("UNUSED_VARIABLE") val _x = lastX
                }
            }
            .horizontalScroll(scrollState),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            apps.forEachIndexed { i, app ->
                // Local x-center of icon i in the dock's coordinate
                // system (Row left edge + accumulated widths + half).
                val centerX = 12f * density.density + // padding start in px
                    i * (basePx + spacingPx) + basePx * 0.5f
                val px = pointerX
                val scale = if (px == null) 1f else gaussianMagnify(distance = abs(px - centerX), maxScale = MAX_SCALE, sigmaPx = MAGNIFY_SIGMA_DP * density.density)

                DockIcon(
                    app = app,
                    icon = icons[app.packageName],
                    scale = scale,
                    onTap = {
                        // Launch via the provider's intent (avoids
                        // exposing Context in the VM).
                        val intent = vm.provider_.launchIntent(app.packageName)
                        if (intent != null) runCatching { ctx.startActivity(intent) }
                        onLaunch(app.packageName)
                    },
                )
                if (i != apps.lastIndex) Spacer(Modifier.width(SPACING_DP.dp))
            }
        }
    }
}

@Composable
private fun DockIcon(
    app: InstalledAppsProvider.App,
    icon: ImageBitmap?,
    scale: Float,
    onTap: () -> Unit,
) {
    val sizeDp = (BASE_DP * scale).dp
    Box(
        modifier = Modifier
            .size(sizeDp)
            .clip(RoundedCornerShape(14.dp))
            .background(MytharaColors.Surface.copy(alpha = 0.7f))
            .border(1.dp, MytharaColors.SurfaceHigh.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = app.label,
                modifier = Modifier.size(sizeDp * 0.75f),
            )
        } else {
            // Initial-letter fallback while the icon is decoding.
            Text(
                text = app.initial,
                color = MytharaColors.Charple,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}

/** Gaussian fisheye scaler. Distance 0 → maxScale. Distance ≥ ~3σ →
 *  back to 1.0. */
private fun gaussianMagnify(distance: Float, maxScale: Float, sigmaPx: Float): Float {
    val k = (distance / sigmaPx)
    val bell = exp(-(k * k) * 0.5f)
    return 1f + (maxScale - 1f) * bell
}

private const val BASE_DP = 44       // base icon size (dp)
private const val SPACING_DP = 8     // space between icons (dp)
private const val MAX_SCALE = 1.8f   // icon under the touch grows ~1.8×
private const val MAGNIFY_SIGMA_DP = 56f
