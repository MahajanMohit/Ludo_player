package com.app.remotesync.webrtc

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.WindowManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.webrtc.EglBase
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import timber.log.Timber
import javax.inject.Inject

/**
 * Wraps [ScreenCapturerAndroid] and manages the SurfaceTextureHelper lifecycle.
 *
 * On rotation, [onScreenRotated] must be called so the capturer adjusts its
 * output resolution accordingly — otherwise the stream remains letterboxed.
 */
class ScreenCaptureVideoSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eglBase: EglBase
) {

    private var capturer: ScreenCapturerAndroid? = null
    private var surfaceHelper: SurfaceTextureHelper? = null

    /**
     * Initializes the capturer against the given [videoSource] and starts capturing.
     *
     * @param mediaProjectionPermissionData The Intent data from [MediaProjectionManager.createScreenCaptureIntent()].
     * @param videoSource The WebRTC [VideoSource] to feed frames into.
     */
    fun initialize(
        mediaProjectionPermissionData: Intent,
        videoSource: VideoSource
    ) {
        val (width, height, fps) = currentDisplayMetrics()
        Timber.i("ScreenCaptureVideoSource: init ${width}x${height} @${fps}fps")

        surfaceHelper = SurfaceTextureHelper.create(
            "ScreenCapture-Thread",
            eglBase.eglBaseContext
        )

        capturer = ScreenCapturerAndroid(
            mediaProjectionPermissionData,
            object : MediaProjection.Callback() {
                override fun onStop() {
                    Timber.i("ScreenCaptureVideoSource: MediaProjection stopped")
                }
            }
        ).also { c ->
            c.initialize(surfaceHelper, context, videoSource.capturerObserver)
            c.startCapture(width, height, fps)
        }
    }

    /**
     * Called when the device screen rotates.
     * Updates capture resolution so the stream matches the new orientation.
     */
    fun onScreenRotated() {
        val (width, height, fps) = currentDisplayMetrics()
        Timber.i("ScreenCaptureVideoSource: rotation detected → ${width}x${height}")
        capturer?.changeCaptureFormat(width, height, fps)
    }

    fun dispose() {
        capturer?.stopCapture()
        capturer?.dispose()
        capturer = null
        surfaceHelper?.dispose()
        surfaceHelper = null
    }

    private data class DisplayConfig(val width: Int, val height: Int, val fps: Int)

    @Suppress("DEPRECATION")
    private fun currentDisplayMetrics(): DisplayConfig {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            DisplayConfig(bounds.width(), bounds.height(), 30)
        } else {
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            DisplayConfig(dm.widthPixels, dm.heightPixels, 30)
        }
    }
}
