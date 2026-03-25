package com.ludoautoplayer

import android.graphics.PointF
import com.ludoautoplayer.models.PlayerColor

/**
 * BoardMapper provides pixel coordinates (as screen ratios) for every logical position
 * on a standard Ludo King board.
 *
 * Coordinate system assumption:
 *   - Screen: 1080 x 2400 px (common Android phone)
 *   - The square Ludo board occupies approximately the center 80% of the screen width,
 *     vertically centred (roughly y=0.15 to y=0.87 of screen height).
 *   - Board origin (top-left corner): ratioX = 0.10, ratioY = 0.15
 *   - Each of the 15 board cells spans ~0.054 of screen width (= 0.80 / 15 ≈ 0.0533).
 *
 * Path layout (standard Ludo):
 *   The 52-square outer path starts at index 0 = Red's entry square (column 1, row 6
 *   from the top of the board, i.e. the leftmost square of the middle row on the left side).
 *   Squares are numbered clockwise.
 *
 * Color-specific start positions (index where a piece enters the path):
 *   RED   → index  0
 *   GREEN → index 13
 *   YELLOW→ index 26
 *   BLUE  → index 39
 */
class BoardMapper {

    companion object {
        // Safe squares (cannot be captured on these)
        val SAFE_SQUARES: Set<Int> = setOf(0, 8, 13, 21, 26, 34, 39, 47)

        // Board geometry constants (ratios of screen dimensions)
        private const val BOARD_LEFT   = 0.10f   // left edge of board
        private const val BOARD_TOP    = 0.15f   // top edge of board
        private const val CELL         = 0.054f  // width of one cell (board_width/15 ≈ 0.054)

        // Helper: convert grid cell (col, row) to ratio-based PointF
        // col and row are 0-based indices into the 15×15 board grid.
        // Returns centre of the cell.
        private fun cell(col: Int, row: Int): Pair<Float, Float> {
            val rx = BOARD_LEFT + (col + 0.5f) * CELL
            val ry = BOARD_TOP  + (row + 0.5f) * CELL
            return Pair(rx, ry)
        }

        // ---- 52-square outer path (clockwise from Red start) -------------------------
        // Layout on the 15×15 board grid (0-indexed):
        //
        //   LEFT  side (going down):  col 1, rows 6..8  → indices 0..2  (but only row 6 = start)
        //   ... (full mapping below)

        private val PATH_RATIOS: List<Pair<Float, Float>> = listOf(
            // Left side, going down (col 1, rows 6 → 8)
            cell(1, 6),   // 0  Red start
            cell(1, 7),   // 1
            cell(1, 8),   // 2
            // Bottom-left corner, going right (row 8, cols 2 → 5)
            cell(2, 8),   // 3
            cell(3, 8),   // 4
            cell(4, 8),   // 5
            cell(5, 8),   // 6
            // Enter bottom zone (col 6, rows 8 → 7)
            cell(6, 8),   // 7
            cell(6, 7),   // 8  SAFE
            // Bottom side, going right (row 13, cols 1 → 5... via col 6 route)
            // Actually row 8 is mid-left; continue standard Ludo path:
            cell(6, 9),   // 9
            cell(6, 10),  // 10
            cell(6, 11),  // 11
            cell(6, 12),  // 12
            // Bottom side turning right (row 13, cols 6 → 8)
            cell(6, 13),  // 13 Green start
            cell(7, 13),  // 14
            cell(8, 13),  // 15
            // Right side of bottom zone going up (col 8, rows 12 → 9)
            cell(8, 12),  // 16
            cell(8, 11),  // 17
            cell(8, 10),  // 18
            cell(8, 9),   // 19
            cell(8, 8),   // 20
            cell(8, 7),   // 21 SAFE
            // Right side going right (row 8 → col 9,10,11,12,13)
            cell(9, 8),   // 22
            cell(10, 8),  // 23
            cell(11, 8),  // 24
            cell(12, 8),  // 25
            cell(13, 8),  // 26 Yellow start
            cell(13, 7),  // 27
            cell(13, 6),  // 28
            // Top-right corner going left (row 6, cols 12 → 9)
            cell(12, 6),  // 29
            cell(11, 6),  // 30
            cell(10, 6),  // 31
            cell(9, 6),   // 32
            cell(8, 6),   // 33
            cell(8, 5),   // 34 SAFE
            // Top side going left (row 1..2, via col 8 upward)
            cell(8, 4),   // 35
            cell(8, 3),   // 36
            cell(8, 2),   // 37
            cell(8, 1),   // 38
            cell(8, 0),   // 39 Blue start – but top row is 0
            cell(7, 0),   // 40 (using row 0 for top edge)
            cell(6, 0),   // 41
            // Left side of top zone going down (col 6, rows 1 → 4)
            cell(6, 1),   // 42
            cell(6, 2),   // 43
            cell(6, 3),   // 44
            cell(6, 4),   // 45
            cell(6, 5),   // 46
            cell(6, 6),   // 47 SAFE
            // Complete the path back to start (row 6, cols 5 → 2)
            cell(5, 6),   // 48
            cell(4, 6),   // 49
            cell(3, 6),   // 50
            cell(2, 6),   // 51
        )

        // ---- Home base positions (4 slots per color) ---------------------------------
        // Each color has a 5×5 "home" quadrant; the 4 piece slots are inside that quadrant.

        private val HOME_BASE_RATIOS: Map<PlayerColor, List<Pair<Float, Float>>> = mapOf(
            PlayerColor.RED    to listOf(cell(1,1), cell(2,1), cell(1,2), cell(2,2)),
            PlayerColor.GREEN  to listOf(cell(12,1), cell(13,1), cell(12,2), cell(13,2)),  // Adjusted to GREEN quadrant
            PlayerColor.YELLOW to listOf(cell(12,12), cell(13,12), cell(12,13), cell(13,13)),
            PlayerColor.BLUE   to listOf(cell(1,12), cell(2,12), cell(1,13), cell(2,13)),
        )

        // ---- Home column positions (5 steps from entry to center, per color) ----------
        // Step 0 = first home-column square; step 4 = square just before center.

        private val HOME_COLUMN_RATIOS: Map<PlayerColor, List<Pair<Float, Float>>> = mapOf(
            PlayerColor.RED    to listOf(cell(1,7), cell(2,7), cell(3,7), cell(4,7), cell(5,7)),
            PlayerColor.GREEN  to listOf(cell(7,13), cell(7,12), cell(7,11), cell(7,10), cell(7,9)),
            PlayerColor.YELLOW to listOf(cell(13,7), cell(12,7), cell(11,7), cell(10,7), cell(9,7)),
            PlayerColor.BLUE   to listOf(cell(7,1), cell(7,2), cell(7,3), cell(7,4), cell(7,5)),
        )

        // ---- Center position ---------------------------------------------------------
        private val CENTER_RATIO = cell(7, 7)

        // ---- Dice button position (typically bottom-center of screen) ----------------
        private const val DICE_RATIO_X = 0.50f
        private const val DICE_RATIO_Y = 0.72f
    }

