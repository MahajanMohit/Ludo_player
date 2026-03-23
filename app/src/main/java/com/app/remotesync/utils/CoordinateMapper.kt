package com.app.remotesync.utils

import android.graphics.PointF

/**
 * Maps touch coordinates between the local viewport (where the user taps)
 * and the remote device's native screen space.
 *
 * The video stream is rendered with SCALE_ASPECT_FIT, meaning the video may
 * be letterboxed or pillarboxed inside the viewport. All coordinates must be
 * adjusted to account for this padding.
 */
object CoordinateMapper {

    /**
     * Converts a point from viewport-local pixels into normalized remote-screen
     * coordinates (0.0–1.0 in both axes).
     *
     * @param viewX       Touch X in local viewport pixels
     * @param viewY       Touch Y in local viewport pixels
     * @param viewportW   Width of the local Compose viewport in pixels
     * @param viewportH   Height of the local Compose viewport in pixels
     * @param remoteW     Native width of the remote device screen in pixels
     * @param remoteH     Native height of the remote device screen in pixels
     * @return Normalized [PointF] where (0,0) = top-left, (1,1) = bottom-right
     *         of the remote screen; clamped to [0,1].
     */
    fun viewportToRemoteNormalized(
        viewX: Float,
        viewY: Float,
        viewportW: Float,
        viewportH: Float,
        remoteW: Float,
        remoteH: Float
    ): PointF {
        // ── Compute aspect-fit video rect inside the viewport ──────────────
        val (videoLeft, videoTop, videoRight, videoBottom) =
            aspectFitRect(remoteW, remoteH, viewportW, viewportH)

        val videoW = videoRight - videoLeft
        val videoH = videoBottom - videoTop

        // ── Map viewport pixel to normalized remote coordinate ─────────────
        val normX = ((viewX - videoLeft) / videoW).coerceIn(0f, 1f)
        val normY = ((viewY - videoTop) / videoH).coerceIn(0f, 1f)

        return PointF(normX, normY)
    }

    /**
     * Converts normalized remote coordinates back to local viewport pixels.
     * Used for rendering overlay elements (e.g., a remote cursor indicator).
     */
    fun remoteNormalizedToViewport(
        normX: Float,
        normY: Float,
        viewportW: Float,
        viewportH: Float,
        remoteW: Float,
        remoteH: Float
    ): PointF {
        val (videoLeft, videoTop, videoRight, videoBottom) =
            aspectFitRect(remoteW, remoteH, viewportW, viewportH)

        val videoW = videoRight - videoLeft
        val videoH = videoBottom - videoTop

        return PointF(
            videoLeft + normX * videoW,
            videoTop + normY * videoH
        )
    }

    /**
     * Computes the letterboxed/pillarboxed rectangle for rendering [srcW]×[srcH]
     * content inside [dstW]×[dstH] container while preserving aspect ratio.
     *
     * @return [FitRect] with left/top/right/bottom in destination space.
     */
    fun aspectFitRect(
        srcW: Float,
        srcH: Float,
        dstW: Float,
        dstH: Float
    ): FitRect {
        if (srcW <= 0f || srcH <= 0f || dstW <= 0f || dstH <= 0f) {
            return FitRect(0f, 0f, dstW, dstH)
        }

        val srcRatio = srcW / srcH
        val dstRatio = dstW / dstH

        return if (srcRatio > dstRatio) {
            // Letterbox: video is wider than container → top/bottom bars
            val scaledH = dstW / srcRatio
            val offsetY = (dstH - scaledH) / 2f
            FitRect(left = 0f, top = offsetY, right = dstW, bottom = offsetY + scaledH)
        } else {
            // Pillarbox: video is taller than container → left/right bars
            val scaledW = dstH * srcRatio
            val offsetX = (dstW - scaledW) / 2f
            FitRect(left = offsetX, top = 0f, right = offsetX + scaledW, bottom = dstH)
        }
    }

    data class FitRect(val left: Float, val top: Float, val right: Float, val bottom: Float)
}
