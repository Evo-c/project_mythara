package com.mythara.services

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * ADB-triggerable wallpaper applier. Lets the operator drop a Mythara
 * branding image onto the device's home + lockscreen with one shell
 * command (no third-party gallery / picker dance):
 *
 *   adb shell am broadcast \
 *     -a com.mythara.action.APPLY_WALLPAPER \
 *     -n com.mythara.debug/com.mythara.services.WallpaperApplyReceiver \
 *     --es path /sdcard/Pictures/mythara_wallpaper.png \
 *     --es target both
 *
 * `path` may be a filesystem path OR a content:// URI. `target` is one
 * of `home`, `lock`, `both` (default `both`). Decoded via
 * [BitmapFactory], handed to [WallpaperManager.setBitmap] with the
 * matching `which` flag(s). Errors are logged; the receiver never
 * crashes the host process. Exported so adb can reach it without the
 * app being in foreground.
 *
 * The application holds [android.permission.SET_WALLPAPER] (declared in
 * the manifest); no runtime grant required.
 */
class WallpaperApplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) {
            Log.w(TAG, "ignoring unknown action ${intent.action}")
            return
        }
        val pathArg = intent.getStringExtra(EXTRA_PATH)
        if (pathArg.isNullOrBlank()) {
            Log.w(TAG, "missing required extra '$EXTRA_PATH'")
            return
        }
        val targetArg = (intent.getStringExtra(EXTRA_TARGET) ?: "both").lowercase()
        val whichFlags = when (targetArg) {
            "home" -> WallpaperManager.FLAG_SYSTEM
            "lock" -> WallpaperManager.FLAG_LOCK
            "both" -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            else -> {
                Log.w(TAG, "unknown target '$targetArg'; defaulting to both")
                WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            }
        }

        val bitmap = runCatching {
            // Accept either content:// URIs or raw filesystem paths.
            // Filesystem paths are the common case for adb-pushed PNGs.
            if (pathArg.startsWith("content://") || pathArg.startsWith("file://")) {
                val uri = Uri.parse(pathArg)
                context.contentResolver.openInputStream(uri).use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            } else {
                val f = File(pathArg)
                if (!f.exists()) {
                    Log.e(TAG, "file does not exist: $pathArg")
                    return
                }
                BitmapFactory.decodeFile(f.absolutePath)
            }
        }.getOrElse {
            Log.e(TAG, "failed to decode bitmap from '$pathArg': ${it.message}", it)
            return
        }

        if (bitmap == null) {
            Log.e(TAG, "BitmapFactory returned null for '$pathArg'")
            return
        }

        runCatching {
            val wm = WallpaperManager.getInstance(context)
            // Pass `null` for visibleCropHint to let the system center-
            // crop, and `true` for `allowBackup` so it survives a backup-
            // restore. The `which` arg gates home / lock / both.
            wm.setBitmap(bitmap, null, true, whichFlags)
            Log.i(TAG, "wallpaper applied (${bitmap.width}x${bitmap.height}, target=$targetArg)")
        }.onFailure {
            Log.e(TAG, "WallpaperManager.setBitmap failed: ${it.message}", it)
        }
    }

    companion object {
        private const val TAG = "Mythara/WallpaperApply"
        const val ACTION = "com.mythara.action.APPLY_WALLPAPER"
        const val EXTRA_PATH = "path"
        const val EXTRA_TARGET = "target"
    }
}
