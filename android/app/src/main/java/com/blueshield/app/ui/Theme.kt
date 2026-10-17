package com.blueshield.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * The app's colours. They are Compose state: the theme (dark / light) and the accent colour can be changed in the
 * settings and every screen redraws with them. The names keep their meaning in both themes: Ink100 is always the
 * strongest text, Ink950 the page background; Shield* are shades of the chosen accent colour.
 */
object Palette {
    var Ink950 by mutableStateOf(Color(0xFF070912))
    var Ink900 by mutableStateOf(Color(0xFF0C0F1D))
    var Ink850 by mutableStateOf(Color(0xFF111526))
    var Ink800 by mutableStateOf(Color(0xFF171C30))
    var Ink700 by mutableStateOf(Color(0xFF20263D))
    var Ink600 by mutableStateOf(Color(0xFF2C3350))
    var Ink400 by mutableStateOf(Color(0xFF7D84A0))
    var Ink300 by mutableStateOf(Color(0xFFA6ACC2))
    var Ink200 by mutableStateOf(Color(0xFFCDD1E0))
    var Ink100 by mutableStateOf(Color(0xFFEEF0F7))
    /** Headings and the strongest text (white in the dark theme). */
    var Text by mutableStateOf(Color.White)
    var Shield300 by mutableStateOf(Color(0xFFC4B5FF))
    var Shield400 by mutableStateOf(Color(0xFF9F8BFF))
    var Shield500 by mutableStateOf(Color(0xFF7C5CFF))
    var Shield600 by mutableStateOf(Color(0xFF6440F5))
    var Shield700 by mutableStateOf(Color(0xFF4F2FD0))
    val Violet = Color(0xFF8B5CF6)
    val Cyan = Color(0xFF22D3EE)
    var Emerald by mutableStateOf(Color(0xFF34D399))
    var EmeraldText by mutableStateOf(Color(0xFFA7F3D0))
    var Amber by mutableStateOf(Color(0xFFFCD34D))
    var Danger by mutableStateOf(Color(0xFFF87171))
    var DangerText by mutableStateOf(Color(0xFFFECACA))
    var Border by mutableStateOf(Color(0x1FFFFFFF))
    /** A faint layer over a card (rows, tracks): lighter in the dark theme, darker in the light one. */
    var Veil by mutableStateOf(Color(0x0AFFFFFF))
    var VeilStrong by mutableStateOf(Color(0x26FFFFFF))
    var dark by mutableStateOf(true)
        private set

    /** Default accent: violet, as in the design. */
    const val DEFAULT_ACCENT = 0xFF7C5CFF

    fun apply(dark: Boolean, accent: Color) {
        this.dark = dark
        if (dark) {
            Ink950 = Color(0xFF070912); Ink900 = Color(0xFF0C0F1D); Ink850 = Color(0xFF111526); Ink800 = Color(0xFF171C30)
            Ink700 = Color(0xFF20263D); Ink600 = Color(0xFF2C3350); Ink400 = Color(0xFF7D84A0); Ink300 = Color(0xFFA6ACC2)
            Ink200 = Color(0xFFCDD1E0); Ink100 = Color(0xFFEEF0F7); Text = Color.White
            Emerald = Color(0xFF34D399); EmeraldText = Color(0xFFA7F3D0); Amber = Color(0xFFFCD34D)
            Danger = Color(0xFFF87171); DangerText = Color(0xFFFECACA)
            Border = Color(0x1FFFFFFF); Veil = Color(0x0AFFFFFF); VeilStrong = Color(0x26FFFFFF)
            Glass.Fill = Color(0x12FFFFFF); Glass.FillStrong = Color(0x22FFFFFF); Glass.Highlight = Color(0x0CFFFFFF)
            Glass.RimTop = Color(0x33FFFFFF); Glass.RimBottom = Color(0x0AFFFFFF); Glass.Bar = Color(0xE60B0E1B)
        } else {
            Ink950 = Color(0xFFF3F2F9); Ink900 = Color(0xFFFFFFFF); Ink850 = Color(0xFFFFFFFF); Ink800 = Color(0xFFECEAF4)
            Ink700 = Color(0xFFE0DDEC); Ink600 = Color(0xFFC9C5DA); Ink400 = Color(0xFF6E6A84); Ink300 = Color(0xFF55516B)
            Ink200 = Color(0xFF3B3852); Ink100 = Color(0xFF1E1B30); Text = Color(0xFF14121F)
            Emerald = Color(0xFF0E9F6E); EmeraldText = Color(0xFF056A48); Amber = Color(0xFFB45309)
            Danger = Color(0xFFDC2626); DangerText = Color(0xFF991B1B)
            Border = Color(0x1A000000); Veil = Color(0x08000000); VeilStrong = Color(0x1A000000)
            Glass.Fill = Color(0xCCFFFFFF); Glass.FillStrong = Color(0xF2FFFFFF); Glass.Highlight = Color(0x66FFFFFF)
            Glass.RimTop = Color(0x14000000); Glass.RimBottom = Color(0x0D000000); Glass.Bar = Color(0xF2F3F2F9)
        }
        Shield500 = accent
        Shield300 = mix(accent, Color.White, if (dark) 0.55f else 0.35f)
        Shield400 = mix(accent, Color.White, if (dark) 0.3f else 0.12f)
        Shield600 = mix(accent, Color.Black, 0.15f)
        Shield700 = mix(accent, Color.Black, 0.3f)
        Glass.Accent = Brush.linearGradient(listOf(mix(accent, Color.White, 0.12f), mix(accent, Color.Black, 0.12f)))
    }

