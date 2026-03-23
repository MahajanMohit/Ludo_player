package com.app.remotesync.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.remotesync.services.ScreenCaptureService
import com.app.remotesync.ui.viewmodel.MainViewModel
import com.app.remotesync.webrtc.DiscoveredDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDiscoveryScreen(
    onNavigateToViewport: (DiscoveredDevice) -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // ── MediaProjection launcher ──────────────────────────────────────────
    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenCaptureService.start(context, result.resultCode, result.data!!)
            viewModel.startHosting()
        }
    }

    // ── Start discovery on entering ───────────────────────────────────────
    DisposableEffect(Unit) {
        viewModel.startDiscovery()
        onDispose { viewModel.stopDiscovery() }
    }

    // ── Battery optimization check ────────────────────────────────────────
    val powerManager = context.getSystemService(PowerManager::class.java)
    val isBatteryOptDisabled = powerManager.isIgnoringBatteryOptimizations(context.packageName)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RemoteSync") },
                actions = {
                    IconButton(onClick = { viewModel.startDiscovery() }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── Battery optimization warning ──────────────────────────────
            if (!isBatteryOptDisabled) {
                BatteryOptimizationBanner(context)
            }

            // ── Host mode card ────────────────────────────────────────────
            HostModeCard(
                isHosting = uiState.isHosting,
                onStartHosting = {
                    val mpManager =
                        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                                as MediaProjectionManager
                    mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
                },
                onStopHosting = { viewModel.stopHosting() }
            )

            // ── Discovered devices list ───────────────────────────────────
            Text(
                "Nearby Devices",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            if (uiState.discoveredDevices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Scanning for devices…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(uiState.discoveredDevices, key = { it.name }) { device ->
                        DeviceListItem(
                            device = device,
                            onClick = {
                                viewModel.connectToDevice(device)
                                onNavigateToViewport(device)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HostModeCard(
    isHosting: Boolean,
    onStartHosting: () -> Unit,
    onStopHosting: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Cast,
                    contentDescription = null,
                    tint = if (isHosting) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Host Mode", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (isHosting) "Sharing your screen" else "Let another device view and control this screen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (isHosting) {
                OutlinedButton(
                    onClick = onStopHosting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.StopCircle, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Stop Sharing")
                }
            } else {
                Button(
                    onClick = onStartHosting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Cast, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start Sharing")
                }
            }
        }
    }
}

@Composable
private fun BatteryOptimizationBanner(context: Context) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Battery optimization active",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    "Disable it to prevent the session from being killed in the background.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    ).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                }
            ) { Text("Fix") }
        }
    }
}

@Composable
private fun DeviceListItem(device: DiscoveredDevice, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        ListItem(
            headlineContent = { Text(device.name) },
            supportingContent = {
                Text(
                    "${device.host}:${device.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            leadingContent = {
                Icon(Icons.Default.Devices, contentDescription = null)
            },
            trailingContent = {
                Icon(Icons.Default.ChevronRight, contentDescription = null)
            }
        )
    }
}
