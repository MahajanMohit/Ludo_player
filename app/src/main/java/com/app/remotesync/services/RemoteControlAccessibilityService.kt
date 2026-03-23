package com.app.remotesync.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import com.app.remotesync.webrtc.ControlEvent
import com.app.remotesync.webrtc.DataChannelManager
import com.app.remotesync.webrtc.GlobalActionType
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Receives [ControlEvent] messages from the WebRTC DataChannel and dispatches
 * them as real Android gestures and global actions.
 *
 * Gesture protocol:
 * - [ControlEvent.TouchDown] → stroke with willContinue = true (finger down)
 * - [ControlEvent.TouchMove] → continuation stroke (finger dragging)
 * - [ControlEvent.TouchUp]   → final stroke with willContinue = false (finger up)
 *
 * Coordinates are received as 0.0–1.0 normalized values and multiplied by the
 * device's real display dimensions before dispatching.
 */
@AndroidEntryPoint
class RemoteControlAccessibilityService : AccessibilityService() {

    @Inject
    lateinit var dataChannelManager: DataChannelManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Track active pointer strokes so multi-touch continuations reference the same stroke
    private val activeStrokes = mutableMapOf<Int, GestureDescription.StrokeDescription>()

    // Cache resolved screen dimensions
    private var screenWidth = 0
    private var screenHeight = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        resolveScreenDimensions()
        observeControlEvents()
        Timber.i("RemoteControlAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Event observation
    // ─────────────────────────────────────────────────────────────────────────

    private fun observeControlEvents() {
        scope.launch {
            dataChannelManager.controlEvents.collect { event ->
                handleControlEvent(event)
            }
        }
    }

    private fun handleControlEvent(event: ControlEvent) {
        when (event) {
            is ControlEvent.TouchDown -> dispatchTouchDown(event)
            is ControlEvent.TouchMove -> dispatchTouchMove(event)
            is ControlEvent.TouchUp   -> dispatchTouchUp(event)
            is ControlEvent.Scroll    -> dispatchScroll(event)
            is ControlEvent.Pinch     -> dispatchPinch(event)
            is ControlEvent.GlobalAction -> dispatchGlobalAction(event)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Touch dispatch helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun dispatchTouchDown(event: ControlEvent.TouchDown) {
        val (px, py) = denormalize(event.x, event.y)
        val path = Path().apply { moveTo(px, py) }
        // willContinue = true means this stroke expects a continuation (TouchMove or TouchUp)
        val stroke = GestureDescription.StrokeDescription(path, 0L, 1L, true)
        activeStrokes[event.pointerId] = stroke

        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, gestureCallback("TouchDown"), null)
        Timber.v("TouchDown (${px}, ${py})")
    }

    private fun dispatchTouchMove(event: ControlEvent.TouchMove) {
        val previous = activeStrokes[event.pointerId] ?: run {
            // No prior down — synthesize one
            dispatchTouchDown(ControlEvent.TouchDown(event.x, event.y, event.pointerId))
            return
        }
        val (px, py) = denormalize(event.x, event.y)
        val path = Path().apply { moveTo(px, py) }
        val continuation = previous.continueStroke(path, 0L, 1L, true)
        activeStrokes[event.pointerId] = continuation

        val gesture = GestureDescription.Builder().addStroke(continuation).build()
        dispatchGesture(gesture, gestureCallback("TouchMove"), null)
    }

    private fun dispatchTouchUp(event: ControlEvent.TouchUp) {
        val (px, py) = denormalize(event.x, event.y)
        val previous = activeStrokes.remove(event.pointerId)

        if (previous != null) {
            val path = Path().apply { moveTo(px, py) }
            // willContinue = false → finger lifted
            val finalStroke = previous.continueStroke(path, 0L, 1L, false)
            val gesture = GestureDescription.Builder().addStroke(finalStroke).build()
            dispatchGesture(gesture, gestureCallback("TouchUp"), null)
        } else {
            // Standalone tap (no prior down registered)
            val path = Path().apply { moveTo(px, py) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 50L, false)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, gestureCallback("TouchUp(standalone)"), null)
        }
        Timber.v("TouchUp (${px}, ${py})")
    }

    private fun dispatchScroll(event: ControlEvent.Scroll) {
        val (sx, sy) = denormalize(event.x, event.y)
        val dx = event.dx * screenWidth
        val dy = event.dy * screenHeight
        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(sx + dx, sy + dy)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 200L, false)
        dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            gestureCallback("Scroll"),
            null
        )
    }

    private fun dispatchPinch(event: ControlEvent.Pinch) {
        val (cx, cy) = denormalize(event.x, event.y)
        val span = 100f * event.scaleFactor
        // Two fingers moving apart (scale > 1) or together (scale < 1)
        val path1 = Path().apply { moveTo(cx - span, cy); lineTo(cx - span / 2, cy) }
        val path2 = Path().apply { moveTo(cx + span, cy); lineTo(cx + span / 2, cy) }
        val stroke1 = GestureDescription.StrokeDescription(path1, 0L, 150L, false)
        val stroke2 = GestureDescription.StrokeDescription(path2, 0L, 150L, false)
        dispatchGesture(
            GestureDescription.Builder().addStroke(stroke1).addStroke(stroke2).build(),
            gestureCallback("Pinch"),
            null
        )
    }

    private fun dispatchGlobalAction(event: ControlEvent.GlobalAction) {
        val actionId = when (event.action) {
            GlobalActionType.BACK          -> GLOBAL_ACTION_BACK
            GlobalActionType.HOME          -> GLOBAL_ACTION_HOME
            GlobalActionType.RECENTS       -> GLOBAL_ACTION_RECENTS
            GlobalActionType.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
            GlobalActionType.QUICK_SETTINGS -> GLOBAL_ACTION_QUICK_SETTINGS
            GlobalActionType.LOCK_SCREEN   -> GLOBAL_ACTION_LOCK_SCREEN
        }
        performGlobalAction(actionId)
        Timber.v("GlobalAction: ${event.action}")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Coordinate mapping
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Convert normalized coordinates (0.0–1.0) to device pixel coordinates.
     */
    private fun denormalize(nx: Float, ny: Float): Pair<Float, Float> {
        return Pair(nx * screenWidth, ny * screenHeight)
    }

    private fun resolveScreenDimensions() {
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            screenWidth = dm.widthPixels
            screenHeight = dm.heightPixels
        }
        Timber.i("AccessibilityService screen: ${screenWidth}x${screenHeight}")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Gesture callback
    // ─────────────────────────────────────────────────────────────────────────

    private fun gestureCallback(tag: String) = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription) {
            Timber.v("Gesture completed: $tag")
        }

        override fun onCancelled(gestureDescription: GestureDescription) {
            Timber.w("Gesture cancelled: $tag")
        }
    }
}
