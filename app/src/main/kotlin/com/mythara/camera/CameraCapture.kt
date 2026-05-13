package com.mythara.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Headless one-shot photo capture for the M5 `take_photo` agent tool.
 *
 * Architecture:
 *  - Bound to [ProcessLifecycleOwner] so a Hilt singleton can fire
 *    captures from a coroutine without owning an Activity. The chat
 *    surface keeps painting while a photo is taken — no viewfinder,
 *    no preview, no UI overlay.
 *  - The [ProcessCameraProvider] is bound on first use and reused
 *    across calls. Re-binding on every call would burn ~200ms in
 *    camera-open overhead.
 *  - Capture writes the raw JPEG to a temp file, then the result is
 *    downscaled to ≤ [TARGET_LONG_EDGE] px on the JVM side and
 *    rewritten — this matches the MiniMax-VL-01 vision payload size
 *    guidance (≤1024 long-edge for cheapest token usage).
 *
 * Threading: capture callback fires on the main executor; the suspend
 * wrapper bridges to the calling coroutine. The post-capture downscale
 * runs on [Dispatchers.IO] so the main thread doesn't decode/recompress
 * a JPEG.
 *
 * Background note: ProcessLifecycleOwner is STARTED only when at least
 * one Mythara activity is foregrounded. If the user backgrounds the app
 * mid-call the capture will throw — caller maps that to a `not_ready`
 * tool error.
 */
