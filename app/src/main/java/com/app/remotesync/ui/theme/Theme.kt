package com.app.remotesync.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val primaryBlue = Color(0xFF1565C0)
private val primaryDark = Color(0xFF90CAF9)

private val LightColors = lightColorScheme(
    primary = primaryBlue,
    onPrimary = Color.White,
    secondary = Color(0xFF0288D1),
    background = Color(0xFFF5F5F5),
    surface = Color.White,
    onBackground = Color(0xFF212121),
    onSurface = Color(0xFF212121)
)

private val DarkColors = darkColorScheme(
    primary = primaryDark,
    onPrimary = Color(0xFF003258),
    secondary = Color(0xFF4FC3F7),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onBackground = Color(0xFFE0E0E0),
    onSurface = Color(0xFFE0E0E0)
)

@Composable
fun RemoteSyncTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
