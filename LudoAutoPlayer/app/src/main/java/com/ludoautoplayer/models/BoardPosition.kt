package com.ludoautoplayer.models

data class BoardPosition(
    val index: Int,          // 0-57 on path; -1 = home base; 58 = center/won
    val isHomeBase: Boolean = false,
    val isHomeColumn: Boolean = false,
    val isSafeSquare: Boolean = false,
    val isCenter: Boolean = false
)
