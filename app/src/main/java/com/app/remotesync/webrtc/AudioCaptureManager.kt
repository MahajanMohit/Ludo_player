package com.app.remotesync.webrtc

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.audio.JavaAudioDeviceModule
import timber.log.Timber
import javax.inject.Inject

/**
 * Captures internal device audio (system sounds + app audio) using
 * [AudioPlaybackCaptureConfiguration] (Android 10+) and forwards PCM frames
 * to a custom [JavaAudioDeviceModule] recording callback.
 *
 * Usage:
 * 1. Call [start] once [MediaProjection] is available.
 * 2. Audio flows automatically into the WebRTC audio pipeline.
 * 3. Call [stop] to release resources.
 */
class AudioCaptureManager @Inject constructor() {

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private val BUFFER_SIZE = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
        ).coerceAtLeast(8192)
    }

    private var audioRecord: AudioRecord? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null

    // Callback set by WebRtcClient to receive raw PCM data
    var onAudioData: ((ByteArray) -> Unit)? = null

    /**
     * Starts capturing internal audio from the given [MediaProjection].
     * Requires RECORD_AUDIO permission and the MediaProjection to be active.
     */
    @androidx.annotation.RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    fun start(mediaProjection: MediaProjection) {
        stop()

        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG)
            .setEncoding(AUDIO_FORMAT)
            .build()

        audioRecord = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(format)
            .setBufferSizeInBytes(BUFFER_SIZE)
            .build()

        audioRecord?.startRecording()
        Timber.i("AudioCaptureManager: started")

        captureJob = scope.launch {
            val buffer = ByteArray(BUFFER_SIZE)
            while (isActive) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read > 0) {
                    onAudioData?.invoke(buffer.copyOf(read))
                }
            }
        }
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        Timber.i("AudioCaptureManager: stopped")
    }
}
