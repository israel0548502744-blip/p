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

/** Same palette as the desktop app (ink + shield blue), plus the tints of the glass background. */
object Palette {
    val Ink950 = Color(0xFF060814)
    val Ink900 = Color(0xFF0A0E1C)
    val Ink850 = Color(0xFF0F1426)
    val Ink800 = Color(0xFF151B30)
    val Ink700 = Color(0xFF1E2640)
    val Ink600 = Color(0xFF2A3352)
    val Ink400 = Color(0xFF7A84A3)
    val Ink300 = Color(0xFFA3ABC4)
    val Ink200 = Color(0xFFCCD2E3)
    val Ink100 = Color(0xFFEEF1F8)
    val Shield300 = Color(0xFFA8C0FF)
    val Shield400 = Color(0xFF7499FF)
    val Shield500 = Color(0xFF4A73FF)
    val Shield600 = Color(0xFF2F57F5)
    val Shield700 = Color(0xFF1F3DC9)
    val Violet = Color(0xFF8B5CF6)
    val Cyan = Color(0xFF22D3EE)
    val Emerald = Color(0xFF34D399)
    val Amber = Color(0xFFFCD34D)
    val Danger = Color(0xFFF87171)
    val Border = Color(0x1FFFFFFF)
}

/**
 * The "glass" look: a translucent white tint with a light rim that is brighter at the top, as if
 * lit from above. Laid over the slowly moving colour background it reads as frosted glass.
 */
object Glass {
    val Fill = Color(0x14FFFFFF)
    val FillStrong = Color(0x24FFFFFF)
    val Highlight = Color(0x0FFFFFFF)
    val RimTop = Color(0x40FFFFFF)
    val RimBottom = Color(0x0AFFFFFF)
    val Bar = Color(0x8C0A0E1C)
    val Accent = Brush.linearGradient(listOf(Color(0xFF5B83FF), Color(0xFF6E5BFF)))
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
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B1030), Palette.Ink950, Color(0xFF070B1A))))) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val r = maxOf(w, h)
            fun glow(color: Color, x: Float, y: Float, radius: Float, alpha: Float) = drawCircle(
                Brush.radialGradient(listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * 0.35f), Color.Transparent), Offset(x, y), radius),
                radius, Offset(x, y),
            )
            glow(Palette.Shield500, w * (0.85f + 0.08f * cos(t)), h * (0.12f + 0.05f * sin(t)), r * 0.55f, 0.42f)
            glow(Palette.Violet, w * (0.1f + 0.1f * sin(t + 1f)), h * (0.48f + 0.06f * cos(t * 2)), r * 0.5f, 0.30f)
            glow(Palette.Cyan, w * (0.7f + 0.12f * sin(t + 2.5f)), h * (0.92f + 0.04f * cos(t + 1f)), r * 0.45f, 0.20f)
        }
        content()
    }
}

@Composable
fun BlueShieldTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Shield500,
            onPrimary = Color.White,
            secondary = Palette.Shield400,
            background = Palette.Ink950,
            onBackground = Palette.Ink100,
            surface = Palette.Ink900,
            onSurface = Palette.Ink100,
            surfaceVariant = Palette.Ink800,
            onSurfaceVariant = Palette.Ink300,
            surfaceContainer = Palette.Ink800,
            outline = Color(0x33FFFFFF),
            error = Palette.Danger,
        ),
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
