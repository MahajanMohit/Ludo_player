package com.ludoautoplayer

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks the four runtime permissions required by LudoAutoPlayer and exposes their
 * collective state through a [StateFlow].
 *
 * Permission acquisition order:
 *  1. POST_NOTIFICATIONS (Android 13+)
 *  2. SYSTEM_ALERT_WINDOW (overlay / "display over other apps")
 *  3. MediaProjection (screen capture — granted at runtime via Intent)
 *  4. AccessibilityService (must be enabled in system settings)
 */
enum class PermissionStep {
    POST_NOTIFICATIONS,
    OVERLAY,
    MEDIA_PROJECTION,
    ACCESSIBILITY,
    ALL_GRANTED
}

class PermissionManager(private val context: Context) {

    private val _currentStep = MutableStateFlow(PermissionStep.POST_NOTIFICATIONS)
    val currentStep: StateFlow<PermissionStep> = _currentStep.asStateFlow()

    /** True when every required permission has been granted. */
    fun allPermissionsGranted(): Boolean {
        return isPostNotificationsGranted() &&
               isOverlayGranted() &&
               isMediaProjectionGranted() &&
               isAccessibilityGranted()
    }

    /**
     * Returns the next permission that still needs to be granted, or
     * [PermissionStep.ALL_GRANTED] if everything is in order.
     */
    fun nextRequiredPermission(): PermissionStep {
        if (!isPostNotificationsGranted()) return PermissionStep.POST_NOTIFICATIONS
        if (!isOverlayGranted())           return PermissionStep.OVERLAY
        if (!isMediaProjectionGranted())   return PermissionStep.MEDIA_PROJECTION
        if (!isAccessibilityGranted())     return PermissionStep.ACCESSIBILITY
        return PermissionStep.ALL_GRANTED
    }

    /**
     * Re-evaluates all permission states and updates [currentStep].
     * Call this after returning from a system settings screen.
     */
    fun refresh() {
        _currentStep.value = nextRequiredPermission()
    }

    // ==================================================================================
    // Individual permission checks
    // ==================================================================================

    fun isPostNotificationsGranted(): Boolean {
        // POST_NOTIFICATIONS is only required on Android 13 (API 33)+
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.areNotificationsEnabled()
        } else {
            true
        }
    }

    fun isOverlayGranted(): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * MediaProjection permission cannot be checked via a system API — it is ephemeral
     * and granted per-session. We track whether the token has been received by checking
     * a SharedPreferences flag that [OverlayService] sets when it acquires the projection.
     */
    fun isMediaProjectionGranted(): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_MEDIA_PROJECTION_GRANTED, false)
    }

    fun isAccessibilityGranted(): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC
        )
        return enabledServices.any { info ->
            info.resolveInfo.serviceInfo.packageName == context.packageName
        }
    }

    // ==================================================================================
    // Intent helpers (for use in UI)
    // ==================================================================================

    fun buildOverlaySettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
               Uri.parse("package:${context.packageName}"))

    fun buildAccessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    fun buildNotificationSettingsIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                   Uri.parse("package:${context.packageName}"))
        }

    companion object {
        const val PREFS_NAME = "ludo_auto_player_prefs"
        const val KEY_MEDIA_PROJECTION_GRANTED = "media_projection_granted"
    }
}
