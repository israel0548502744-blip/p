package com.blueshield.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope

/** The painted night landscapes of the design: mountains, pine trees and a lake, drawn (no image files). */
enum class Scene { VIOLET, TEAL, LAKE }

@Composable
fun Landscape(scene: Scene, modifier: Modifier = Modifier) = Canvas(modifier) {
    when (scene) {
        Scene.VIOLET -> tile(
            sky = listOf(Color(0xFF7457E8), Color(0xFF4B36B0), Color(0xFF2A1F6B)),
            far = Color(0xFF8C74F0).copy(alpha = 0.45f), near = Color(0xFF241A5E), trees = Color(0xFF1A1347),
            glow = Color(0xFFC9B8FF), pines = false,
        )
        Scene.TEAL -> tile(
            sky = listOf(Color(0xFF3AA59B), Color(0xFF1F6F6C), Color(0xFF0F3B40)),
            far = Color(0xFF6CC9BC).copy(alpha = 0.40f), near = Color(0xFF0F4446), trees = Color(0xFF0A2E31),
            glow = Color(0xFFB8FFF1), pines = true,
        )
        Scene.LAKE -> lake()
    }
}

/** A ridge across the width: [peaks] are (x, y) as fractions of the area, joined into one filled shape. */
private fun DrawScope.ridge(peaks: List<Pair<Float, Float>>, color: Color, bottom: Float = size.height) {
    val p = Path()
    p.moveTo(0f, bottom)
    for ((x, y) in peaks) p.lineTo(x * size.width, y * size.height)
    p.lineTo(size.width, bottom)
    p.close()
    drawPath(p, color)
}

/** A pine: a thin trunk under three stacked triangles. */
private fun DrawScope.pine(x: Float, base: Float, h: Float, color: Color) {
    val w = h * 0.42f
    drawRect(color, Offset(x - h * 0.025f, base - h * 0.12f), Size(h * 0.05f, h * 0.12f))
    for (k in 0 until 3) {
        val top = base - h + k * h * 0.24f
        val bottom = base - h * 0.1f - (2 - k) * h * 0.2f
        val half = w * (0.55f + k * 0.22f) / 2f
        val p = Path()
        p.moveTo(x, top)
        p.lineTo(x - half, bottom)
        p.lineTo(x + half, bottom)
        p.close()
        drawPath(p, color)
    }
}

private fun DrawScope.tile(sky: List<Color>, far: Color, near: Color, trees: Color, glow: Color, pines: Boolean) {
    drawRect(Brush.verticalGradient(sky))
    // the light behind the hills
    drawCircle(
        Brush.radialGradient(listOf(glow.copy(alpha = 0.35f), Color.Transparent), Offset(size.width * 0.5f, size.height * 0.45f), size.width * 0.7f),
        size.width * 0.7f, Offset(size.width * 0.5f, size.height * 0.45f),
    )
    ridge(listOf(0f to 0.70f, 0.14f to 0.58f, 0.3f to 0.66f, 0.47f to 0.52f, 0.63f to 0.64f, 0.8f to 0.55f, 1f to 0.66f), far)
    // mist between the two ridges
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, glow.copy(alpha = 0.12f), Color.Transparent), startY = size.height * 0.6f, endY = size.height * 0.85f))
    ridge(listOf(0f to 0.82f, 0.2f to 0.72f, 0.38f to 0.8f, 0.58f to 0.7f, 0.78f to 0.79f, 1f to 0.72f), near)
    if (pines) {
        val b = size.height * 1.02f
        for ((x, h) in listOf(0.04f to 0.42f, 0.11f to 0.3f, 0.86f to 0.36f, 0.94f to 0.48f, 0.99f to 0.3f)) pine(x * size.width, b, h * size.height, trees)
    } else {
        ridge(listOf(0f to 0.9f, 0.25f to 0.84f, 0.5f to 0.9f, 0.75f to 0.83f, 1f to 0.88f), trees)
    }
    // darker at the bottom, where the text sits
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0x55000000)), startY = size.height * 0.45f))
}

/** The side menu's bottom: violet mountains over a still lake that mirrors them, pines on both shores. */
private fun DrawScope.lake() {
    val horizon = size.height * 0.58f
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF2C2163).copy(alpha = 0.85f), Color(0xFF3A2A7A)), endY = horizon))
    drawCircle(
        Brush.radialGradient(listOf(Color(0xFFB79CFF).copy(alpha = 0.35f), Color.Transparent), Offset(size.width * 0.45f, horizon), size.width * 0.6f),
        size.width * 0.6f, Offset(size.width * 0.45f, horizon),
    )
    val far = listOf(0f to 0.40f, 0.12f to 0.30f, 0.27f to 0.38f, 0.42f to 0.22f, 0.58f to 0.36f, 0.73f to 0.28f, 0.88f to 0.37f, 1f to 0.31f)
    val near = listOf(0f to 0.50f, 0.18f to 0.44f, 0.36f to 0.52f, 0.55f to 0.46f, 0.75f to 0.53f, 1f to 0.47f)
    ridge(far, Color(0xFF5B45B5).copy(alpha = 0.75f), horizon)
    ridge(near, Color(0xFF2E2370), horizon)
    // the lake: dark water with the ridges mirrored and faint ripples
    drawRect(Brush.verticalGradient(listOf(Color(0xFF2A2068), Color(0xFF120E2C)), startY = horizon), Offset(0f, horizon), Size(size.width, size.height - horizon))
    fun mirrored(peaks: List<Pair<Float, Float>>, color: Color) {
        val p = Path()
        p.moveTo(0f, horizon)
        for ((x, y) in peaks) p.lineTo(x * size.width, horizon + (horizon - y * size.height) * 0.6f)
        p.lineTo(size.width, horizon)
        p.close()
        drawPath(p, color)
    }
    mirrored(far, Color(0xFF5B45B5).copy(alpha = 0.25f))
    mirrored(near, Color(0xFF2E2370).copy(alpha = 0.5f))
    for (k in 0 until 7) {
        val y = horizon + (k + 1) * (size.height - horizon) / 9f
        val x0 = size.width * (0.2f + 0.09f * ((k * 37) % 5))
        drawLine(Color(0xFFC9B8FF).copy(alpha = 0.18f - k * 0.02f), Offset(x0, y), Offset(x0 + size.width * (0.18f + 0.03f * (k % 3)), y), strokeWidth = 1.5f)
    }
    val trees = Color(0xFF0E0A24)
    for ((x, h) in listOf(0.03f to 0.34f, 0.09f to 0.24f, 0.8f to 0.26f, 0.87f to 0.38f, 0.93f to 0.3f, 0.985f to 0.44f)) {
        pine(x * size.width, horizon + size.height * 0.03f, h * size.height, trees)
    }
    drawRect(trees, Offset(0f, horizon), Size(size.width * 0.12f, size.height * 0.03f))
    drawRect(trees, Offset(size.width * 0.78f, horizon), Size(size.width * 0.22f, size.height * 0.03f))
}
