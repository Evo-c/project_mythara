package com.mythara.lifeline

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.mythara.memory.DeviceIdStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scans the device's MediaStore for camera-captured photos and inserts
 * them into [LifelineRepository] as PENDING rows. New rows trigger a
 * caption pass via [LifelineCaptioner].
 *
 * "Camera-captured" is the practical filter — we want photos the user
 * actually TOOK, not screenshots / downloads / app caches. Two signals:
 *
 *  1. BUCKET_DISPLAY_NAME in our [CAMERA_BUCKETS] allowlist (Camera,
 *     DCIM, Photos, …) — matches what Pixel + Samsung + OnePlus + the
 *     stock Android camera apps surface.
 *  2. RELATIVE_PATH starts with "DCIM/" — backstop for OEM cameras
 *     that store under odd bucket names.
 *
 * Excluded by design:
 *  - Screenshots (BUCKET = "Screenshots" / "Pictures/Screenshots")
 *  - WhatsApp/Telegram/etc auto-saves (handled by NotificationImageIngestor)
 *  - Downloads, app caches, sharing intent dumps
 *
 * Scan strategy:
 *  - First scan: pull every photo from MediaStore newer than 30 days
 *    so the timeline isn't empty on day one (caps the import burst).
 *  - Subsequent scans: since [LifelineDao.lastScannedAddedMs] —
 *    incremental, fast.
 *  - Triggered by [com.mythara.lifeline.MediaStoreObserver] on every
 *    MediaStore content change (~immediate after a photo is taken),
 *    AND by [LifelineNightlyWorker] as a safety net.
 */
