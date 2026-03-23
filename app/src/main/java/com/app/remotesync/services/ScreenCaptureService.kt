package com.app.remotesync.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.app.remotesync.R
import com.app.remotesync.webrtc.AudioCaptureManager
import com.app.remotesync.webrtc.ScreenCaptureVideoSource
import com.app.remotesync.webrtc.WebRtcClient
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service that holds the [MediaProjection] token and drives the
 * screen / audio capture pipeline.
 *
 * Start with:
 * ```
 * ScreenCaptureService.start(context, resultCode, permissionData)
 * ```
 */
@AndroidEntryPoint
class ScreenCaptureService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "remotesync_capture"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_PROJECTION_DATA, data)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }

    @Inject lateinit var webRtcClient: WebRtcClient
    @Inject lateinit var screenCaptureVideoSource: ScreenCaptureVideoSource
    @Inject lateinit var audioCaptureManager: AudioCaptureManager

    private var mediaProjection: MediaProjection? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()

        // Must call before acquiring MediaProjection on API 29+
        startForeground(NOTIFICATION_ID, notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val projectionData = intent?.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)

        if (resultCode == android.app.Activity.RESULT_OK && projectionData != null) {
            val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, projectionData).also {
                it.registerCallback(projectionCallback, null)
            }

            // Start WebRTC host session with the projection permission data
            webRtcClient.startAsHost(projectionData)

            // Start internal audio capture
            audioCaptureManager.start(mediaProjection!!)

            // Listen for screen rotation to update capture format
            registerDisplayListener()
        } else {
            Timber.e("ScreenCaptureService: invalid result code or null projection data")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        unregisterDisplayListener()
        audioCaptureManager.stop()
        webRtcClient.disconnect()
        mediaProjection?.stop()
        mediaProjection = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Rotation handling
    // ─────────────────────────────────────────────────────────────────────────

    private fun registerDisplayListener() {
        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == android.view.Display.DEFAULT_DISPLAY) {
                    Timber.d("ScreenCaptureService: display changed (rotation)")
                    webRtcClient.notifyScreenRotated()
                }
            }
        }
        dm.registerDisplayListener(displayListener, null)
    }

    private fun unregisterDisplayListener() {
        displayListener?.let {
            (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(it)
        }
        displayListener = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification channel
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Timber.i("MediaProjection stopped externally")
            stopSelf()
        }
    }
}
