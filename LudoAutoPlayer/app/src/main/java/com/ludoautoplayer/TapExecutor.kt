package com.ludoautoplayer

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Executes tap gestures on the screen via [LudoAccessibilityService].
 *
 * Each tap is:
 *  - Preceded by a random delay of 800–2000 ms (to appear human-like).
 *  - Offset by a random ±5 px in both axes.
 *  - Performed as a stroke with a 100 ms duration.
 */
class TapExecutor {

    private val tag = "TapExecutor"
    private val scope = CoroutineScope(Dispatchers.Main)

    /**
     * Taps at ([x], [y]) with a human-like random delay and jitter.
     * Invokes [onComplete] on the main thread after the gesture is dispatched
     * (or immediately if the service is unavailable).
     */
    fun tapAt(x: Float, y: Float, onComplete: () -> Unit = {}) {
        scope.launch {
            // Random pre-tap delay: 800–2000 ms
            val delayMs = Random.nextLong(800L, 2001L)
            delay(delayMs)

            // Random ±5 px jitter
            val jitterX = Random.nextFloat() * 10f - 5f
            val jitterY = Random.nextFloat() * 10f - 5f
            val tapX = x + jitterX
            val tapY = y + jitterY

            dispatchTap(tapX, tapY, onComplete)
        }
    }

    /**
     * Taps immediately without any random delay (used internally for chained actions).
     */
    fun tapAtImmediate(x: Float, y: Float, onComplete: () -> Unit = {}) {
        scope.launch {
            dispatchTap(x, y, onComplete)
        }
    }

    // ==================================================================================
    // Private helpers
    // ==================================================================================

    private fun dispatchTap(x: Float, y: Float, onComplete: () -> Unit) {
        val service = LudoAccessibilityService.instance
        if (service == null) {
            Log.w(tag, "AccessibilityService instance is null — cannot tap at ($x, $y)")
            onComplete()
            return
        }

        val path = Path().apply { moveTo(x, y) }

        val stroke = GestureDescription.StrokeDescription(
            path,
            /* startTime  */ 0L,
            /* duration   */ STROKE_DURATION_MS
        )

        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        val dispatched = service.dispatchGesture(
            gesture,
            object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(tag, "Tap completed at ($x, $y)")
                    onComplete()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(tag, "Tap cancelled at ($x, $y)")
                    onComplete()
                }
            },
            /* handler */ null
        )

        if (!dispatched) {
            Log.e(tag, "dispatchGesture returned false for tap at ($x, $y)")
            onComplete()
        }
    }

    companion object {
        private const val STROKE_DURATION_MS = 100L
    }
}
