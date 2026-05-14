package com.mythara.ui.lifeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.lifeline.LifelineEntity
import com.mythara.lifeline.LifelineRepository
import com.mythara.memory.DeviceIdStore
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Browses the user's complete photo timeline as a launcher-style
 * grid. Each cell is a thumbnail; tap to see the caption + open
 * the underlying image. Month headers float between row clusters so
 * the user can scroll back through time.
 *
 * Two host wrappers — same as [com.mythara.ui.launcher.AppDrawerSheet]:
 *
 *   [TimelineGridSheet]  — compact phones; ModalBottomSheet wrapper.
 *   [TimelineGridPane]   — two-pane foldable; renders inline in the
 *                          right pane like Settings / People.
 *
 * The grid loads up to [LIMIT] rows from [LifelineRepository] and
 * groups by year-month. Deleted rows are filtered out at the DAO
 * (see [LifelineDao.observeRecent]). Remote-device entries appear
 * as a tinted "📷 from <device>" placeholder — same convention as
 * the chat scrollback's LifelineCard.
 */

private const val LIMIT = 600

@HiltViewModel
class TimelineGridViewModel @Inject constructor(
    private val repo: LifelineRepository,
    private val deviceIdStore: DeviceIdStore,
) : ViewModel() {

    @Volatile var localDeviceId: String? = null
        private set

    val entries: StateFlow<List<LifelineEntity>> = repo.dao.observeRecent(LIMIT)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    init {
        viewModelScope.launch {
            localDeviceId = runCatching { deviceIdStore.id() }.getOrNull()
        }
    }

    fun setQuery(q: String) {
        _query.value = q
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineGridSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MytharaColors.Bg,
    ) {
        TimelineGridBody()
    }
}

@Composable
fun TimelineGridPane(onClose: () -> Unit) {
    TimelineGridBody()
}

@Composable
private fun TimelineGridBody(vm: TimelineGridViewModel = hiltViewModel()) {
    val entries by vm.entries.collectAsState()
    val query by vm.query.collectAsState()

    // Filter + group by month (most recent first). One header cell per
    // month so the grid reads as a timeline you can scroll through.
    val filtered = remember(entries, query) {
        val q = query.trim().lowercase()
        val list = if (q.isEmpty()) entries else entries.filter {
            (it.captionText?.lowercase()?.contains(q) ?: false) ||
                it.deviceId.lowercase().contains(q)
        }
        list.sortedByDescending { it.takenMs }
    }
    val grouped = remember(filtered) { groupByMonth(filtered) }

    Box(modifier = Modifier.fillMaxSize().background(MytharaColors.Bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 14.dp),
        ) {
            Text(
                text = "${Glyph.DiamondFilled} timeline",
                color = MytharaColors.Bok,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${entries.size} photos in your life timeline${if (filtered.size != entries.size) " · ${filtered.size} match" else ""}",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))

            if (grouped.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${Glyph.CircleOutline} no photos yet — take one with the camera",
                        color = MytharaColors.FgDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 96.dp),
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for ((monthLabel, photos) in grouped) {
                        item(key = "h:$monthLabel", span = { GridItemSpan(maxLineSpan) }) {
                            MonthHeader(monthLabel, photos.size)
                        }
                        for (p in photos) {
                            item(key = "p:${p.deviceId}:${p.mediaStoreId}") {
                                TimelineCell(p, isLocal = (p.deviceId == vm.localDeviceId) && !p.isRemote)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthHeader(label: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = MytharaColors.Fg,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        )
        Spacer(Modifier.padding(end = 8.dp))
        Text(
            text = "$count photo${if (count == 1) "" else "s"}",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun TimelineCell(entry: LifelineEntity, isLocal: Boolean) {
    val ctx = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(8.dp))
            .clickable { /* future: open expanded view */ },
    ) {
        if (isLocal && entry.uri.isNotBlank()) {
            LocalThumb(uri = Uri.parse(entry.uri))
        } else {
            // Remote — no bytes here; tint the cell + show device tag.
            Box(
                modifier = Modifier.fillMaxSize().background(MytharaColors.SurfaceMid),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "📷\n${entry.deviceId.takeLast(6)}",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
        // Caption overlay at the bottom — first ~30 chars, dimmed bar.
        if (!entry.captionText.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(MytharaColors.Bg.copy(alpha = 0.78f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                Text(
                    text = entry.captionText,
                    color = MytharaColors.Fg,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun LocalThumb(uri: Uri) {
    val ctx = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        val loaded = withContext(Dispatchers.IO) { decodeThumb(ctx, uri) }
        if (loaded != null) bitmap = loaded.asImageBitmap()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}

private fun decodeThumb(ctx: Context, uri: Uri): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    }.onFailure { return null }
    val srcW = bounds.outWidth.takeIf { it > 0 } ?: return null
    var sample = 1
    // 192-px thumb at 1x is plenty for a 96-dp grid cell.
    while (srcW / sample > 256) sample *= 2
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrElse {
        Log.w("Mythara/Timeline", "thumb decode failed: ${it.message}")
        null
    }
}

private fun groupByMonth(entries: List<LifelineEntity>): List<Pair<String, List<LifelineEntity>>> {
    val sdf = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    val map = linkedMapOf<String, MutableList<LifelineEntity>>()
    for (e in entries) {
        val label = sdf.format(Date(e.takenMs))
        map.getOrPut(label) { mutableListOf() }.add(e)
    }
    return map.entries.map { it.key to it.value.toList() }
}
