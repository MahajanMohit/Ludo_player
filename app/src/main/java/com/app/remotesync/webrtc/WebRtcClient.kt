package com.app.remotesync.webrtc

import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Core WebRTC peer-connection lifecycle manager.
 *
 * Roles:
 *  - HOST   — captures screen/audio, creates Offer, exposes local tracks via NSD + SignalingServer
 *  - CLIENT — receives Offer, creates Answer, renders remote video on SurfaceViewRenderer
 *
 * DataChannels (both created by HOST, received by CLIENT via [onDataChannel]):
 *  - "control"       ordered, text,   for [ControlEvent] JSON
 *  - "file_transfer" ordered, binary, for chunked file data
 */
@Singleton
class WebRtcClient @Inject constructor(
    private val factory: PeerConnectionFactory,
    private val signalingServer: SignalingServer,
    private val signalingClient: SignalingClient,
    private val dataChannelManager: DataChannelManager,
    private val screenCaptureVideoSource: ScreenCaptureVideoSource,
    private val audioCaptureManager: AudioCaptureManager
) {

    companion object {
        private val ICE_SERVERS = listOf(
            // STUN — only needed if devices are on different subnets; for same LAN, host ICE suffices
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow(WebRtcConnectionState.IDLE)
    val connectionState: StateFlow<WebRtcConnectionState> = _connectionState.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null

    // ─────────────────────────────────────────────────────────────────────────
    // PeerConnection.Observer
    // ─────────────────────────────────────────────────────────────────────────

    private val pcObserver = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) {
            Timber.d("PC signaling: $state")
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            Timber.i("PC ICE: $state")
            _connectionState.value = when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED ->
                    WebRtcConnectionState.CONNECTED

                PeerConnection.IceConnectionState.CHECKING ->
                    WebRtcConnectionState.CONNECTING

                PeerConnection.IceConnectionState.DISCONNECTED ->
                    WebRtcConnectionState.RECONNECTING

                PeerConnection.IceConnectionState.FAILED ->
                    WebRtcConnectionState.FAILED

                PeerConnection.IceConnectionState.CLOSED ->
                    WebRtcConnectionState.CLOSED

                else -> _connectionState.value
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {}

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            Timber.d("ICE gathering: $state")
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            Timber.d("Local ICE candidate: ${candidate.sdp}")
            scope.launch {
                val msg = SignalingMessage.IceCandidate(
                    sdpMid = candidate.sdpMid,
                    sdpMLineIndex = candidate.sdpMLineIndex,
                    candidate = candidate.sdp
                )
                // Send via whichever channel is active
                signalingServer.send(msg)
                signalingClient.send(msg)
            }
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}

        override fun onAddStream(stream: MediaStream) {
            Timber.i("Remote stream added: ${stream.id}")
            stream.videoTracks.firstOrNull()?.let { _remoteVideoTrack.value = it }
        }

        override fun onRemoveStream(stream: MediaStream) {
            _remoteVideoTrack.value = null
        }

        override fun onDataChannel(channel: DataChannel) {
            Timber.i("Remote data channel: ${channel.label()}")
            when (channel.label()) {
                "control" -> {
                    dataChannelManager.controlChannel = channel
                    channel.registerObserver(dataChannelManager.controlObserver)
                }
                "file_transfer" -> {
                    dataChannelManager.fileChannel = channel
                    channel.registerObserver(dataChannelManager.fileObserver)
                }
            }
        }

        override fun onRenegotiationNeeded() {
            Timber.d("Renegotiation needed")
        }

        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            val track = receiver.track()
            if (track is VideoTrack) {
                Timber.i("Remote video track received")
                _remoteVideoTrack.value = track
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * HOST: Initialize local tracks, create peer connection with DataChannels, then create Offer.
     */
    fun startAsHost(mediaProjectionPermissionData: Intent) {
        _connectionState.value = WebRtcConnectionState.CONNECTING
        val pc = buildPeerConnection() ?: return

        // ── Video track ──
        videoSource = factory.createVideoSource(/* isScreencast = */ true)
        screenCaptureVideoSource.initialize(mediaProjectionPermissionData, videoSource!!)
        localVideoTrack = factory.createVideoTrack("screen_video", videoSource).also {
            pc.addTrack(it, listOf("local_stream"))
        }

        // ── Audio track ──
        audioSource = factory.createAudioSource(MediaConstraints())
        localAudioTrack = factory.createAudioTrack("screen_audio", audioSource).also {
            pc.addTrack(it, listOf("local_stream"))
        }

        // ── DataChannels (host creates them) ──
        val dcInit = DataChannel.Init().apply { ordered = true }
        dataChannelManager.controlChannel = pc.createDataChannel("control", dcInit).also {
            it.registerObserver(dataChannelManager.controlObserver)
        }
        dataChannelManager.fileChannel = pc.createDataChannel("file_transfer", dcInit).also {
            it.registerObserver(dataChannelManager.fileObserver)
        }

        // ── Subscribe to signaling from client ──
        subscribeToSignaling(isHost = true)

        // ── Create Offer ──
        pc.createOffer(sdpObserver(isOffer = true), MediaConstraints())
    }

    /**
     * CLIENT: Create peer connection and connect to host's signaling server.
     */
    fun startAsClient(hostAddress: String, hostPort: Int) {
        _connectionState.value = WebRtcConnectionState.CONNECTING
        buildPeerConnection() ?: return

        subscribeToSignaling(isHost = false)
        signalingClient.connect(hostAddress, hostPort)
    }

    fun sendControlEvent(event: ControlEvent) {
        dataChannelManager.sendControlEvent(event)
    }

    fun notifyScreenRotated() {
        screenCaptureVideoSource.onScreenRotated()
    }

    fun disconnect() {
        audioCaptureManager.stop()
        screenCaptureVideoSource.dispose()
        dataChannelManager.dispose()
        peerConnection?.close()
        peerConnection = null
        localVideoTrack?.dispose()
        localAudioTrack?.dispose()
        videoSource?.dispose()
        audioSource?.dispose()
        _connectionState.value = WebRtcConnectionState.CLOSED
        _remoteVideoTrack.value = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildPeerConnection(): PeerConnection? {
        val config = PeerConnection.RTCConfiguration(ICE_SERVERS).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        return factory.createPeerConnection(config, pcObserver).also {
            peerConnection = it
            if (it == null) Timber.e("Failed to create PeerConnection")
        }
    }

    private fun subscribeToSignaling(isHost: Boolean) {
        val messagesFlow = if (isHost) signalingServer.incomingMessages
        else signalingClient.incomingMessages

        scope.launch {
            messagesFlow.collect { msg ->
                when (msg) {
                    is SignalingMessage.Offer -> handleOffer(msg)
                    is SignalingMessage.Answer -> handleAnswer(msg)
                    is SignalingMessage.IceCandidate -> handleIceCandidate(msg)
                    is SignalingMessage.Bye -> {
                        Timber.i("Signaling: peer disconnected")
                        _connectionState.value = WebRtcConnectionState.CLOSED
                    }
                    else -> {}
                }
            }
        }
    }

    private fun handleOffer(offer: SignalingMessage.Offer) {
        val pc = peerConnection ?: return
        val sdp = SessionDescription(SessionDescription.Type.OFFER, offer.sdp)
        pc.setRemoteDescription(loggingSdpObserver("setRemoteDesc(offer)"), sdp)
        pc.createAnswer(sdpObserver(isOffer = false), MediaConstraints())
    }

    private fun handleAnswer(answer: SignalingMessage.Answer) {
        val pc = peerConnection ?: return
        val sdp = SessionDescription(SessionDescription.Type.ANSWER, answer.sdp)
        pc.setRemoteDescription(loggingSdpObserver("setRemoteDesc(answer)"), sdp)
    }

    private fun handleIceCandidate(msg: SignalingMessage.IceCandidate) {
        peerConnection?.addIceCandidate(
            IceCandidate(msg.sdpMid, msg.sdpMLineIndex, msg.candidate)
        )
    }

    private fun sdpObserver(isOffer: Boolean): SdpObserver = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {
            peerConnection?.setLocalDescription(loggingSdpObserver("setLocalDesc"), sdp)
            scope.launch {
                val msg = if (isOffer) SignalingMessage.Offer(sdp.description)
                else SignalingMessage.Answer(sdp.description)
                signalingServer.send(msg)
                signalingClient.send(msg)
            }
        }

        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String) {
            Timber.e("SDP create failure: $error")
            _connectionState.value = WebRtcConnectionState.FAILED
        }

        override fun onSetFailure(error: String) {
            Timber.e("SDP set failure: $error")
        }
    }

    private fun loggingSdpObserver(tag: String) = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() { Timber.d("$tag: success") }
        override fun onCreateFailure(error: String) { Timber.e("$tag create error: $error") }
        override fun onSetFailure(error: String) { Timber.e("$tag set error: $error") }
    }
}