    private fun mix(a: Color, b: Color, t: Float) = Color(
        a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f,
    )
}

/**
 * The "glass" look: a translucent tint with a light rim that is brighter at the top, as if lit from above. In the
 * light theme the panels are nearly white cards with a thin dark rim.
 */
object Glass {
    var Fill by mutableStateOf(Color(0x12FFFFFF))
    var FillStrong by mutableStateOf(Color(0x22FFFFFF))
    var Highlight by mutableStateOf(Color(0x0CFFFFFF))
    var RimTop by mutableStateOf(Color(0x33FFFFFF))
    var RimBottom by mutableStateOf(Color(0x0AFFFFFF))
    var Bar by mutableStateOf(Color(0xE60B0E1B))
    var Accent: Brush by mutableStateOf(Brush.linearGradient(listOf(Color(0xFF8E73FF), Color(0xFF6D51E0))))
}

fun Modifier.glass(shape: Shape = RoundedCornerShape(24.dp), strong: Boolean = false, rim: Dp = 1.dp): Modifier =
    clip(shape)
        .background(if (strong) Glass.FillStrong else Glass.Fill)
        .background(Brush.verticalGradient(listOf(Glass.Highlight, Color.Transparent)))
        .border(rim, Brush.verticalGradient(listOf(Glass.RimTop, Glass.RimBottom)), shape)

/**
 * Deep navy with three large soft colour glows (blue, violet, cyan) drifting slowly behind the
 * glass panels. Drawn with radial gradients, so it needs no blur and works on every Android version.
 */
@Composable
fun GlassBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val t by rememberInfiniteTransition(label = "glow").animateFloat(
        0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(40_000, easing = LinearEasing), RepeatMode.Restart), label = "phase",
    )
    val dark = Palette.dark
    val top = if (dark) Color(0xFF0B0E22) else Color(0xFFF7F5FD)
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, Palette.Ink950, Palette.Ink950)))) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val r = maxOf(w, h)
            fun glow(color: Color, x: Float, y: Float, radius: Float, alpha: Float) = drawCircle(
                Brush.radialGradient(listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * 0.35f), Color.Transparent), Offset(x, y), radius),
                radius, Offset(x, y),
            )
            val k = if (dark) 1f else 0.45f
            glow(Palette.Shield500, w * (0.85f + 0.08f * cos(t)), h * (0.1f + 0.05f * sin(t)), r * 0.55f, 0.26f * k)
            glow(Palette.Shield700, w * (0.1f + 0.1f * sin(t + 1f)), h * (0.5f + 0.06f * cos(t * 2)), r * 0.5f, 0.18f * k)
            glow(Palette.Shield400, w * (0.7f + 0.12f * sin(t + 2.5f)), h * (0.95f + 0.04f * cos(t + 1f)), r * 0.45f, 0.10f * k)
        }
        content()
    }
}

/**
 * The Material theme over the current [Palette] (set with [Palette.apply] when the theme or accent setting
 * changes), with the text size factor from the settings.
 */
@Composable
fun BlueShieldTheme(textScale: Float = 1f, content: @Composable () -> Unit) {
    val dark = Palette.dark
    val base = Typography()
    val colors = if (dark) darkColorScheme(
        primary = Palette.Shield500, onPrimary = Color.White, secondary = Palette.Shield400,
        background = Palette.Ink950, onBackground = Palette.Ink100, surface = Palette.Ink900, onSurface = Palette.Ink100,
        surfaceVariant = Palette.Ink800, onSurfaceVariant = Palette.Ink300, surfaceContainer = Palette.Ink800,
        outline = Color(0x33FFFFFF), error = Palette.Danger,
    ) else lightColorScheme(
        primary = Palette.Shield500, onPrimary = Color.White, secondary = Palette.Shield600,
        background = Palette.Ink950, onBackground = Palette.Ink100, surface = Palette.Ink900, onSurface = Palette.Ink100,
        surfaceVariant = Palette.Ink800, onSurfaceVariant = Palette.Ink300, surfaceContainer = Palette.Ink900,
        outline = Color(0x33000000), error = Palette.Danger,
    )
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, density.fontScale * textScale),
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = base.copy(
                titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
                bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
            ),
            shapes = androidx.compose.material3.Shapes(
                extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(14.dp), medium = RoundedCornerShape(18.dp),
                large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp),
            ),
            content = content,
        )
    }
}
