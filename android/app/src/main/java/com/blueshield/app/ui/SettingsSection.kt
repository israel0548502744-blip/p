package com.blueshield.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blueshield.core.CensorSettings

private val SWATCHES = listOf("#FFFFFF" to "White", "#000000" to "Black", "#6B7280" to "Gray", "#E8D9C5" to "Beige", "#1E4DFF" to "Blue", "#0B1F66" to "Navy", "#10B981" to "Green", "#F472B6" to "Pink")

/** All censor settings — same options as the desktop settings panel. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsSection(s: CensorSettings, hasAudio: Boolean, enabled: Boolean, onChange: (CensorSettings) -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("Who to censor")
            Spacer(Modifier.height(10.dp))
            Segmented(listOf("female" to "Women only", "everyone" to "Everyone"), s.target, { onChange(s.copy(target = it)) }, enabled)
            if (s.target == "female") {
                Spacer(Modifier.height(8.dp))
                LabeledSlider(
                    "Gender confidence", s.genderThreshold, { onChange(s.copy(genderThreshold = it)) }, "Decide quickly", "Must be very sure",
                    range = 51..99, suffix = "%", enabled = enabled,
                    hint = "A person counts as female or male only above this confidence; anyone below it is “unsure”.",
                )
                Text("When unsure", color = Palette.Ink100, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
                Segmented(listOf("censor" to "Censor (safe)", "keep" to "Don't censor"), s.uncertainPolicy, { onChange(s.copy(uncertainPolicy = it)) }, enabled)
                Text(
                    "Gender is estimated from faces and is not always right. Review the people list after processing — you can override anyone and re-render.",
                    color = Palette.Ink400, fontSize = 11.5.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 8.dp),
                )
            }

            Spacer(Modifier.height(18.dp))
            SectionTitle("Censor color")
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((c, name) in SWATCHES) {
                    val selected = s.color.equals(c, ignoreCase = true)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Color(android.graphics.Color.parseColor(c)))
                                .border(if (selected) 2.5.dp else 1.dp, if (selected) Palette.Shield400 else Palette.Border, RoundedCornerShape(11.dp))
                                .clickable(enabled = enabled) { onChange(s.copy(color = c)) },
                        )
                        Text(name, color = if (selected) Color.White else Palette.Ink400, fontSize = 10.5.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
            var hex by remember(s.color) { mutableStateOf(s.color) }
            OutlinedTextField(
                value = hex, enabled = enabled, singleLine = true,
                onValueChange = { v ->
                    hex = v
                    if (runCatching { CensorSettings.parseColor(v) }.isSuccess) onChange(s.copy(color = if (v.startsWith("#")) v.uppercase() else "#" + v.uppercase()))
                },
                label = { Text("Custom color (hex)") },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Shield500, unfocusedBorderColor = Palette.Ink600),
            )
            ToggleRow(Icons.Filled.AutoAwesome, "Animated shimmer", "Gentle moving sheen instead of a flat fill", s.animated, { onChange(s.copy(animated = it)) }, enabled = enabled)

            Spacer(Modifier.height(18.dp))
            SectionTitle("Detection")
            LabeledSlider("Detection sensitivity", s.sensitivity, { onChange(s.copy(sensitivity = it)) }, "Precise", "Catch everything", enabled = enabled,
                hint = "Higher values censor more borderline skin and lower-confidence regions.")
            LabeledSlider("Mask softness", s.softness, { onChange(s.copy(softness = it)) }, "Hard edge", "Feathered", enabled = enabled)
            ToggleRow(Icons.Filled.LocalFireDepartment, "Aggressive censorship", "Wider masks, every-frame detection, tiled scan for small people, colour-based skin backup",
                s.aggressive, { onChange(s.copy(aggressive = it)) }, accent = Color(0xFFFB923C), enabled = enabled)
            ToggleRow(Icons.Filled.Face, "Also cover faces", "Include facial skin in the mask", s.includeFace, { onChange(s.copy(includeFace = it)) }, enabled = enabled)

            Spacer(Modifier.height(18.dp))
            SectionTitle("Performance")
            Spacer(Modifier.height(10.dp))
            Segmented(listOf("quality" to "Max quality", "balanced" to "Balanced", "fast" to "Fast"), s.speed, { onChange(s.copy(speed = it)) }, enabled && !s.aggressive)
            Text(
                if (s.aggressive) "Aggressive mode always analyzes every frame." else "Optical-flow tracking carries masks between analyzed frames.",
                color = Palette.Ink400, fontSize = 11.5.sp, modifier = Modifier.padding(top = 6.dp),
            )

            Spacer(Modifier.height(18.dp))
            SectionTitle("Export")
            Spacer(Modifier.height(10.dp))
            Segmented(listOf("high" to "High", "balanced" to "Standard", "small" to "Small file"), s.quality, { onChange(s.copy(quality = it)) }, enabled)
            Text("MP4 · H.264 · original resolution & frame rate", color = Palette.Ink300, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            if (hasAudio) ToggleRow(Icons.Filled.VolumeUp, "Preserve audio", "Keep the original soundtrack", s.keepAudio, { onChange(s.copy(keepAudio = it)) }, enabled = enabled)
        }
    }
}
