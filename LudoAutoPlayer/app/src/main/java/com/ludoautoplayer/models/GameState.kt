package com.ludoautoplayer.models

data class GameState(
    val activePlayer: PlayerColor?,
    val diceValue: Int,                 // -1 = not rolled yet
    val pieces: List<Piece>,
    val isAnimating: Boolean,
    val isBotTurn: Boolean,
    val turnSkipped: Boolean = false,
    val gameOver: Boolean = false,
    val bonusTurn: Boolean = false      // true if dice was 6
)
