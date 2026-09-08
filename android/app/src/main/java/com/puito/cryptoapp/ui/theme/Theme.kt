package com.puito.cryptoapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF0B90B),
    onPrimary = Color(0xFF0B0E11),
    background = Color(0xFF0B0E11),
    surface = Color(0xFF12161C),
    onBackground = Color(0xFFEAECEF),
    onSurface = Color(0xFFEAECEF),
    secondary = Color(0xFF848E9C),
)

@Composable
fun CryptoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}
