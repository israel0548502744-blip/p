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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
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

private val SWATCHES get() = listOf("#FFFFFF" to tr("לבן"), "#000000" to tr("שחור"), "#6B7280" to tr("אפור"), "#E8D9C5" to tr("בז׳"), "#1E4DFF" to tr("כחול"), "#0B1F66" to tr("כחול כהה"), "#10B981" to tr("ירוק"), "#F472B6" to tr("ורוד"))

/** All censor settings — same options as the desktop settings panel. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsSection(s: CensorSettings, hasAudio: Boolean, enabled: Boolean, onChange: (CensorSettings) -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle(tr("את מי לצנזר"))
            Spacer(Modifier.height(10.dp))
            Segmented(listOf("female" to tr("נשים בלבד"), "everyone" to tr("כולם")), s.target, { onChange(s.copy(target = it)) }, enabled)
            if (s.target == "female") {
                Spacer(Modifier.height(8.dp))
                LabeledSlider(
                    tr("ודאות בזיהוי מגדר"), s.genderThreshold, { onChange(s.copy(genderThreshold = it)) }, tr("החלטה מהירה"), tr("רק בוודאות גבוהה"),
                    range = 51..99, suffix = "%", enabled = enabled,
                    hint = tr("אדם ייחשב לאישה או לגבר רק מעל רמת הוודאות הזו; מתחתיה הוא ייחשב \"לא בטוח\"."),
                )
                Text(tr("כשלא בטוח"), color = Palette.Ink100, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
                Segmented(listOf("censor" to tr("לצנזר (בטוח)"), "keep" to tr("לא לצנזר")), s.uncertainPolicy, { onChange(s.copy(uncertainPolicy = it)) }, enabled)
                Text(
                    tr("המגדר מוערך לפי הפנים ולא תמיד מדויק. אחרי העיבוד כדאי לעבור על רשימת האנשים — אפשר לשנות את ההחלטה לכל אחד ולעבד מחדש."),
                    color = Palette.Ink400, fontSize = 11.5.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 8.dp),
                )
            }

            Spacer(Modifier.height(18.dp))
            SectionTitle(tr("סגנון הכיסוי"))
            Spacer(Modifier.height(10.dp))
            Segmented(listOf("color" to tr("צבע אחיד"), "clothing" to tr("המשך הבגד")), s.fill, { onChange(s.copy(fill = it)) }, enabled)
            Text(
                if (s.fill == "clothing") tr("העור מתמלא בצבע הבגד שלידו, עם הצללות הגוף: זרוע הופכת לשרוול, מחשוף לצווארון. איפה שאין בגד ליד, משתמשים בצבע שנבחר למטה.")
                else tr("העור מכוסה בצבע אחיד, לפי הקווים של הגוף."),
                color = Palette.Ink400, fontSize = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 6.dp),
            )

            Spacer(Modifier.height(18.dp))
            SectionTitle(tr("צבע הצנזור"))
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
                        Text(name, color = if (selected) Palette.Text else Palette.Ink400, fontSize = 10.5.sp, modifier = Modifier.padding(top = 3.dp))
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
                label = { Text(tr("צבע מותאם אישית (HEX)")) },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Shield500, unfocusedBorderColor = Palette.Ink600),
            )
            ToggleRow(Icons.Filled.AutoAwesome, tr("ברק מונפש"), tr("ברק עדין שנע על המסכה במקום מילוי אחיד"), s.animated, { onChange(s.copy(animated = it)) }, enabled = enabled)

            Spacer(Modifier.height(18.dp))
            SectionTitle(tr("זיהוי"))
            LabeledSlider(tr("רגישות הזיהוי"), s.sensitivity, { onChange(s.copy(sensitivity = it)) }, tr("מדויק"), tr("לתפוס הכול"), enabled = enabled,
                hint = tr("ערך גבוה יותר מצנזר יותר עור גבולי ואזורים שזוהו בוודאות נמוכה."))
            LabeledSlider(tr("רכות שולי המסכה"), s.softness, { onChange(s.copy(softness = it)) }, tr("קצה חד"), tr("קצה רך"), enabled = enabled)
            ToggleRow(Icons.Filled.LocalFireDepartment, tr("צנזור אגרסיבי"), tr("מסכות רחבות יותר, זיהוי בכל פריים, סריקה מפוצלת לאנשים קטנים וגיבוי זיהוי עור לפי צבע"),
                s.aggressive, { onChange(s.copy(aggressive = it)) }, accent = Color(0xFFFB923C), enabled = enabled)
            ToggleRow(Icons.Filled.Face, tr("לכסות גם פנים"), tr("לכלול את עור הפנים במסכה"), s.includeFace, { onChange(s.copy(includeFace = it)) }, enabled = enabled)

            Spacer(Modifier.height(18.dp))
            SectionTitle(tr("ביצועים"))
            Spacer(Modifier.height(10.dp))
            Segmented(listOf("quality" to tr("איכות מרבית"), "balanced" to tr("מאוזן"), "fast" to tr("מהיר")), s.speed, { onChange(s.copy(speed = it)) }, enabled && !s.aggressive)
            Text(
                if (s.aggressive) tr("במצב אגרסיבי כל פריים נבדק.") else tr("מעקב תנועה (optical flow) מעביר את המסכות בין הפריימים שנבדקו."),
                color = Palette.Ink400, fontSize = 11.5.sp, modifier = Modifier.padding(top = 6.dp),
            )

            Spacer(Modifier.height(18.dp))
            SectionTitle(tr("ייצוא"))
            Spacer(Modifier.height(10.dp))
            Segmented(listOf("high" to tr("איכות גבוהה"), "balanced" to tr("רגיל"), "small" to tr("קובץ קטן")), s.quality, { onChange(s.copy(quality = it)) }, enabled)
            Text(tr("קובץ MP4 · H.264 · רזולוציה וקצב פריימים מקוריים"), color = Palette.Ink300, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            if (hasAudio) ToggleRow(Icons.AutoMirrored.Filled.VolumeUp, tr("שמירת השמע"), tr("פס הקול המקורי יישאר בסרטון"), s.keepAudio, { onChange(s.copy(keepAudio = it)) }, enabled = enabled)
        }
    }
}
