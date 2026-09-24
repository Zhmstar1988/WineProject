package com.wine.dispenser.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val WineRed = Color(0xFF8B0000)
private val WineGold = Color(0xFFC5A572)

private val LightColorScheme = lightColorScheme(
    primary = WineRed,
    secondary = WineGold
)

@Composable
fun WineDispenserTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColorScheme, content = content)
}
