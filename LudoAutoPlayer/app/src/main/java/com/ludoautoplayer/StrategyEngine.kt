package com.ludoautoplayer

import com.ludoautoplayer.models.BoardPosition
import com.ludoautoplayer.models.GameState
import com.ludoautoplayer.models.Piece
import com.ludoautoplayer.models.PlayerColor

/**
 * StrategyEngine selects the best piece to move given the current [GameState].
 *
 * Priority system (high → low):
 *  1. Win move  — piece can reach the center (position ≥ 57 after move)
 *  2. Capture   — land exactly on an opponent's piece on a non-safe square
 *  3. Safe square — move lands the piece on a safe square
 *  4. Advance furthest piece (AGGRESSIVE) / advance safest piece (DEFENSIVE)
 *  5. Bring-out — dice == 6, piece is in home base
 *  6. Fallback  — any valid moveable piece (highest path index first)
 */
class StrategyEngine {

    enum class Strategy { AGGRESSIVE, DEFENSIVE }

    var strategy: Strategy = Strategy.AGGRESSIVE

    // ==================================================================================
    // Public API
    // ==================================================================================

    /**
     * Returns the [Piece] the bot should move, or null if there are no valid moves.
     */
    fun selectBestMove(state: GameState, botColor: PlayerColor): Piece? {
        val validMoves = getValidMoves(state, botColor)
        if (validMoves.isEmpty()) return null

        val boardMapper = BoardMapper()

        // --- Priority 1: Win move -----------------------------------------------------
        val winMove = validMoves.firstOrNull { canReachHome(it, state.diceValue) }
        if (winMove != null) return winMove

        // --- Priority 2: Capture opponent piece ---------------------------------------
        val captureMove = validMoves.firstOrNull { canCapture(it, state, boardMapper) }
        if (captureMove != null) return captureMove

        // --- Priority 3: Land on a safe square ----------------------------------------
        val safeMove = validMoves.firstOrNull { wouldLandOnSafeSquare(it, state.diceValue) }
        if (safeMove != null) return safeMove

        // --- Priority 4: Advance piece ------------------------------------------------
        val activePieces = validMoves.filter {
            !it.position.isHomeBase && !it.hasWon
        }
        if (activePieces.isNotEmpty()) {
            return when (strategy) {
                Strategy.AGGRESSIVE ->
                    // Move the piece furthest along the path (highest index)
                    activePieces.maxByOrNull { it.position.index }
                Strategy.DEFENSIVE ->
                    // Move the piece closest to home base (lowest index) to spread risk
                    activePieces.minByOrNull { it.position.index }
            }
        }

        // --- Priority 5: Bring out from home (only when dice == 6) --------------------
        if (state.diceValue == 6) {
            val bringOutMove = validMoves.firstOrNull { it.position.isHomeBase }
            if (bringOutMove != null) return bringOutMove
        }

        // --- Priority 6: Fallback — any valid piece ------------------------------------
        return validMoves.maxByOrNull { it.position.index }
    }

    // ==================================================================================
    // Private helpers
    // ==================================================================================

    /**
     * Returns all pieces belonging to [botColor] that are currently moveable
     * (i.e., [Piece.isMoveable] == true and piece has not already won).
     */
    private fun getValidMoves(state: GameState, botColor: PlayerColor): List<Piece> {
        return state.pieces.filter { piece ->
            piece.color == botColor && piece.isMoveable && !piece.hasWon
        }
    }

    /**
     * Returns true if moving [piece] by [diceValue] steps would land it on the center
     * (i.e., piece reaches or passes position 57 on its color's path).
     *
     * Home-column indices are encoded as 52..56 (step 0..4); index 57 is the center entry.
     */
    private fun canReachHome(piece: Piece, diceValue: Int): Boolean {
        if (piece.position.isHomeBase || piece.position.isCenter) return false
        val currentIndex = piece.position.index
        // Home-column range: 52–56. Index 57 means center.
        return (currentIndex + diceValue) >= 57
    }

    /**
     * Returns true if moving [piece] by state.diceValue steps would land it exactly on
     * a square occupied by at least one opponent piece, and that square is not safe.
     */
    private fun canCapture(piece: Piece, state: GameState, boardMapper: BoardMapper): Boolean {
        if (piece.position.isHomeBase || piece.position.isHomeColumn || piece.position.isCenter) {
            return false
        }
        val targetIndex = (piece.position.index + state.diceValue) % 52
        // Must not be a safe square
        if (boardMapper.isSafeSquare(targetIndex)) return false

        // Check if any opponent piece occupies targetIndex
        return state.pieces.any { other ->
            other.color != piece.color &&
            !other.hasWon &&
            !other.position.isHomeBase &&
            !other.position.isHomeColumn &&
            !other.position.isCenter &&
            other.position.index == targetIndex
        }
    }

    /**
     * Returns true if moving [piece] by [diceValue] steps would land it on a safe square.
     */
    private fun wouldLandOnSafeSquare(piece: Piece, diceValue: Int): Boolean {
        if (piece.position.isHomeBase || piece.position.isHomeColumn || piece.position.isCenter) {
            return false
        }
        val targetIndex = (piece.position.index + diceValue) % 52
        return BoardMapper.SAFE_SQUARES.contains(targetIndex)
    }
}
