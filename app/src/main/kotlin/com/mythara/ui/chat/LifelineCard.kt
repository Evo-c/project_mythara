package com.mythara.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One photo entry in the life-timeline. Renders the image (lazy-decoded
 * from the MediaStore URI on a background dispatcher), the caption,
 * and a small footer with the date + device.
 *
 * Two render modes:
 *  - Local (entry.isLocal = true): full image, decoded from the URI.
 *  - Remote (entry.isLocal = false): the bytes aren't on this device,
 *    so we render a "📷 from <device>" placeholder card with just
 *    the caption + date. The user can fetch the original by walking
 *    over to that device.
 */
@Composable
fun LifelineCard(item: ChatViewModel.ChatItem.LifelinePhoto) {
    val ctx = LocalContext.current
    val dateLabel = remember(item.takenMs) { formatDate(item.takenMs) }
    val deviceLabel = if (item.isLocal) "this device" else "📷 ${item.deviceShortId}"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(12.dp)),
    ) {
        if (item.isLocal && item.uri.isNotBlank()) {
            LocalImage(uri = Uri.parse(item.uri), aspectRatio = aspectRatioOf(item.width, item.height))
        } else {
            // Remote placeholder — no bytes on this device.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspectRatioOf(item.width, item.height))
                    .background(MytharaColors.SurfaceMid),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${Glyph.DiamondOutline} photo on ${item.deviceShortId}\n(not on this device)",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (!item.captionText.isNullOrBlank()) {
                Text(
                    text = item.captionText,
                    color = MytharaColors.Fg,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else if (item.captionStatus == "PENDING") {
                Text(
                    text = "${Glyph.Ellipsis} captioning…",
                    color = MytharaColors.Citron,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (item.captionStatus == "FAILED" || item.captionStatus == "SKIPPED") {
                Text(
                    text = "${Glyph.Cross} no caption",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = dateLabel,
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "│",
                    color = MytharaColors.SurfaceHigh,
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = deviceLabel,
                    color = MytharaColors.Mustard,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                )
                if (item.placeLabel != null) {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = "│",
                        color = MytharaColors.SurfaceHigh,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = item.placeLabel,
                        color = MytharaColors.FgDim,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalImage(uri: Uri, aspectRatio: Float) {
    val ctx = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }

    LaunchedEffect(uri) {
        val loaded = withContext(Dispatchers.IO) { decodeBitmap(ctx, uri) }
        if (loaded != null) bitmap = loaded.asImageBitmap() else failed = true
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .background(MytharaColors.SurfaceMid),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> {
                Image(
                    bitmap = bitmap!!,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().aspectRatio(aspectRatio),
                    contentScale = ContentScale.Crop,
                )
            }
            failed -> {
                Text(
                    text = "${Glyph.Cross} couldn't load",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            else -> {
                Text(
                    text = "${Glyph.Ellipsis} loading…",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun aspectRatioOf(w: Int, h: Int): Float {
    if (w <= 0 || h <= 0) return 1.5f
    return (w.toFloat() / h.toFloat()).coerceIn(0.5f, 3f)
}

private fun decodeBitmap(ctx: Context, uri: Uri): Bitmap? {
    // Downsample on decode so we don't load a 12MP photo into RAM for
    // a 300-dp-wide card. inSampleSize halves dimensions, so the
    // strategy is: probe bounds, pick sample so the loaded width is
    // ≤ ~1024px.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    }.onFailure { return null }
    val srcW = bounds.outWidth.takeIf { it > 0 } ?: return null
    var sample = 1
    while (srcW / sample > 1024) sample *= 2

    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrElse {
        Log.w("Mythara/Lifeline", "decode failed: ${it.message}")
        null
    }
}

private fun formatDate(ms: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - ms
    val sdf = if (diff < 24L * 3600 * 1000) {
        SimpleDateFormat("HH:mm", Locale.getDefault())
    } else if (diff < 7L * 24 * 3600 * 1000) {
        SimpleDateFormat("EEEE HH:mm", Locale.getDefault())
    } else {
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    }
    return sdf.format(Date(ms))
}