@Singleton
class CameraCapture @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    /** Outcome of one capture attempt. Always returned — never thrown. */
    sealed class Result {
        data class Ok(
            val path: String,
            val widthPx: Int,
            val heightPx: Int,
            val sizeBytes: Long,
            val lens: String,
            val captureTimeMs: Long,
        ) : Result()
        data class Fail(val code: String, val detail: String) : Result()
    }

    enum class Lens(val selector: CameraSelector) {
        Back(CameraSelector.DEFAULT_BACK_CAMERA),
        Front(CameraSelector.DEFAULT_FRONT_CAMERA),
    }

    @Volatile private var provider: ProcessCameraProvider? = null
    @Volatile private var boundCapture: ImageCapture? = null
    @Volatile private var boundLens: Lens? = null

    /**
     * Capture one frame from the chosen lens, downscale, save to
     * `filesDir/photos/<ts>.jpg`. Returns a [Result] — never throws.
     */
    suspend fun capture(lens: Lens = Lens.Back): Result {
        if (!hasCameraPermission()) {
            return Result.Fail(
                "permission_denied",
                "CAMERA permission isn't granted. Mythara needs it to take a photo. Open the chat, ask once, and Android will prompt you — or grant it in Settings → Apps → Mythara → Permissions.",
            )
        }
        val capture = runCatching { ensureBound(lens) }.getOrElse { e ->
            Log.w(TAG, "ensureBound failed", e)
            return Result.Fail("not_ready", "Camera couldn't be opened: ${e.message ?: e.javaClass.simpleName}")
        }
        val photosDir = File(ctx.filesDir, PHOTOS_DIR).apply { mkdirs() }
        val now = System.currentTimeMillis()
        val tempFile = File(photosDir, "raw-${stampOf(now)}.jpg")
        val outFile = File(photosDir, "${stampOf(now)}.jpg")

        return try {
            takePictureSuspending(capture, tempFile)
            // Decode + downscale on IO. We always rewrite the file even
            // when the source is already small — that normalises EXIF
            // rotation so downstream consumers (vision_query) see
            // upright pixels without re-reading metadata.
            val (w, h, size) = withContext(Dispatchers.IO) { downscaleInPlace(tempFile, outFile) }
            runCatching { tempFile.delete() }
            Result.Ok(
                path = outFile.absolutePath,
                widthPx = w,
                heightPx = h,
                sizeBytes = size,
                lens = lens.name.lowercase(),
                captureTimeMs = now,
            )
        } catch (e: ImageCaptureException) {
            Log.w(TAG, "capture failed: ${e.imageCaptureError} ${e.message}", e)
            Result.Fail("capture_failed", "${e.imageCaptureError}: ${e.message ?: "unknown"}")
        } catch (e: Throwable) {
            Log.w(TAG, "capture threw", e)
            Result.Fail("threw", e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun ensureBound(lens: Lens): ImageCapture {
        val current = boundCapture
        if (current != null && boundLens == lens) return current
        val provider = ensureProvider()
        return withContext(Dispatchers.Main) {
            // Re-bind: unbind anything we previously bound, then bind
            // the fresh ImageCapture against the requested lens.
            provider.unbindAll()
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(android.view.Surface.ROTATION_0)
                .build()
            provider.bindToLifecycle(
                ProcessLifecycleOwner.get(),
                lens.selector,
                capture,
            )
            boundCapture = capture
            boundLens = lens
            capture
        }
    }

    private suspend fun ensureProvider(): ProcessCameraProvider {
        provider?.let { return it }
        return withContext(Dispatchers.Main) {
            // ProcessCameraProvider.getInstance returns a ListenableFuture
            // that completes on the main thread. Suspending here lets us
            // await it without a custom Future→suspend bridge.
            val future = ProcessCameraProvider.getInstance(ctx)
            val p = suspendCancellableCoroutine<ProcessCameraProvider> { cont ->
                future.addListener({
                    runCatching { cont.resume(future.get()) }
                        .onFailure { cont.resumeWithException(it) }
                }, ContextCompat.getMainExecutor(ctx))
            }
            provider = p
            p
        }
    }

    private suspend fun takePictureSuspending(capture: ImageCapture, file: File) {
        val output = ImageCapture.OutputFileOptions.Builder(file).build()
        suspendCancellableCoroutine<Unit> { cont ->
            capture.takePicture(
                output,
                ContextCompat.getMainExecutor(ctx),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                        cont.resume(Unit)
                    }

                    override fun onError(e: ImageCaptureException) {
                        cont.resumeWithException(e)
                    }
                },
            )
        }
    }

    /**
     * Decode the raw JPEG with `inSampleSize` so we don't materialise
     * the full multi-megapixel bitmap, then scale to the target long
     * edge, fix any EXIF rotation, and recompress at JPEG_QUALITY.
     * Returns (width, height, sizeBytes) of the saved file.
     */
    private fun downscaleInPlace(rawFile: File, outFile: File): Triple<Int, Int, Long> {
        // Pass 1: bounds only.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(rawFile.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            // Decode failed; copy raw → out and bail.
            rawFile.copyTo(outFile, overwrite = true)
            return Triple(0, 0, outFile.length())
        }
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        // Pass 2: choose an inSampleSize that gets us close to target
        // without going below it (so a 4032×3024 → 1024 sampling step
        // is 4 → 1008×756, then a final exact scale rounds it up).
        var sample = 1
        while (longEdge / (sample * 2) >= TARGET_LONG_EDGE) sample *= 2
        val decode = BitmapFactory.Options().apply { inSampleSize = sample }
        val sampled = BitmapFactory.decodeFile(rawFile.absolutePath, decode)
            ?: run {
                rawFile.copyTo(outFile, overwrite = true)
                return Triple(bounds.outWidth, bounds.outHeight, outFile.length())
            }
        val rotated = applyExifRotation(sampled, rawFile.absolutePath)
        val finalBitmap = if (maxOf(rotated.width, rotated.height) > TARGET_LONG_EDGE) {
            val scale = TARGET_LONG_EDGE.toFloat() / maxOf(rotated.width, rotated.height)
            val w = (rotated.width * scale).toInt().coerceAtLeast(1)
            val h = (rotated.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(rotated, w, h, true).also {
                if (it !== rotated) rotated.recycle()
            }
        } else rotated
        FileOutputStream(outFile).use { os ->
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, os)
        }
        val w = finalBitmap.width
        val h = finalBitmap.height
        finalBitmap.recycle()
        return Triple(w, h, outFile.length())
    }

    private fun applyExifRotation(bitmap: Bitmap, path: String): Bitmap {
        // Platform ExifInterface (android.media.ExifInterface) is
        // always available — no need for the androidx variant just to
        // read a single orientation tag.
        val exif = runCatching { android.media.ExifInterface(path) }.getOrNull()
            ?: return bitmap
        val orientation = exif.getAttributeInt(
            android.media.ExifInterface.TAG_ORIENTATION,
            android.media.ExifInterface.ORIENTATION_NORMAL,
        )
        val degrees = when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun stampOf(ms: Long): String =
        SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(ms))

    companion object {
        private const val TAG = "Mythara/Camera"
        private const val PHOTOS_DIR = "photos"
        private const val TARGET_LONG_EDGE = 1024
        private const val JPEG_QUALITY = 85
    }
}