    // ==================================================================================
    // Public API
    // ==================================================================================

    /**
     * Returns the screen pixel coordinate for a main-path square.
     * @param index 0..51
     */
    fun getPathCoordinate(index: Int, screenW: Int, screenH: Int): PointF {
        require(index in 0..51) { "Path index $index out of range 0..51" }
        val (rx, ry) = PATH_RATIOS[index]
        return PointF(rx * screenW, ry * screenH)
    }

    /**
     * Returns the screen pixel coordinate for a piece's home-base slot.
     * @param color Piece color
     * @param slot  0..3
     */
    fun getHomeBaseCoordinate(color: PlayerColor, slot: Int, screenW: Int, screenH: Int): PointF {
        require(slot in 0..3) { "Home base slot $slot out of range 0..3" }
        val (rx, ry) = HOME_BASE_RATIOS[color]!![slot]
        return PointF(rx * screenW, ry * screenH)
    }

    /**
     * Returns the screen pixel coordinate for a home-column step.
     * @param color Piece color
     * @param step  0..4 (0 = first home-column square)
     */
    fun getHomeColumnCoordinate(color: PlayerColor, step: Int, screenW: Int, screenH: Int): PointF {
        require(step in 0..4) { "Home column step $step out of range 0..4" }
        val (rx, ry) = HOME_COLUMN_RATIOS[color]!![step]
        return PointF(rx * screenW, ry * screenH)
    }

    /** Returns the screen pixel coordinate for the center (winning) square. */
    fun getCenterCoordinate(screenW: Int, screenH: Int): PointF {
        return PointF(CENTER_RATIO.first * screenW, CENTER_RATIO.second * screenH)
    }

    /**
     * Returns the approximate screen pixel coordinate for the dice roll button.
     * This changes per player turn in Ludo King; this returns a reasonable default.
     */
    fun getDiceButtonCoordinate(screenW: Int, screenH: Int): PointF {
        return PointF(DICE_RATIO_X * screenW, DICE_RATIO_Y * screenH)
    }

    /**
     * Returns the coordinate for the given [position] on a [color] piece.
     * Handles home base, home column, center, and normal path positions.
     */
    fun getCoordinateForPosition(
        index: Int,
        isHomeBase: Boolean,
        isHomeColumn: Boolean,
        isCenter: Boolean,
        color: PlayerColor,
        pieceSlot: Int,
        screenW: Int,
        screenH: Int
    ): PointF {
        return when {
            isCenter   -> getCenterCoordinate(screenW, screenH)
            isHomeBase -> getHomeBaseCoordinate(color, pieceSlot.coerceIn(0, 3), screenW, screenH)
            isHomeColumn -> {
                val step = (index - 52).coerceIn(0, 4)
                getHomeColumnCoordinate(color, step, screenW, screenH)
            }
            else -> getPathCoordinate(index.coerceIn(0, 51), screenW, screenH)
        }
    }

    /**
     * Checks whether the given path index is a safe square.
     */
    fun isSafeSquare(index: Int): Boolean = index in SAFE_SQUARES
}
