package com.ludoautoplayer

import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ludoautoplayer.models.PlayerColor

// =====================================================================================
// Navigation routes
// =====================================================================================
private object Routes {
    const val WELCOME    = "welcome"
    const val PERMISSION = "permission"
    const val DASHBOARD  = "dashboard"
    const val SETTINGS   = "settings"
}

// =====================================================================================
// MainActivity
// =====================================================================================
class MainActivity : ComponentActivity() {

    private lateinit var permissionManager: PermissionManager
    private var pendingMediaProjectionResult: ((Int, Intent?) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionManager = PermissionManager(this)

        setContent {
            LudoAutoPlayerTheme {
                val navController = rememberNavController()

                // MediaProjection launcher
                val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val mediaProjectionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    pendingMediaProjectionResult?.invoke(result.resultCode, result.data)
                    pendingMediaProjectionResult = null
                }

                // POST_NOTIFICATIONS launcher (Android 13+)
                val notificationPermLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { _ ->
                    permissionManager.refresh()
                }

                NavHost(navController = navController, startDestination = Routes.WELCOME) {
                    composable(Routes.WELCOME) {
                        WelcomeScreen(onGetStarted = {
                            navController.navigate(Routes.PERMISSION)
                        })
                    }
                    composable(Routes.PERMISSION) {
                        PermissionWizardScreen(
                            permissionManager     = permissionManager,
                            onAllGranted          = { navController.navigate(Routes.DASHBOARD) },
                            onRequestMediaProjection = {
                                pendingMediaProjectionResult = it
                                mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
                            },
                            onRequestNotification = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        )
                    }
                    composable(Routes.DASHBOARD) {
                        DashboardScreen(
                            permissionManager = permissionManager,
                            onSettings        = { navController.navigate(Routes.SETTINGS) },
                            onStartAutoPlayer = { color, strat ->
                                startOverlayService(color, strat)
                            },
                            onRequestMediaProjection = {
                                pendingMediaProjectionResult = it
                                mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
                            }
                        )
                    }
                    composable(Routes.SETTINGS) {
                        SettingsScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }

    private fun startOverlayService(color: PlayerColor, strategy: StrategyEngine.Strategy) {
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        pendingMediaProjectionResult = { resultCode, data ->
            if (resultCode == RESULT_OK && data != null) {
                val intent = Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_START
                    putExtra(OverlayService.EXTRA_BOT_COLOR, color.name)
                    putExtra(OverlayService.EXTRA_STRATEGY, strategy.name)
                    putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(OverlayService.EXTRA_PROJECTION_DATA, data)
                }
                startForegroundService(intent)
                // Launch Ludo King
                val ludoIntent = packageManager.getLaunchIntentForPackage("com.ludoking.app")
                ludoIntent?.let { startActivity(it) }
            }
        }
        startActivityForResult(mpManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            pendingMediaProjectionResult?.invoke(resultCode, data)
            pendingMediaProjectionResult = null
        }
    }

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 1002
    }
}

// =====================================================================================
// Theme
// =====================================================================================
@Composable
fun LudoAutoPlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            primary         = Color(0xFF6750A4),
            onPrimary       = Color(0xFFFFFFFF),
            primaryContainer= Color(0xFF4F378B),
            secondary       = Color(0xFFCCC2DC),
            background      = Color(0xFF1C1B1F),
            surface         = Color(0xFF1C1B1F),
            onSurface       = Color(0xFFE6E1E5)
        ),
        content = content
    )
}

