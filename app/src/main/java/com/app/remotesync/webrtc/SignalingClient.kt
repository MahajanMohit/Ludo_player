package com.app.remotesync.webrtc

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebSocket signaling CLIENT running on the REMOTE-CONTROL device.
 * Connects to [SignalingServer] on the host via the address/port discovered by NSD.
 */
@Singleton
class SignalingClient @Inject constructor() {

    private val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true }

    private val _incomingMessages = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = 32)
    val incomingMessages: SharedFlow<SignalingMessage> = _incomingMessages.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectJob: Job? = null
    private var wsSession: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession? = null

    private val client = HttpClient(CIO) {
        install(WebSockets)
    }

    fun connect(host: String, port: Int) {
        connectJob?.cancel()
        connectJob = scope.launch {
            try {
                Timber.i("SignalingClient: connecting to ws://$host:$port/signal")
                client.webSocket(host = host, port = port, path = "/signal") {
                    wsSession = this
                    Timber.i("SignalingClient: connected")
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            Timber.v("SignalingClient <<< $text")
                            try {
                                val msg = json.decodeFromString<SignalingMessage>(text)
                                _incomingMessages.tryEmit(msg)
                            } catch (e: Exception) {
                                Timber.w(e, "SignalingClient: parse error")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "SignalingClient: connection error")
            } finally {
                wsSession = null
                _incomingMessages.tryEmit(SignalingMessage.Bye)
                Timber.i("SignalingClient: disconnected")
            }
        }
    }

    suspend fun send(message: SignalingMessage) {
        val text = json.encodeToString(message)
        Timber.v("SignalingClient >>> $text")
        wsSession?.send(Frame.Text(text))
    }

    fun disconnect() {
        scope.launch { wsSession?.close() }
        connectJob?.cancel()
    }

    fun dispose() {
        scope.cancel()
        client.close()
    }
}
