package com.app.remotesync.webrtc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─────────────────────────────────────────────────────────────────────────────
// Signaling messages exchanged over the WebSocket channel
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
sealed class SignalingMessage {

    @Serializable
    @SerialName("offer")
    data class Offer(val sdp: String) : SignalingMessage()

    @Serializable
    @SerialName("answer")
    data class Answer(val sdp: String) : SignalingMessage()

    @Serializable
    @SerialName("ice_candidate")
    data class IceCandidate(
        val sdpMid: String,
        val sdpMLineIndex: Int,
        val candidate: String
    ) : SignalingMessage()

    @Serializable
    @SerialName("screen_dimensions")
    data class ScreenDimensions(val width: Int, val height: Int) : SignalingMessage()

    @Serializable
    @SerialName("bye")
    object Bye : SignalingMessage()
}

// ─────────────────────────────────────────────────────────────────────────────
// Control events sent over the "control" DataChannel (JSON text frames)
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
sealed class ControlEvent {

    /** Finger (or pointer) pressed down. Coordinates are 0.0–1.0 normalized. */
    @Serializable
    @SerialName("TouchDown")
    data class TouchDown(val x: Float, val y: Float, val pointerId: Int) : ControlEvent()

    /** Finger moved. */
    @Serializable
    @SerialName("TouchMove")
    data class TouchMove(val x: Float, val y: Float, val pointerId: Int) : ControlEvent()

    /** Finger lifted. */
    @Serializable
    @SerialName("TouchUp")
    data class TouchUp(val x: Float, val y: Float, val pointerId: Int) : ControlEvent()

    /** Two-finger pinch / zoom. */
    @Serializable
    @SerialName("Pinch")
    data class Pinch(
        val x: Float,
        val y: Float,
        val scaleFactor: Float
    ) : ControlEvent()

    /** Fling / velocity scroll. dx/dy are normalized deltas. */
    @Serializable
    @SerialName("Scroll")
    data class Scroll(val x: Float, val y: Float, val dx: Float, val dy: Float) : ControlEvent()

    /** Hardware button or system action. */
    @Serializable
    @SerialName("GlobalAction")
    data class GlobalAction(val action: GlobalActionType) : ControlEvent()
}

@Serializable
enum class GlobalActionType {
    BACK,
    HOME,
    RECENTS,
    NOTIFICATIONS,
    QUICK_SETTINGS,
    LOCK_SCREEN
}

// ─────────────────────────────────────────────────────────────────────────────
// File-transfer protocol messages (over the "file_transfer" DataChannel)
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
sealed class FileTransferMessage {

    @Serializable
    @SerialName("FILE_START")
    data class Start(
        val name: String,
        val size: Long,
        val chunkSize: Int,
        val mimeType: String
    ) : FileTransferMessage()

    @Serializable
    @SerialName("FILE_END")
    data class End(val name: String, val checksum: String) : FileTransferMessage()

    @Serializable
    @SerialName("FILE_CANCEL")
    data class Cancel(val name: String, val reason: String) : FileTransferMessage()
}

// ─────────────────────────────────────────────────────────────────────────────
// WebRTC connection state
// ─────────────────────────────────────────────────────────────────────────────

enum class WebRtcConnectionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED,
    CLOSED
}

// ─────────────────────────────────────────────────────────────────────────────
// Discovered peer device
// ─────────────────────────────────────────────────────────────────────────────

data class DiscoveredDevice(
    val name: String,
    val host: String,
    val port: Int
)
