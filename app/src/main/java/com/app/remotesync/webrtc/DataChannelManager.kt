package com.app.remotesync.webrtc

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.webrtc.DataChannel
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.charset.Charset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages two WebRTC DataChannels:
 *  - "control"       — JSON text frames carrying [ControlEvent] messages
 *  - "file_transfer" — binary chunks for on-demand file transfers, delimited
 *                      by [FileTransferMessage.Start] / [FileTransferMessage.End] text frames
 */
@Singleton
class DataChannelManager @Inject constructor() {

    private val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true }

    // Channels set by WebRtcClient once the peer connection is established
    var controlChannel: DataChannel? = null
    var fileChannel: DataChannel? = null

    // ─── Incoming control events ───────────────────────────────────────────────
    private val _controlEvents = MutableSharedFlow<ControlEvent>(extraBufferCapacity = 64)
    val controlEvents: SharedFlow<ControlEvent> = _controlEvents.asSharedFlow()

    // ─── Incoming file chunks ──────────────────────────────────────────────────
    private val _fileChunks = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    val fileChunks: SharedFlow<ByteArray> = _fileChunks.asSharedFlow()

    // ─── Incoming file-transfer text frames (START / END / CANCEL) ────────────
    private val _fileMessages = MutableSharedFlow<FileTransferMessage>(extraBufferCapacity = 16)
    val fileMessages: SharedFlow<FileTransferMessage> = _fileMessages.asSharedFlow()

    // ─────────────────────────────────────────────────────────────────────────
    // DataChannel.Observer implementations
    // ─────────────────────────────────────────────────────────────────────────

    val controlObserver = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) {}
        override fun onStateChange() {
            Timber.d("Control DataChannel state: ${controlChannel?.state()}")
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            if (!buffer.binary) {
                val text = Charset.forName("UTF-8").decode(buffer.data).toString()
                try {
                    val event = json.decodeFromString<ControlEvent>(text)
                    _controlEvents.tryEmit(event)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to parse control event: $text")
                }
            }
        }
    }

    val fileObserver = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) {}
        override fun onStateChange() {
            Timber.d("File DataChannel state: ${fileChannel?.state()}")
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            if (buffer.binary) {
                // Raw file chunk bytes
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                _fileChunks.tryEmit(bytes)
            } else {
                // Control frame (START / END / CANCEL)
                val text = Charset.forName("UTF-8").decode(buffer.data).toString()
                try {
                    val msg = json.decodeFromString<FileTransferMessage>(text)
                    _fileMessages.tryEmit(msg)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to parse file message: $text")
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sending helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Send a [ControlEvent] as a JSON text frame. */
    fun sendControlEvent(event: ControlEvent) {
        val channel = controlChannel ?: return
        if (channel.state() != DataChannel.State.OPEN) return
        val text = json.encodeToString(event)
        val bytes = text.toByteArray(Charsets.UTF_8)
        channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
    }

    /**
     * Send a file over the file DataChannel.
     * Sends a START frame, then binary chunks, then an END frame.
     * Runs in the caller's coroutine context — use Dispatchers.IO.
     */
    suspend fun sendFile(
        name: String,
        mimeType: String,
        data: ByteArray,
        chunkSize: Int = 65536
    ) {
        val channel = fileChannel ?: return
        if (channel.state() != DataChannel.State.OPEN) return

        val checksum = data.sha256Hex()

        // START frame
        val startMsg = FileTransferMessage.Start(
            name = name,
            size = data.size.toLong(),
            chunkSize = chunkSize,
            mimeType = mimeType
        )
        sendFileText(channel, json.encodeToString(startMsg))

        // Binary chunks
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + chunkSize, data.size)
            val chunk = data.copyOfRange(offset, end)
            channel.send(DataChannel.Buffer(ByteBuffer.wrap(chunk), true))
            offset = end
            // Back-pressure: yield if buffer is filling up
            while (channel.bufferedAmount() > chunkSize * 8L) {
                kotlinx.coroutines.delay(10)
            }
        }

        // END frame
        val endMsg = FileTransferMessage.End(name = name, checksum = checksum)
        sendFileText(channel, json.encodeToString(endMsg))
    }

    private fun sendFileText(channel: DataChannel, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
    }

    fun dispose() {
        controlChannel?.close()
        fileChannel?.close()
        controlChannel = null
        fileChannel = null
    }
}

private fun ByteArray.sha256Hex(): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    return md.digest(this).joinToString("") { "%02x".format(it) }
}
