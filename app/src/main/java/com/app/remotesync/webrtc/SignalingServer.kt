package com.app.remotesync.webrtc

import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Embedded Ktor/Netty WebSocket server running on the HOST device.
 *
 * - Listens on [port] (default 8765) for a single client connection.
 * - Incoming JSON [SignalingMessage] frames are emitted on [incomingMessages].
 * - Outgoing messages are sent via [send].
 */
@Singleton
class SignalingServer @Inject constructor() {

    companion object {
        const val DEFAULT_PORT = 8765
    }

    private val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true }

    private val _incomingMessages = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = 32)
    val incomingMessages: SharedFlow<SignalingMessage> = _incomingMessages.asSharedFlow()

    private var session: io.ktor.websocket.DefaultWebSocketSession? = null

    private val server = embeddedServer(Netty, port = DEFAULT_PORT) {
        install(WebSockets) {
            pingPeriod = Duration.ofSeconds(15)
            timeout = Duration.ofSeconds(30)
            maxFrameSize = Long.MAX_VALUE
        }
        routing {
            webSocket("/signal") {
                Timber.i("SignalingServer: client connected from ${call.request.local.remoteAddress}")
                session = this
                try {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            Timber.v("SignalingServer <<< $text")
                            try {
                                val msg = json.decodeFromString<SignalingMessage>(text)
                                _incomingMessages.tryEmit(msg)
                            } catch (e: Exception) {
                                Timber.w(e, "SignalingServer: failed to parse message")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "SignalingServer: session error")
                } finally {
                    session = null
                    Timber.i("SignalingServer: client disconnected")
                    _incomingMessages.tryEmit(SignalingMessage.Bye)
                }
            }
        }
    }

    fun start() {
        try {
            server.start(wait = false)
            Timber.i("SignalingServer started on port $DEFAULT_PORT")
        } catch (e: Exception) {
            Timber.e(e, "SignalingServer failed to start")
        }
    }

    fun stop() {
        server.stop(gracePeriodMillis = 500, timeoutMillis = 1000)
        Timber.i("SignalingServer stopped")
    }

    suspend fun send(message: SignalingMessage) {
        val text = json.encodeToString(message)
        Timber.v("SignalingServer >>> $text")
        session?.send(Frame.Text(text))
    }
}
