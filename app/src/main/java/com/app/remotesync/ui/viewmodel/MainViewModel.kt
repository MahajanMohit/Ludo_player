package com.app.remotesync.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.remotesync.network.NsdDiscoveryManager
import com.app.remotesync.webrtc.ControlEvent
import com.app.remotesync.webrtc.DataChannelManager
import com.app.remotesync.webrtc.DiscoveredDevice
import com.app.remotesync.webrtc.FileTransferMessage
import com.app.remotesync.webrtc.WebRtcClient
import com.app.remotesync.webrtc.WebRtcConnectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.webrtc.VideoTrack
import timber.log.Timber
import javax.inject.Inject

data class MainUiState(
    val connectionState: WebRtcConnectionState = WebRtcConnectionState.IDLE,
    val discoveredDevices: List<DiscoveredDevice> = emptyList(),
    val isHosting: Boolean = false,
    val fileTransferProgress: Int = 0, // 0 = idle, 1–99 = in progress, 100 = done
    val receivedFileName: String? = null,
    val signalingServerPort: Int = 8765,
    val errorMessage: String? = null
)

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val webRtcClient: WebRtcClient,
    private val nsdDiscoveryManager: NsdDiscoveryManager,
    private val dataChannelManager: DataChannelManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    val remoteVideoTrack: StateFlow<VideoTrack?> = webRtcClient.remoteVideoTrack

    // Accumulated file bytes for incoming transfer
    private var incomingFileBuffer = mutableListOf<ByteArray>()
    private var incomingFileName: String? = null
    private var incomingFileSize: Long = 0
    private var incomingBytesReceived: Long = 0

    init {
        observeConnectionState()
        observeDiscoveredDevices()
        observeIncomingFiles()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Host mode
    // ─────────────────────────────────────────────────────────────────────────

    fun startHosting() {
        nsdDiscoveryManager.registerService(_uiState.value.signalingServerPort)
        _uiState.update { it.copy(isHosting = true) }
    }

    fun stopHosting() {
        nsdDiscoveryManager.unregisterService()
        webRtcClient.disconnect()
        _uiState.update { it.copy(isHosting = false) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Client mode
    // ─────────────────────────────────────────────────────────────────────────

    fun startDiscovery() {
        nsdDiscoveryManager.startDiscovery()
    }

    fun stopDiscovery() {
        nsdDiscoveryManager.stopDiscovery()
    }

    fun connectToDevice(device: DiscoveredDevice) {
        webRtcClient.startAsClient(device.host, device.port)
        _uiState.update { it.copy(connectionState = WebRtcConnectionState.CONNECTING) }
    }

    fun disconnect() {
        webRtcClient.disconnect()
        _uiState.update { it.copy(connectionState = WebRtcConnectionState.IDLE) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Control
    // ─────────────────────────────────────────────────────────────────────────

    fun sendControlEvent(event: ControlEvent) {
        webRtcClient.sendControlEvent(event)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // File transfer
    // ─────────────────────────────────────────────────────────────────────────

    fun sendFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cr = context.contentResolver
                val mimeType = cr.getType(uri) ?: "application/octet-stream"
                val fileName = uri.lastPathSegment ?: "file"
                val bytes = cr.openInputStream(uri)?.readBytes() ?: return@launch

                _uiState.update { it.copy(fileTransferProgress = 1) }
                dataChannelManager.sendFile(
                    name = fileName,
                    mimeType = mimeType,
                    data = bytes
                )
                _uiState.update { it.copy(fileTransferProgress = 100) }
            } catch (e: Exception) {
                Timber.e(e, "File send failed")
                _uiState.update { it.copy(fileTransferProgress = 0, errorMessage = e.message) }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Observers
    // ─────────────────────────────────────────────────────────────────────────

    private fun observeConnectionState() {
        webRtcClient.connectionState
            .onEach { state ->
                _uiState.update { it.copy(connectionState = state) }
            }
            .launchIn(viewModelScope)
    }

    private fun observeDiscoveredDevices() {
        nsdDiscoveryManager.discoveredDevices
            .onEach { devices ->
                _uiState.update { it.copy(discoveredDevices = devices) }
            }
            .launchIn(viewModelScope)
    }

    private fun observeIncomingFiles() {
        // File metadata frames
        dataChannelManager.fileMessages
            .onEach { msg ->
                when (msg) {
                    is FileTransferMessage.Start -> {
                        incomingFileName = msg.name
                        incomingFileSize = msg.size
                        incomingBytesReceived = 0
                        incomingFileBuffer.clear()
                        _uiState.update { it.copy(fileTransferProgress = 1) }
                        Timber.i("Incoming file: ${msg.name} (${msg.size} bytes)")
                    }
                    is FileTransferMessage.End -> {
                        saveReceivedFile(msg.name)
                        _uiState.update {
                            it.copy(
                                fileTransferProgress = 100,
                                receivedFileName = msg.name
                            )
                        }
                    }
                    is FileTransferMessage.Cancel -> {
                        incomingFileBuffer.clear()
                        _uiState.update { it.copy(fileTransferProgress = 0) }
                    }
                }
            }
            .launchIn(viewModelScope)

        // Binary chunks
        dataChannelManager.fileChunks
            .onEach { chunk ->
                incomingFileBuffer.add(chunk)
                incomingBytesReceived += chunk.size
                val progress = if (incomingFileSize > 0) {
                    (incomingBytesReceived * 99 / incomingFileSize).toInt().coerceIn(1, 99)
                } else 50
                _uiState.update { it.copy(fileTransferProgress = progress) }
            }
            .launchIn(viewModelScope)
    }

    private fun saveReceivedFile(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val allBytes = incomingFileBuffer.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
                incomingFileBuffer.clear()

                val downloadsDir = context.getExternalFilesDir("Downloads")
                    ?: context.filesDir
                downloadsDir.mkdirs()

                val file = java.io.File(downloadsDir, name)
                file.writeBytes(allBytes)
                Timber.i("File saved: ${file.absolutePath}")
            } catch (e: Exception) {
                Timber.e(e, "Failed to save received file")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        nsdDiscoveryManager.stopDiscovery()
        nsdDiscoveryManager.unregisterService()
    }
}
