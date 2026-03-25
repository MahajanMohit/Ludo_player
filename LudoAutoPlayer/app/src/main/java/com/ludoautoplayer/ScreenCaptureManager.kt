package com.ludoautoplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages MediaProjection-based screen capture.
 *
 * Usage:
 *  1. Obtain a [MediaProjection] token from [OverlayService] (or pass it in directly).
 *  2. Call [startCapture] with a callback to receive frames every ~500 ms.
 *  3. Call [stopCapture] or [release] to clean up.
 */
class ScreenCaptureManager(
    private val context: Context,
    private val mediaProjection: MediaProjection
) {
    private val tag = "ScreenCaptureManager"

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureJob: Job? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val metrics = context.resources.displayMetrics
    private val screenWidth  = metrics.widthPixels
    private val screenHeight = metrics.heightPixels
    private val screenDpi    = metrics.densityDpi

    // ==================================================================================
    // Public API
    // ==================================================================================

    /**
     * Starts a repeating capture loop that delivers frames to [onFrame] at ~500 ms intervals.
     * Frames are delivered on the IO dispatcher; switch to Main if you need to update UI.
     */
    fun startCapture(onFrame: (Bitmap) -> Unit) {
        if (captureJob?.isActive == true) {
            Log.w(tag, "startCapture() called while already running — ignoring")
            return
        }

        try {
            setupVirtualDisplay()
        } catch (e: SecurityException) {
            Log.e(tag, "SecurityException during VirtualDisplay setup: ${e.message}")
            return
        }

        captureJob = scope.launch {
            while (isActive) {
                val bmp = acquireLatestBitmap()
                if (bmp != null) {
                    onFrame(bmp)
                }
                delay(CAPTURE_INTERVAL_MS)
            }
        }
    }

    /** Stops the repeating capture loop but does NOT release the MediaProjection. */
    fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        teardownVirtualDisplay()
    }

    /**
     * Captures a single frame synchronously (from a coroutine context).
     * Returns null if the VirtualDisplay is not running or an image cannot be acquired.
     */
    suspend fun captureOnce(): Bitmap? = withContext(Dispatchers.IO) {
        var display: VirtualDisplay? = null
        var reader: ImageReader? = null
        return@withContext try {
            reader = ImageReader.newInstance(
                screenWidth, screenHeight,
                PixelFormat.RGBA_8888, 2
            )
            display = mediaProjection.createVirtualDisplay(
                "LudoOneShot",
                screenWidth, screenHeight, screenDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )
            // Give the display a moment to render the first frame
            delay(300)
            acquireBitmapFromReader(reader)
        } catch (e: SecurityException) {
            Log.e(tag, "captureOnce SecurityException: ${e.message}")
            null
        } finally {
            display?.release()
            reader?.close()
        }
    }

    /**
     * Fully releases all resources including the [MediaProjection].
     * Call this when the capturing session is permanently over.
     */
    fun release() {
        stopCapture()
        try {
            mediaProjection.stop()
        } catch (e: Exception) {
            Log.w(tag, "Exception while stopping MediaProjection: ${e.message}")
        }
    }

    // ==================================================================================
    // Private helpers
    // ==================================================================================

    private fun setupVirtualDisplay() {
        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight,
            PixelFormat.RGBA_8888, 2
        )
        virtualDisplay = mediaProjection.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            screenWidth, screenHeight, screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
    }

    private fun teardownVirtualDisplay() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
    }

    private fun acquireLatestBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        return acquireBitmapFromReader(reader)
    }

    private fun acquireBitmapFromReader(reader: ImageReader): Bitmap? {
        var image: Image? = null
        return try {
            image = reader.acquireLatestImage() ?: return null
            val planes = image.planes
            if (planes.isEmpty()) return null

            val plane   = planes[0]
            val buffer  = plane.buffer
            val width   = image.width
            val height  = image.height
            val rowStride    = plane.rowStride
            val pixelStride  = plane.pixelStride

            // Handle row padding
            val rowPadding = rowStride - pixelStride * width
            val bmp = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(buffer)

            // Crop away any row padding
            if (rowPadding > 0) {
                Bitmap.createBitmap(bmp, 0, 0, width, height).also { bmp.recycle() }
            } else {
                bmp
            }
        } catch (e: Exception) {
            Log.e(tag, "Error acquiring bitmap: ${e.message}")
            null
        } finally {
            // Always close to avoid buffer starvation
            image?.close()
        }
    }

    companion object {
        private const val VIRTUAL_DISPLAY_NAME = "LudoAutoPlayerCapture"
        private const val CAPTURE_INTERVAL_MS  = 500L
    }
}