@Singleton
class PhotoScanner @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val repo: LifelineRepository,
    private val deviceIdStore: DeviceIdStore,
) {
    suspend fun scan(): ScanResult = withContext(Dispatchers.IO) {
        if (!hasPermission()) {
            return@withContext ScanResult(scanned = 0, inserted = 0, skipped = "no READ_MEDIA_IMAGES")
        }
        val myId = runCatching { deviceIdStore.id() }.getOrElse { "unknown-device" }
        val lastAddedMs = runCatching { repo.dao.lastScannedAddedMs() }.getOrDefault(null)
        val isFirstScan = lastAddedMs == null
        // MediaStore stores DATE_ADDED in seconds since epoch. Convert.
        val sinceSec = if (lastAddedMs != null) {
            (lastAddedMs / 1000L).coerceAtLeast(0L)
        } else {
            // First scan — pull the last 30 days so the timeline isn't
            // empty on install. We still INSERT every photo so the user
            // sees their recent shots in the timeline, but we mark
            // pre-install photos as SKIPPED so the captioner doesn't
            // burn through hundreds of Gemini calls on day one. The
            // user can opt to re-caption these later from Settings.
            ((System.currentTimeMillis() - FIRST_SCAN_WINDOW_MS) / 1000L).coerceAtLeast(0L)
        }
        val nowMs = System.currentTimeMillis()
        val rows = queryCameraPhotos(sinceSec)
        var inserted = 0
        var skippedFromBackfill = 0
        for (row in rows) {
            // First-scan rows that were ALREADY in the camera roll
            // before this install are marked SKIPPED. The cutoff is
            // "5 minutes before now" — anything older was on disk
            // before we started, anything newer was almost certainly
            // taken just now (e.g. the user installed and immediately
            // snapped a photo). The 5-min slop accounts for permission
            // grant + first-scan latency.
            val isBackfill = isFirstScan && row.addedMs < nowMs - BACKFILL_SLOP_MS
            val status = if (isBackfill) {
                skippedFromBackfill++
                LifelineCaptionStatus.SKIPPED.name
            } else {
                LifelineCaptionStatus.PENDING.name
            }
            val entity = LifelineEntity(
                deviceId = myId,
                mediaStoreId = row.id,
                uri = row.uri.toString(),
                displayName = row.displayName,
                bucket = row.bucket,
                takenMs = row.takenMs,
                addedMs = row.addedMs,
                mimeType = row.mimeType,
                width = row.width,
                height = row.height,
                sizeBytes = row.sizeBytes,
                lat = row.lat,
                lng = row.lng,
                captionStatus = status,
                captionText = if (isBackfill) "(captioning skipped — pre-install photo)" else null,
            )
            val id = runCatching { repo.dao.insertIfAbsent(entity) }.getOrDefault(-1L)
            if (id > 0L) inserted++
        }
        Log.d(
            TAG,
            "scan: ${rows.size} photo(s) since ${sinceSec}s, $inserted new" +
                if (skippedFromBackfill > 0) " ($skippedFromBackfill SKIPPED as backfill)" else "",
        )
        // Tombstone pass — find LOCAL rows whose MediaStore id no
        // longer exists in the gallery (user deleted the photo).
        // Marking them is_deleted=true makes them vanish from the
        // local timeline AND ships the tombstone to other devices
        // on the next memory sync. The full-roll query on first
        // launch is OK; subsequent runs are bounded by the table
        // size, not the user's full camera roll.
        val tombstoned = runCatching { tombstoneDeleted(myId) }.getOrDefault(0)
        if (tombstoned > 0) {
            Log.d(TAG, "scan: tombstoned $tombstoned deleted photo(s)")
        }
        ScanResult(scanned = rows.size, inserted = inserted, skipped = null)
    }

    /**
     * For every LOCAL lifeline row we have, check whether its MediaStore
     * id still resolves. If not, the photo was deleted from the gallery
     * — tombstone the row so it vanishes from the timeline and the
     * deletion syncs to peers.
     *
     * Single bulk query: get every live MediaStore image id this scan
     * sees, then diff against our local row set. O(N) over the gallery
     * + O(M) over our rows.
     */
    private suspend fun tombstoneDeleted(myDeviceId: String): Int {
        val liveIds = collectAllLiveMediaStoreIds()
        if (liveIds.isEmpty()) return 0 // safety — never tombstone the entire roll
        val locals = repo.dao.listLocalLive(myDeviceId)
        val now = System.currentTimeMillis()
        var n = 0
        for (row in locals) {
            if (row.mediaStoreId !in liveIds) {
                repo.dao.markDeleted(row.id, now)
                n++
            }
        }
        return n
    }

    /**
     * Lightweight projection — just every _ID currently in
     * EXTERNAL_CONTENT_URI (no bucket filter). We pull the full set
     * because a photo move (DCIM/Camera → DCIM/SomeAlbum) would
     * otherwise look like a deletion from the camera bucket
     * specifically.
     */
    private fun collectAllLiveMediaStoreIds(): Set<Long> {
        val out = HashSet<Long>()
        val proj = arrayOf(MediaStore.Images.Media._ID)
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null, null,
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (c.moveToNext()) out.add(c.getLong(idIdx))
        }
        return out
    }

    private fun queryCameraPhotos(sinceSec: Long): List<RawPhoto> {
        val pm = ctx.contentResolver
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.LATITUDE,
            MediaStore.Images.Media.LONGITUDE,
            MediaStore.Images.Media.RELATIVE_PATH,
        )
        val sel = "${MediaStore.Images.Media.DATE_ADDED} > ?"
        val args = arrayOf(sinceSec.toString())
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        val out = mutableListOf<RawPhoto>()
        pm.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, sel, args, sort)?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val bucketIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val nameIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val addedIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val takenIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val mimeIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val wIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val hIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val sizeIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val latIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.LATITUDE)
            val lngIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.LONGITUDE)
            val pathIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            while (c.moveToNext()) {
                val bucket = c.getString(bucketIdx).orEmpty()
                val relPath = c.getString(pathIdx).orEmpty()
                if (!isCameraSource(bucket, relPath)) continue
                val id = c.getLong(idIdx)
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                val takenMs = c.getLong(takenIdx)
                    .takeIf { it > 0L }
                    ?: (c.getLong(addedIdx) * 1000L) // fall back to date_added in ms
                val lat = c.getDouble(latIdx).takeIf { it != 0.0 }
                val lng = c.getDouble(lngIdx).takeIf { it != 0.0 }
                out.add(
                    RawPhoto(
                        id = id,
                        uri = uri,
                        bucket = bucket,
                        displayName = c.getString(nameIdx).orEmpty(),
                        addedMs = c.getLong(addedIdx) * 1000L,
                        takenMs = takenMs,
                        mimeType = c.getString(mimeIdx).orEmpty(),
                        width = c.getInt(wIdx),
                        height = c.getInt(hIdx),
                        sizeBytes = c.getLong(sizeIdx),
                        lat = lat,
                        lng = lng,
                    ),
                )
            }
        }
        return out
    }

    private fun isCameraSource(bucket: String, relPath: String): Boolean {
        if (bucket.isNotBlank() && bucket in CAMERA_BUCKETS) return true
        if (relPath.startsWith("DCIM/", ignoreCase = true)) return true
        // Defensive — explicit denylist of common non-camera buckets.
        if (bucket.equals("Screenshots", ignoreCase = true)) return false
        if (relPath.contains("Screenshots", ignoreCase = true)) return false
        return false
    }

    private fun hasPermission(): Boolean {
        val perm = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
    }

    private data class RawPhoto(
        val id: Long,
        val uri: Uri,
        val bucket: String,
        val displayName: String,
        val addedMs: Long,
        val takenMs: Long,
        val mimeType: String,
        val width: Int,
        val height: Int,
        val sizeBytes: Long,
        val lat: Double?,
        val lng: Double?,
    )

    data class ScanResult(val scanned: Int, val inserted: Int, val skipped: String?)

    companion object {
        private const val TAG = "Mythara/PhotoScan"
        private const val FIRST_SCAN_WINDOW_MS = 30L * 24L * 60L * 60L * 1000L

        /** Photos older than (now - this) on the first scan are marked
         *  SKIPPED so the captioner doesn't backfill the user's
         *  entire pre-install camera roll. */
        private const val BACKFILL_SLOP_MS = 5L * 60_000L

        /**
         * Buckets that almost always carry camera-captured photos.
         * "Camera" is Pixel + most OEMs; "DCIM" / "100ANDRO" are
         * fallbacks; "Photos" appears on some Samsung builds.
         */
        private val CAMERA_BUCKETS = setOf(
            "Camera", "DCIM", "100ANDRO", "100MEDIA", "Photos",
        )
    }
}
