package com.app.remotesync.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.remotesync.ui.viewmodel.MainViewModel
import com.app.remotesync.utils.CoordinateMapper
import com.app.remotesync.webrtc.ControlEvent
import com.app.remotesync.webrtc.GlobalActionType
import com.app.remotesync.webrtc.WebRtcConnectionState
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteControlViewport(
    hostAddress: String,
    onNavigateBack: () -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val remoteVideoTrack by viewModel.remoteVideoTrack.collectAsStateWithLifecycle()

    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var surfaceRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    // Default remote dims — updated once the host sends a ScreenDimensions signaling message
    val remoteWidth = 1080f
    val remoteHeight = 1920f

    // Attach remote video track to surface once both are available
    LaunchedEffect(remoteVideoTrack, surfaceRenderer) {
        val track = remoteVideoTrack ?: return@LaunchedEffect
        val renderer = surfaceRenderer ?: return@LaunchedEffect
        track.addSink(renderer)
    }

    DisposableEffect(Unit) {
        onDispose {
            remoteVideoTrack?.removeSink(surfaceRenderer ?: return@onDispose)
            surfaceRenderer?.release()
        }
    }

    // File picker for on-demand transfer
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.sendFile(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (uiState.connectionState) {
                            WebRtcConnectionState.CONNECTED -> "Connected – $hostAddress"
                            WebRtcConnectionState.CONNECTING -> "Connecting…"
                            WebRtcConnectionState.FAILED -> "Connection Failed"
                            else -> hostAddress
                        },
                        style = MaterialTheme.typography.titleSmall
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.disconnect()
                        onNavigateBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { filePicker.launch("*/*") }) {
                        Icon(Icons.Default.AttachFile, "Send File")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.7f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        },
        containerColor = Color.Black
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            // ── Video Surface ─────────────────────────────────────────────
            AndroidView(
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).apply {
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                        surfaceRenderer = this
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewportSize = it }
                    // ── Tap handling ──────────────────────────────────────
                    .pointerInput(viewportSize) {
                        detectTapGestures(
                            onTap = { offset ->
                                val norm = CoordinateMapper.viewportToRemoteNormalized(
                                    offset.x, offset.y,
                                    viewportSize.width.toFloat(),
                                    viewportSize.height.toFloat(),
                                    remoteWidth, remoteHeight
                                )
                                viewModel.sendControlEvent(
                                    ControlEvent.TouchDown(norm.x, norm.y, 0)
                                )
                                viewModel.sendControlEvent(
                                    ControlEvent.TouchUp(norm.x, norm.y, 0)
                                )
                            },
                            onLongPress = { offset ->
                                val norm = CoordinateMapper.viewportToRemoteNormalized(
                                    offset.x, offset.y,
                                    viewportSize.width.toFloat(),
                                    viewportSize.height.toFloat(),
                                    remoteWidth, remoteHeight
                                )
                                viewModel.sendControlEvent(
                                    ControlEvent.TouchDown(norm.x, norm.y, 0)
                                )
                            }
                        )
                    }
                    // ── Drag / Swipe handling ─────────────────────────────
                    .pointerInput(viewportSize) {
                        var lastOffset = Offset.Zero
                        detectDragGestures(
                            onDragStart = { offset ->
                                lastOffset = offset
                                val norm = CoordinateMapper.viewportToRemoteNormalized(
                                    offset.x, offset.y,
                                    viewportSize.width.toFloat(),
                                    viewportSize.height.toFloat(),
                                    remoteWidth, remoteHeight
                                )
                                viewModel.sendControlEvent(
                                    ControlEvent.TouchDown(norm.x, norm.y, 0)
                                )
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val norm = CoordinateMapper.viewportToRemoteNormalized(
                                    change.position.x, change.position.y,
                                    viewportSize.width.toFloat(),
                                    viewportSize.height.toFloat(),
                                    remoteWidth, remoteHeight
                                )
                                viewModel.sendControlEvent(
                                    ControlEvent.TouchMove(norm.x, norm.y, 0)
                                )
                                lastOffset = change.position
                            },
                            onDragEnd = {
                                val norm = CoordinateMapper.viewportToRemoteNormalized(
                                    lastOffset.x, lastOffset.y,
                                    viewportSize.width.toFloat(),
                                    viewportSize.height.toFloat(),
                                    remoteWidth, remoteHeight
                                )
                                viewModel.sendControlEvent(
                                    ControlEvent.TouchUp(norm.x, norm.y, 0)
                                )
                            },
                            onDragCancel = {
                                val norm = CoordinateMapper.viewportToRemoteNormalized(
                                    lastOffset.x, lastOffset.y,
                                    viewportSize.width.toFloat(),
                                    viewportSize.height.toFloat(),
                                    remoteWidth, remoteHeight
                                )
                                viewModel.sendControlEvent(
                                    ControlEvent.TouchUp(norm.x, norm.y, 0)
                                )
                            }
                        )
                    }
            )

            // ── Connecting overlay ────────────────────────────────────────
            if (uiState.connectionState == WebRtcConnectionState.CONNECTING) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(color = Color.White)
                        Text("Establishing secure connection…", color = Color.White)
                    }
                }
            }

            // ── Global action bar (bottom) ─────────────────────────────────
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                IconButton(onClick = {
                    viewModel.sendControlEvent(
                        ControlEvent.GlobalAction(GlobalActionType.BACK)
                    )
                }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }

                IconButton(onClick = {
                    viewModel.sendControlEvent(
                        ControlEvent.GlobalAction(GlobalActionType.HOME)
                    )
                }) { Icon(Icons.Default.Home, "Home", tint = Color.White) }

                IconButton(onClick = {
                    viewModel.sendControlEvent(
                        ControlEvent.GlobalAction(GlobalActionType.RECENTS)
                    )
                }) { Icon(Icons.Default.Apps, "Recents", tint = Color.White) }

                IconButton(onClick = {
                    viewModel.sendControlEvent(
                        ControlEvent.GlobalAction(GlobalActionType.NOTIFICATIONS)
                    )
                }) { Icon(Icons.Default.Notifications, "Notifications", tint = Color.White) }
            }

            // ── File transfer progress bar ─────────────────────────────────
            if (uiState.fileTransferProgress in 1..99) {
                LinearProgressIndicator(
                    progress = { uiState.fileTransferProgress / 100f },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                )
            }
        }
    }
}
