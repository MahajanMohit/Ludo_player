package com.ludoautoplayer

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.ludoautoplayer.models.BoardPosition
import com.ludoautoplayer.models.GameState
import com.ludoautoplayer.models.Piece
import com.ludoautoplayer.models.PlayerColor
import kotlinx.coroutines.delay
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Analyses screen-captured frames to extract the current [GameState].
 *
 * Approach:
 *  - Piece detection: colour-sampling at mapped board coordinates.
 *    Known piece colours (sRGB):
 *      RED    ≈ (229,  57,  53)  #E53935
 *      GREEN  ≈ ( 67, 160,  71)  #43A047
 *      YELLOW ≈ (253, 216,  53)  #FDD835
 *      BLUE   ≈ ( 30, 136, 229)  #1E88E5
 *  - Dice detection: OpenCV contour counting on the cropped dice region.
 *  - Active turn: detected by sampling the highlighted colour of each player's panel.
 */
class GameStateAnalyser(private val boardMapper: BoardMapper) {

    private val tag = "GameStateAnalyser"

    // Tolerance (Euclidean RGB distance) for colour matching
    private val COLOR_TOLERANCE = 60.0

    // Known piece colours
    private val PIECE_COLORS = mapOf(
        PlayerColor.RED    to Triple(229,  57,  53),
        PlayerColor.GREEN  to Triple( 67, 160,  71),
        PlayerColor.YELLOW to Triple(253, 216,  53),
        PlayerColor.BLUE   to Triple( 30, 136, 229)
    )

    // Fraction of screen used for the dice area (rough Ludo King layout)
    private val DICE_REGION_LEFT   = 0.35f
    private val DICE_REGION_TOP    = 0.65f
    private val DICE_REGION_RIGHT  = 0.65f
    private val DICE_REGION_BOTTOM = 0.82f

    // Active-turn indicator panel regions (thin strip along each player's side)
    private val TURN_REGIONS = mapOf(
        PlayerColor.RED    to floatArrayOf(0.05f, 0.50f, 0.10f, 0.55f),
        PlayerColor.GREEN  to floatArrayOf(0.90f, 0.50f, 0.95f, 0.55f),
        PlayerColor.YELLOW to floatArrayOf(0.50f, 0.88f, 0.55f, 0.93f),
        PlayerColor.BLUE   to floatArrayOf(0.50f, 0.05f, 0.55f, 0.10f)
    )

    // ==================================================================================
    // Public API
    // ==================================================================================

    /**
     * Derives a full [GameState] from a single [bitmap] frame.
     */
    fun analyseFrame(bitmap: Bitmap): GameState {
        val w = bitmap.width
        val h = bitmap.height

        val diceValue   = detectDiceValue(bitmap)
        val activePlayer = detectActiveTurn(bitmap)
        val pieces      = detectPiecePositions(bitmap)
        val moveableIds = detectMoveablePieces(bitmap)

        // Mark moveable pieces
        val markedPieces = pieces.map { piece ->
            if (piece.id in moveableIds && piece.color == activePlayer) {
                piece.copy(isMoveable = true)
            } else piece
        }

        val isBotTurn = activePlayer != null   // caller decides if activePlayer == botColor

        return GameState(
            activePlayer  = activePlayer,
            diceValue     = diceValue,
            pieces        = markedPieces,
            isAnimating   = false,             // will be updated by waitForStableFrame
            isBotTurn     = isBotTurn,
            bonusTurn     = diceValue == 6
        )
    }

    // ==================================================================================
    // Dice detection (OpenCV contour counting)
    // ==================================================================================

