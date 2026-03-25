package com.ludoautoplayer.models

data class Piece(
    val id: Int,                        // 0-3
    val color: PlayerColor,
    val position: BoardPosition,
    val isMoveable: Boolean = false,
    val hasWon: Boolean = false
)
