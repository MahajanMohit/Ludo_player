package com.ludoautoplayer

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.TextView

/**
 * LudoAccessibilityService provides two main capabilities:
 *  1. Gesture dispatch — used by [TapExecutor] to perform taps on the screen.
 *  2. Floating overlay — a small draggable control panel displayed over Ludo King.
 *
 * The [instance] companion object allows other components to obtain a reference to the
 * running service without needing a bound-service connection.
 */
class LudoAccessibilityService : AccessibilityService() {

    private val tag = "LudoA11yService"

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    // Touch tracking for dragging the overlay
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    // ==================================================================================
    // Lifecycle
    // ==================================================================================

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(tag, "Accessibility service connected")
        setupOverlay()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(tag, "Accessibility service unbound")
        removeOverlay()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        removeOverlay()
        instance = null
        super.onDestroy()
    }

    // ==================================================================================
    // Accessibility events
    // ==================================================================================

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Route events to OverlayService if needed in future
        // Currently handled passively; gesture dispatch is the primary mechanism.
    }

    override fun onInterrupt() {
        Log.w(tag, "Accessibility service interrupted")
    }

    // ==================================================================================
    // Overlay management
    // ==================================================================================

    private fun setupOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_control, null)
        overlayView = view

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 200
        }

        // Draggable touch listener
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    wm.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }

        // Stop button
        view.findViewById<Button>(R.id.btn_stop)?.setOnClickListener {
            val stopIntent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_STOP
            }
            startService(stopIntent)
        }

        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            Log.e(tag, "Failed to add overlay view: ${e.message}")
        }
    }

    private fun removeOverlay() {
        try {
            val view = overlayView
            if (view != null) {
                windowManager?.removeView(view)
                overlayView = null
            }
        } catch (e: Exception) {
            Log.w(tag, "Exception removing overlay: ${e.message}")
        }
    }

    // ==================================================================================
    // Overlay UI update helpers
    // ==================================================================================

    fun updateStatus(status: String) {
        overlayView?.post {
            overlayView?.findViewById<TextView>(R.id.tv_status)?.text = status
        }
    }

    fun updateMoveCount(count: Int) {
        overlayView?.post {
            overlayView?.findViewById<TextView>(R.id.tv_move_count)?.text = "Moves: $count"
        }
    }

    // ==================================================================================
    // Singleton
    // ==================================================================================

    companion object {
        @Volatile
        var instance: LudoAccessibilityService? = null
            private set
    }
}