// =====================================================================================
// Screen 1 — Welcome
// =====================================================================================
@Composable
fun WelcomeScreen(onGetStarted: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // App icon placeholder
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text("🎲", fontSize = 48.sp)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Ludo Auto Player",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "An intelligent auto-player for Ludo King that plays on your behalf using screen analysis and strategic decision making.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = onGetStarted,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Get Started", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// =====================================================================================
// Screen 2 — Permission Wizard
// =====================================================================================
private data class PermInfo(
    val step: PermissionStep,
    val title: String,
    val description: String,
    val icon: ImageVector
)

private val PERM_INFO_LIST = listOf(
    PermInfo(
        PermissionStep.POST_NOTIFICATIONS,
        "Notification Permission",
        "Required to show status notifications when the auto-player is running in the background.",
        Icons.Default.Notifications
    ),
    PermInfo(
        PermissionStep.OVERLAY,
        "Display Over Other Apps",
        "Required to display the control overlay panel on top of Ludo King while the auto-player is active.",
        Icons.Default.Layers
    ),
    PermInfo(
        PermissionStep.MEDIA_PROJECTION,
        "Screen Capture",
        "Required to read the Ludo King game screen and detect dice values, piece positions, and turn status.",
        Icons.Default.Camera
    ),
    PermInfo(
        PermissionStep.ACCESSIBILITY,
        "Accessibility Service",
        "Required to perform taps on the Ludo King screen to roll the dice and move pieces automatically.",
        Icons.Default.AccessibilityNew
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionWizardScreen(
    permissionManager: PermissionManager,
    onAllGranted: () -> Unit,
    onRequestMediaProjection: ((Int, Intent?) -> Unit) -> Unit,
    onRequestNotification: () -> Unit
) {
    val context = LocalContext.current
    val currentStep by permissionManager.currentStep.collectAsState()

    LaunchedEffect(currentStep) {
        if (currentStep == PermissionStep.ALL_GRANTED) onAllGranted()
    }

    val info = PERM_INFO_LIST.firstOrNull { it.step == currentStep }
        ?: PERM_INFO_LIST.last()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Setup") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Progress indicator
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                PERM_INFO_LIST.forEachIndexed { idx, item ->
                    val isComplete = item.step.ordinal < currentStep.ordinal
                    val isCurrent  = item.step == currentStep
                    Box(
                        modifier = Modifier
                            .size(if (isCurrent) 12.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isComplete -> MaterialTheme.colorScheme.primary
                                    isCurrent  -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                    else       -> MaterialTheme.colorScheme.outline
                                }
                            )
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = info.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(40.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = info.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = info.description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp
                )
            }

            Button(
                onClick = {
                    when (currentStep) {
                        PermissionStep.POST_NOTIFICATIONS -> {
                            onRequestNotification()
                            permissionManager.refresh()
                        }
                        PermissionStep.OVERLAY -> {
                            context.startActivity(permissionManager.buildOverlaySettingsIntent())
                        }
                        PermissionStep.MEDIA_PROJECTION -> {
                            onRequestMediaProjection { _, _ ->
                                permissionManager.refresh()
                            }
                        }
                        PermissionStep.ACCESSIBILITY -> {
                            context.startActivity(permissionManager.buildAccessibilitySettingsIntent())
                        }
                        PermissionStep.ALL_GRANTED -> onAllGranted()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Grant Permission", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = { permissionManager.refresh() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("I've granted it — continue")
            }
        }
    }
}

// =====================================================================================
// Screen 3 — Dashboard
// =====================================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    permissionManager: PermissionManager,
    onSettings: () -> Unit,
    onStartAutoPlayer: (PlayerColor, StrategyEngine.Strategy) -> Unit,
    onRequestMediaProjection: ((Int, Intent?) -> Unit) -> Unit
) {
    val context = LocalContext.current

    // Re-check permissions each time the composable is composed
    val notif   = permissionManager.isPostNotificationsGranted()
    val overlay = permissionManager.isOverlayGranted()
    val capture = permissionManager.isMediaProjectionGranted()
    val a11y    = permissionManager.isAccessibilityGranted()
    val allOk   = notif && overlay && a11y // capture is requested on-demand

    var isRunning by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dashboard") },
                actions = {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        modifier = Modifier
                            .padding(end = 16.dp)
                            .clickable { onSettings() },
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ---- Permission status card ----
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Permissions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    PermissionRow("Post Notifications", notif)
                    PermissionRow("Display Over Apps",  overlay)
                    PermissionRow("Screen Capture",     capture)
                    PermissionRow("Accessibility",      a11y)
                }
            }

            // ---- Status card ----
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isRunning)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Default.PlayArrow else Icons.Default.Stop,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (isRunning) "Auto Player Running" else "Auto Player Stopped",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // ---- Launch Ludo King button ----
            OutlinedButton(
                onClick = {
                    val intent = context.packageManager.getLaunchIntentForPackage("com.ludoking.app")
                    if (intent != null) context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Launch Ludo King")
            }

            // ---- Start / Stop Auto Player button ----
            Button(
                onClick = {
                    if (!isRunning) {
                        onStartAutoPlayer(PlayerColor.RED, StrategyEngine.Strategy.AGGRESSIVE)
                        isRunning = true
                    } else {
                        val stopIntent = Intent(context, OverlayService::class.java).apply {
                            action = OverlayService.ACTION_STOP
                        }
                        context.startService(stopIntent)
                        isRunning = false
                    }
                },
                enabled = allOk || isRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isRunning) "Stop Auto Player" else "Start Auto Player",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (!allOk) {
                Text(
                    text = "Some permissions are still required. Please grant all permissions before starting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = if (granted) "Granted" else "Not granted",
            tint = if (granted) Color(0xFF43A047) else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp)
        )
    }
}

// =====================================================================================
// Screen 4 — Settings
// =====================================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    var selectedColor by remember { mutableStateOf(PlayerColor.RED) }
    var speed         by remember { mutableFloatStateOf(1f) }
    var isAggressive  by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Back",
                        modifier = Modifier
                            .padding(start = 16.dp)
                            .clickable { onBack() }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {

            // ---- Player Color Selector ----
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Bot Player Color", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PlayerColor.values().forEach { color ->
                            val bgColor = when (color) {
                                PlayerColor.RED    -> Color(0xFFE53935)
                                PlayerColor.GREEN  -> Color(0xFF43A047)
                                PlayerColor.YELLOW -> Color(0xFFFDD835)
                                PlayerColor.BLUE   -> Color(0xFF1E88E5)
                            }
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(bgColor)
                                    .border(
                                        width = if (selectedColor == color) 3.dp else 0.dp,
                                        color = Color.White,
                                        shape = CircleShape
                                    )
                                    .clickable { selectedColor = color },
                                contentAlignment = Alignment.Center
                            ) {
                                if (selectedColor == color) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Selected: ${selectedColor.colorName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            // ---- Play Speed ----
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Play Speed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = when {
                            speed < 0.4f -> "Slow (longer delays)"
                            speed < 0.75f -> "Normal"
                            else -> "Fast (shorter delays)"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Slider(
                        value = speed,
                        onValueChange = { speed = it },
                        valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Slow", style = MaterialTheme.typography.bodySmall)
                        Text("Fast", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // ---- Strategy Toggle ----
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Strategy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isAggressive) "Aggressive — prioritise captures" else "Defensive — prioritise safety",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Switch(
                        checked = isAggressive,
                        onCheckedChange = { isAggressive = it }
                    )
                }
            }

            // ---- Save button ----
            Button(
                onClick = { onBack() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Save & Back", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
