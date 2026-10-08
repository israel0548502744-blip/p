package com.blueshield.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.glass(RoundedCornerShape(24.dp)), content = content)
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier, color = Palette.Ink300, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
}

@Composable
fun Logo() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Glass.Accent)
                .border(1.dp, Brush.verticalGradient(listOf(Color(0x66FFFFFF), Color(0x00FFFFFF))), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Shield, null, tint = Color.White, modifier = Modifier.size(21.dp)) }
        Spacer(Modifier.width(11.dp))
        Column {
            Text("BlueShield", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, letterSpacing = (-0.2).sp)
            Text("צנזור אוטומטי לסרטונים ולתמונות", color = Palette.Ink300, fontSize = 11.5.sp)
        }
    }
}

@Composable
fun PrivacyPill(modifier: Modifier = Modifier) {
    Row(
        modifier.glass(RoundedCornerShape(50)).background(Palette.Emerald.copy(alpha = 0.07f)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Lock, null, tint = Palette.Emerald, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text("הסרטונים והתמונות מעובדים במכשיר הזה ולא מועלים לשום מקום.", color = Color(0xFFA7F3D0), fontSize = 11.5.sp)
    }
}

@Composable
fun LabeledSlider(
    label: String, value: Int, onChange: (Int) -> Unit, left: String, right: String,
    range: IntRange = 0..100, suffix: String = "", hint: String? = null, enabled: Boolean = true,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Palette.Ink100, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(
                "$value$suffix", color = Palette.Ink200, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Glass.FillStrong).padding(horizontal = 9.dp, vertical = 3.dp),
            )
        }
        Slider(
            value = value.toFloat(), onValueChange = { onChange(it.toInt()) }, enabled = enabled,
            valueRange = range.first.toFloat()..range.last.toFloat(),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Palette.Shield400, inactiveTrackColor = Color(0x26FFFFFF)),
        )
        Row {
            Text(left, color = Palette.Ink400, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text(right, color = Palette.Ink400, fontSize = 11.sp)
        }
        if (hint != null) Text(hint, color = Palette.Ink400, fontSize = 11.5.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun ToggleRow(icon: ImageVector, label: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit, accent: Color = Palette.Shield500, enabled: Boolean = true) {
    val bg by animateColorAsState(if (checked) accent.copy(alpha = 0.12f) else Color(0x0AFFFFFF), label = "toggleBg")
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(18.dp)).background(bg)
            .border(1.dp, if (checked) accent.copy(alpha = 0.35f) else Palette.Border, RoundedCornerShape(18.dp))
            .clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(if (checked) accent.copy(alpha = 0.22f) else Glass.Fill),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = if (checked) accent else Palette.Ink300, modifier = Modifier.size(17.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = Palette.Ink100, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(description, color = Palette.Ink400, fontSize = 11.5.sp, lineHeight = 15.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = accent, checkedThumbColor = Color.White, uncheckedTrackColor = Color(0x33FFFFFF), uncheckedThumbColor = Color.White, uncheckedBorderColor = Color.Transparent),
        )
    }
}

@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0x1A000000))
            .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(16.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((value, label) in options) {
            val active = value == selected
            val fill by animateColorAsState(if (active) Color(0x33FFFFFF) else Color.Transparent, label = "segment")
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(fill)
                    .then(if (active) Modifier.border(1.dp, Brush.verticalGradient(listOf(Glass.RimTop, Glass.RimBottom)), RoundedCornerShape(12.dp)) else Modifier)
                    .clickable(enabled = enabled) { onSelect(value) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = if (active) Color.White else Palette.Ink300, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium) }
        }
    }
}

@Composable
fun StatusDot(color: Color) = Box(Modifier.size(8.dp).clip(CircleShape).background(color))

@Composable
fun Chip(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.glass(RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 9.dp)) {
        Text(label.uppercase(), color = Palette.Ink400, fontSize = 10.5.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, color = Palette.Ink100, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/**
 * Wraps numbers / Latin text in a left-to-right isolate so it keeps its order inside Hebrew (RTL)
 * text, e.g. "1920×1080", "12 / 300" or "0:05–0:12" would otherwise be shown reversed.
 */
fun ltr(s: String): String = "\u2066$s\u2069"

fun formatDuration(sec: Double): String {
    val s = sec.coerceAtLeast(0.0).toInt()
    val h = s / 3600
    val m = s % 3600 / 60
    val ss = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
}

fun formatEta(sec: Double?): String = when {
    sec == null -> "מחשב…"
    sec < 1 -> "כמעט סיימנו"
    sec < 60 -> "עוד ${sec.toInt() + 1} שנ׳"
    sec < 3600 -> "עוד ${(sec / 60).toInt()} דק׳ ${"%02d".format((sec % 60).toInt())} שנ׳"
    else -> "עוד ${(sec / 3600).toInt()} שע׳ ${((sec % 3600) / 60).toInt()} דק׳"
}

fun formatBytes(n: Long): String {
    if (n <= 0) return "—"
    val units = listOf("B", "KB", "MB", "GB")
    var v = n.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (v >= 100 || i == 0) "%.0f %s".format(v, units[i]) else "%.1f %s".format(v, units[i])
}

/** An outlined button made of clear glass, used for the secondary actions on every screen. */
@Composable
fun GlassOutlined(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(16.dp),
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) = androidx.compose.material3.OutlinedButton(
    onClick = onClick, modifier = modifier, enabled = enabled, shape = shape,
    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(containerColor = Glass.Fill, contentColor = Palette.Ink100),
    border = androidx.compose.foundation.BorderStroke(1.dp, Brush.verticalGradient(listOf(Glass.RimTop, Glass.RimBottom))),
    content = content,
)
