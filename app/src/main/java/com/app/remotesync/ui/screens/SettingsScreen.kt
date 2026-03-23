package com.app.remotesync.ui.screens

import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.remotesync.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val powerManager = context.getSystemService(PowerManager::class.java)
    val isBatteryOptDisabled = remember {
        powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    val isAccessibilityEnabled = remember {
        mutableStateOf(isAccessibilityServiceEnabled(context))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── Accessibility Service ─────────────────────────────────────
            SettingsSectionCard("Remote Control — Accessibility") {
                if (!isAccessibilityEnabled.value) {
                    Text(
                        "The Accessibility Service must be enabled so this device can receive and inject touch input from a remote controller.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                        )
                    }) {
                        Icon(Icons.Default.Accessibility, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Open Accessibility Settings")
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Steps: Open Accessibility → Installed Services → RemoteSync Control → Enable",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text("Accessibility Service enabled", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // ── Battery Optimization ─────────────────────────────────────
            SettingsSectionCard("Battery") {
                if (!isBatteryOptDisabled) {
                    Text(
                        "Battery optimization is active. OEM skins (OxygenOS, MIUI, etc.) may kill the screen capture session when the app is in the background. Tap below to exempt this app.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = android.net.Uri.parse("package:${context.packageName}")
                            }
                        )
                    }) {
                        Icon(Icons.Default.BatteryAlert, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Disable Battery Optimization")
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.BatteryFull,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text("Battery optimization disabled")
                    }
                }
            }

            // ── Video Quality ────────────────────────────────────────────
            var targetFps by remember { mutableFloatStateOf(30f) }
            var maxBitrateKbps by remember { mutableFloatStateOf(2000f) }

            SettingsSectionCard("Video Quality") {
                Text(
                    "Target Frame Rate: ${targetFps.toInt()} fps",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = targetFps,
                    onValueChange = { targetFps = it },
                    valueRange = 10f..60f,
                    steps = 4
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Max Bitrate: ${maxBitrateKbps.toInt()} kbps",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = maxBitrateKbps,
                    onValueChange = { maxBitrateKbps = it },
                    valueRange = 500f..8000f,
                    steps = 14
                )
            }

            // ── Network ──────────────────────────────────────────────────
            SettingsSectionCard("Network") {
                Text(
                    "Signaling Port: ${uiState.signalingServerPort}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "The host device's embedded WebSocket server listens on this port.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable Column.() -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

private fun isAccessibilityServiceEnabled(context: android.content.Context): Boolean {
    val serviceName =
        "${context.packageName}/.services.RemoteControlAccessibilityService"
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabled.split(":").any { it.equals(serviceName, ignoreCase = true) }
}
