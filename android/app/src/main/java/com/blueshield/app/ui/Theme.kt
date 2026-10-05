package com.blueshield.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Same palette as the desktop app (ink + shield blue). */
object Palette {
    val Ink950 = Color(0xFF07090F)
    val Ink900 = Color(0xFF0B0E16)
    val Ink850 = Color(0xFF0F131D)
    val Ink800 = Color(0xFF141925)
    val Ink700 = Color(0xFF1C2231)
    val Ink600 = Color(0xFF262E40)
    val Ink400 = Color(0xFF5D6782)
    val Ink300 = Color(0xFF8F98B2)
    val Ink200 = Color(0xFFC1C7D8)
    val Ink100 = Color(0xFFE6E9F2)
    val Shield300 = Color(0xFF9DB6FF)
    val Shield400 = Color(0xFF6F93FF)
    val Shield500 = Color(0xFF3F6BFF)
    val Shield600 = Color(0xFF1E4DFF)
    val Shield700 = Color(0xFF1638C4)
    val Emerald = Color(0xFF34D399)
    val Amber = Color(0xFFFCD34D)
    val Danger = Color(0xFFF87171)
    val Border = Color(0x14FFFFFF)
}

@Composable
fun BlueShieldTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Shield600,
            onPrimary = Color.White,
            secondary = Palette.Shield400,
            background = Palette.Ink950,
            onBackground = Palette.Ink100,
            surface = Palette.Ink900,
            onSurface = Palette.Ink100,
            surfaceVariant = Palette.Ink800,
            onSurfaceVariant = Palette.Ink300,
            outline = Palette.Ink600,
            error = Palette.Danger,
        ),
        content = content,
    )
}