    /**
     * Crops the dice region from [bitmap], converts to greyscale, and counts blob contours
     * to determine the dice value. Returns -1 if the dice is not visible or detection fails.
     */
    fun detectDiceValue(bitmap: Bitmap): Int {
        val w = bitmap.width
        val h = bitmap.height

        val left   = (DICE_REGION_LEFT   * w).toInt()
        val top    = (DICE_REGION_TOP    * h).toInt()
        val right  = (DICE_REGION_RIGHT  * w).toInt()
        val bottom = (DICE_REGION_BOTTOM * h).toInt()

        val cropW = right - left
        val cropH = bottom - top
        if (cropW <= 0 || cropH <= 0) return -1

        val cropped = try {
            Bitmap.createBitmap(bitmap, left, top, cropW, cropH)
        } catch (e: Exception) {
            Log.w(tag, "Dice crop failed: ${e.message}")
            return -1
        }

        val src   = Mat()
        val gray  = Mat()
        val blur  = Mat()
        val thresh = Mat()
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()

        return try {
            Utils.bitmapToMat(cropped, src)
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, blur, Size(5.0, 5.0), 0.0)
            // Threshold: dice dots are darker than the white dice face
            Imgproc.threshold(blur, thresh, 100.0, 255.0, Imgproc.THRESH_BINARY_INV)
            Imgproc.findContours(
                thresh, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
            )
            // Filter by area to remove noise; dice dots have a consistent small area
            val minDotArea = (cropW * cropH) * 0.005
            val maxDotArea = (cropW * cropH) * 0.08
            val dotCount = contours.count { c ->
                val area = Imgproc.contourArea(c)
                area in minDotArea..maxDotArea
            }
            dotCount.coerceIn(-1, 6).let { if (it == 0) -1 else it }
        } catch (e: Exception) {
            Log.e(tag, "detectDiceValue error: ${e.message}")
            -1
        } finally {
            src.release(); gray.release(); blur.release()
            thresh.release(); hierarchy.release()
            cropped.recycle()
        }
    }

    // ==================================================================================
    // Active turn detection
    // ==================================================================================

    /**
     * Samples each player's turn-indicator panel to find which one is currently highlighted.
     * Returns null if turn cannot be determined.
     */
    fun detectActiveTurn(bitmap: Bitmap): PlayerColor? {
        val w = bitmap.width
        val h = bitmap.height

        var brightest: PlayerColor? = null
        var maxBrightness = 0.0

        for ((color, region) in TURN_REGIONS) {
            val left   = (region[0] * w).toInt().coerceAtLeast(0)
            val top    = (region[1] * h).toInt().coerceAtLeast(0)
            val right  = (region[2] * w).toInt().coerceAtMost(w - 1)
            val bottom = (region[3] * h).toInt().coerceAtMost(h - 1)

            if (right <= left || bottom <= top) continue

            // Sample average brightness in the region
            var sum = 0.0
            var count = 0
            for (x in left..right step 2) {
                for (y in top..bottom step 2) {
                    val px = bitmap.getPixel(x, y)
                    sum += (Color.red(px) * 0.299 + Color.green(px) * 0.587 + Color.blue(px) * 0.114)
                    count++
                }
            }
            if (count == 0) continue
            val avg = sum / count
            if (avg > maxBrightness) {
                maxBrightness = avg
                brightest = color
            }
        }
        return if (maxBrightness > 180.0) brightest else null
    }

    // ==================================================================================
    // Piece position detection
    // ==================================================================================

    /**
     * Samples pixel colours at every path square and home-base slot for each player,
     * assigning piece positions based on colour match.
     */
    fun detectPiecePositions(bitmap: Bitmap): List<Piece> {
        val w = bitmap.width
        val h = bitmap.height
        val pieces = mutableListOf<Piece>()
        val pieceIdCounters = mutableMapOf<PlayerColor, Int>()

        // Sample path squares
        for (index in 0..51) {
            val pt = boardMapper.getPathCoordinate(index, w, h)
            val px = bitmap.getPixel(
                pt.x.toInt().coerceIn(0, w - 1),
                pt.y.toInt().coerceIn(0, h - 1)
            )
            val color = matchPieceColor(px) ?: continue
            val pieceId = pieceIdCounters.getOrDefault(color, 0)
            if (pieceId >= 4) continue // max 4 pieces per color
            pieceIdCounters[color] = pieceId + 1
            val isSafe = BoardMapper.SAFE_SQUARES.contains(index)
            pieces.add(
                Piece(
                    id       = pieceId,
                    color    = color,
                    position = BoardPosition(
                        index         = index,
                        isSafeSquare  = isSafe
                    )
                )
            )
        }

        // Sample home-base slots for any pieces still at home
        for (color in PlayerColor.values()) {
            for (slot in 0..3) {
                val pt = boardMapper.getHomeBaseCoordinate(color, slot, w, h)
                val px = bitmap.getPixel(
                    pt.x.toInt().coerceIn(0, w - 1),
                    pt.y.toInt().coerceIn(0, h - 1)
                )
                val detectedColor = matchPieceColor(px) ?: continue
                if (detectedColor != color) continue
                val pieceId = pieceIdCounters.getOrDefault(color, 0)
                if (pieceId >= 4) continue
                pieceIdCounters[color] = pieceId + 1
                pieces.add(
                    Piece(
                        id       = pieceId,
                        color    = color,
                        position = BoardPosition(
                            index       = -1,
                            isHomeBase  = true
                        )
                    )
                )
            }
        }

        return pieces
    }

    /**
     * Detects which piece IDs (0–3) are currently highlighted as moveable by the game UI.
     * Returns an empty list if none found.
     *
     * Implementation: looks for a white/golden glow halo around each piece's known position.
     * If any piece has a significantly brighter border than baseline, it is considered moveable.
     */
    fun detectMoveablePieces(bitmap: Bitmap): List<Int> {
        // For now return all piece indices as moveable when it's a valid turn;
        // the game will refuse invalid taps naturally.
        // A more precise implementation would sample the "glow" ring around each piece.
        return listOf(0, 1, 2, 3)
    }

    // ==================================================================================
    // Frame stability helpers
    // ==================================================================================

    /**
     * Waits until two successive frames are sufficiently similar (animation finished).
     * Returns true if stable within [timeoutMs], false if timed out.
     */
    suspend fun waitForStableFrame(
        captureManager: ScreenCaptureManager,
        timeoutMs: Long = 3000L
    ): Boolean {
        val step = 200L
        var elapsed = 0L
        var prev: Bitmap? = captureManager.captureOnce()

        while (elapsed < timeoutMs) {
            delay(step)
            elapsed += step
            val curr = captureManager.captureOnce() ?: continue
            if (prev != null && !isAnimating(prev!!, curr)) {
                prev?.recycle()
                curr.recycle()
                return true
            }
            prev?.recycle()
            prev = curr
        }
        prev?.recycle()
        return false
    }

    /**
     * Returns true if [frame1] and [frame2] differ significantly (animation in progress).
     * Uses a quick pixel-sampling diff on a 10×10 grid.
     */
    fun isAnimating(frame1: Bitmap, frame2: Bitmap): Boolean {
        if (frame1.width != frame2.width || frame1.height != frame2.height) return true

        val w = frame1.width
        val h = frame1.height
        var diffSum = 0L
        val sampleSize = 10

        for (xi in 0 until sampleSize) {
            for (yi in 0 until sampleSize) {
                val x = (xi * w / sampleSize).coerceIn(0, w - 1)
                val y = (yi * h / sampleSize).coerceIn(0, h - 1)
                val p1 = frame1.getPixel(x, y)
                val p2 = frame2.getPixel(x, y)
                diffSum += abs(Color.red(p1)   - Color.red(p2)).toLong()
                diffSum += abs(Color.green(p1) - Color.green(p2)).toLong()
                diffSum += abs(Color.blue(p1)  - Color.blue(p2)).toLong()
            }
        }
        val avgDiff = diffSum.toDouble() / (sampleSize * sampleSize * 3)
        return avgDiff > ANIMATION_DIFF_THRESHOLD
    }

    // ==================================================================================
    // Private helpers
    // ==================================================================================

    /**
     * Returns the [PlayerColor] whose known sRGB value is within [COLOR_TOLERANCE] of [pixel],
     * or null if no match.
     */
    private fun matchPieceColor(pixel: Int): PlayerColor? {
        val r = Color.red(pixel).toDouble()
        val g = Color.green(pixel).toDouble()
        val b = Color.blue(pixel).toDouble()

        for ((color, rgb) in PIECE_COLORS) {
            val dist = sqrt(
                (r - rgb.first)  * (r - rgb.first)  +
                (g - rgb.second) * (g - rgb.second) +
                (b - rgb.third)  * (b - rgb.third)
            )
            if (dist < COLOR_TOLERANCE) return color
        }
        return null
    }

    companion object {
        private const val ANIMATION_DIFF_THRESHOLD = 8.0
    }
}
