package com.blueshield.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Hd
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MovieCreation
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blueshield.app.UiPrefs
import com.blueshield.core.CensorSettings

/** What the tools page opens. */
class ToolActions(
    val photo: () -> Unit,
    val video: () -> Unit,
    val batch: () -> Unit,
    val queue: () -> Unit,
    val presets: () -> Unit,
    val privacy: () -> Unit,
    val guide: () -> Unit,
    val faq: () -> Unit,
    val report: () -> Unit,
    val about: () -> Unit,
)

/** The "כלים" tab: every tool of the app in one place, and the settings that are changed most often. */
@Composable
fun ToolsScreen(settings: CensorSettings, ui: UiPrefs, onSettings: (CensorSettings) -> Unit, onUi: ((UiPrefs) -> UiPrefs) -> Unit, a: ToolActions) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 6.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(tr("כלים"), color = Palette.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(tr("כל מה שהאפליקציה יודעת לעשות, במקום אחד."), color = Palette.Ink300, fontSize = 13.5.sp)

        SectionHeading(tr("צנזור"))
        ToolGrid(
            listOf(
                Tool(Icons.Outlined.Image, tr("צנזור תמונה"), tr("תמונה אחת, עם השוואה לפני ואחרי"), a.photo),
                Tool(Icons.Outlined.MovieCreation, tr("צנזור סרטון"), tr("כולל חיתוך ובחירת אנשים"), a.video),
                Tool(Icons.Outlined.Layers, tr("עריכה קבוצתית"), tr("כמה תמונות ביחד, נשמרות בגלריה"), a.batch),
                Tool(Icons.AutoMirrored.Outlined.PlaylistPlay, tr("תור סרטונים"), tr("כמה סרטונים ברצף, ברקע"), a.queue),
            ),
        )

        SectionHeading(tr("הגדרות מהירות"))
        Panel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                QuickCell(Icons.Outlined.CheckCircle, tr("שמירה אוטומטית"), if (ui.autoSave) tr("מופעל") else tr("כבוי"), ui.autoSave, Modifier.weight(1f)) {
                    onUi { it.copy(autoSave = !it.autoSave) }
                }
                Box(Modifier.width(1.dp).height(36.dp).background(Palette.Border))
                QuickCell(Icons.Outlined.Description, tr("פורמט"), formatLabel(ui.photoFormat), false, Modifier.weight(1f)) {
                    onUi { it.copy(photoFormat = when (it.photoFormat) { "original" -> "jpg"; "jpg" -> "png"; else -> "original" }) }
                }
                Box(Modifier.width(1.dp).height(36.dp).background(Palette.Border))
                QuickCell(Icons.Outlined.Hd, tr("איכות"), qualityLabel(settings.quality), false, Modifier.weight(1f)) {
                    onSettings(settings.copy(quality = when (settings.quality) { "high" -> "balanced"; "balanced" -> "small"; else -> "high" }))
                }
            }
        }
        ToggleRow(
            Icons.Outlined.TouchApp, tr("לשאול את מי לצנזר"),
            tr("האפליקציה מסמנת את האנשים שמצאה, ואתם בוחרים את מי לכסות לפני תחילת הצנזור."),
            ui.askWho, { v -> onUi { it.copy(askWho = v) } },
        )

        SectionHeading(tr("בתוך שלבי הצנזור"))
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                InlineTool(Icons.Outlined.ContentCut, tr("חיתוך סרטון"), tr("לצנזר ולשמור רק קטע מהסרטון"))
                InlineTool(Icons.Outlined.Brush, tr("מכחול ידני"), tr("להוסיף כיסוי או למחוק כיסוי בתמונה, באצבע"))
                InlineTool(Icons.Outlined.Schedule, tr("טווח זמן לכל אדם"), tr("לצנזר מישהי רק בחלק מהסרטון"))
            }
        }

        SectionHeading(tr("עוד"))
        ToolGrid(
            listOf(
                Tool(Icons.Outlined.GridView, tr("טמפלטים"), tr("סטים של הגדרות, בלחיצה"), a.presets),
                Tool(Icons.Outlined.Shield, tr("בדיקת פרטיות"), tr("מה נשאר בטלפון, ומה לא"), a.privacy),
                Tool(Icons.AutoMirrored.Outlined.MenuBook, tr("מדריך למתחילים"), tr("איך זה עובד, בשלוש דקות"), a.guide),
                Tool(Icons.AutoMirrored.Outlined.HelpOutline, tr("שאלות נפוצות"), tr("תשובות קצרות"), a.faq),
                Tool(Icons.Outlined.Flag, tr("דיווח על טעות"), tr("משהו לא כוסה, או כוסה בטעות"), a.report),
                Tool(Icons.Outlined.Info, tr("אודות ועדכון"), tr("גרסה, עדכון ורישיונות"), a.about),
            ),
        )
    }
}

private class Tool(val icon: ImageVector, val title: String, val sub: String, val onClick: () -> Unit)

@Composable
private fun ToolGrid(tools: List<Tool>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in tools.chunked(2)) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (t in row) ToolCard(t, Modifier.weight(1f))
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ToolCard(t: Tool, modifier: Modifier) {
    Column(
        modifier.heightIn(min = 118.dp).glass(RoundedCornerShape(18.dp)).clickable(onClick = t.onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Shield500.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) { Icon(t.icon, null, tint = Palette.Shield300, modifier = Modifier.size(22.dp)) }
        Text(t.title, color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(t.sub, color = Palette.Ink300, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun InlineTool(icon: ImageVector, title: String, sub: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Palette.Teal, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = Palette.Ink100, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(sub, color = Palette.Ink400, fontSize = 12.sp)
        }
    }
}

@Composable
private fun QuickCell(icon: ImageVector, label: String, value: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = if (on) Palette.Shield400 else Palette.Ink300, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = Palette.Ink200, fontSize = 12.sp, maxLines = 1)
        Text(value, color = if (on) Palette.Shield400 else Palette.Ink100, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** The crown's page: what "Pro" holds. During the launch everything is open to everyone, for free. */
@Composable
fun ProScreen() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(84.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFFC59BFF), Palette.Shield500, Palette.Shield700))),
            contentAlignment = Alignment.Center,
        ) { Icon(CrownIcon, null, tint = Color.White, modifier = Modifier.size(44.dp)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("BlueShield ", color = Palette.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            ProPill()
        }
        Text(
            tr("בתקופת ההשקה כל התכונות פתוחות לכולם, בחינם. אין צורך בחשבון ואין תשלום."),
            color = Palette.Ink200, fontSize = 14.5.sp, lineHeight = 21.sp, textAlign = TextAlign.Center,
        )
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (f in listOf(
                    tr("עריכה קבוצתית: עד 100 תמונות בבת אחת"),
                    tr("תור סרטונים שמעובד ברקע"),
                    tr("סרטונים ארוכים, בלי הגבלת אורך"),
                    tr("מכחול ידני לתיקון התמונה"),
                    tr("טמפלטים שמורים בלי הגבלה"),
                )) Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(Palette.Teal), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Check, null, tint = Color(0xFF0B2B2A), modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(f, color = Palette.Ink100, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(tr("פתוח"), color = Palette.Teal, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Text(tr("הצנזור עצמו תמיד נשאר במכשיר, גם בגרסת Pro."), color = Palette.Ink400, fontSize = 12.5.sp, textAlign = TextAlign.Center)
    }
}

/** The name in the greeting and the side menu, and an e-mail shown under it. Both stay on this phone. */
@Composable
fun ProfileDialog(name: String, email: String, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var n by remember { mutableStateOf(name) }
    var e by remember { mutableStateOf(email) }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Palette.Shield400, unfocusedBorderColor = Palette.Border,
        focusedTextColor = Palette.Text, unfocusedTextColor = Palette.Text, cursorColor = Palette.Shield400,
        focusedLabelColor = Palette.Shield300, unfocusedLabelColor = Palette.Ink300,
    )
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Palette.Ink850, shape = RoundedCornerShape(26.dp),
        title = { Text(tr("הפרופיל שלי"), color = Palette.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(n, { n = it.take(40) }, label = { Text(tr("שם")) }, singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    e, { e = it.take(80) }, label = { Text(tr("מייל (לא חובה)")) }, singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
                Text(tr("השם מופיע בברכה ובתפריט. הפרטים נשמרים רק בטלפון הזה ולא נשלחים לשום מקום."), color = Palette.Ink400, fontSize = 12.sp, lineHeight = 16.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(n.trim(), e.trim()) }) { Text(tr("שמירה")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("ביטול")) } },
    )
}

/** The "+" menu: a new photo or a new video, as two large choices above the bottom bar. */
@Composable
fun PlusMenu(onPhoto: () -> Unit, onVideo: () -> Unit, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onDismiss), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 96.dp).clip(RoundedCornerShape(24.dp)).background(Palette.Ink850)
                .border(1.dp, Palette.Border, RoundedCornerShape(24.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(tr("מה לצנזר?"), color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((scene, icon, label) in listOf(
                    Triple(Scene.TEAL, Icons.Outlined.Image, tr("צנזור תמונה")),
                    Triple(Scene.VIOLET, Icons.Outlined.MovieCreation, tr("צנזור סרטון")),
                )) Box(
                    Modifier.weight(1f).height(96.dp).clip(RoundedCornerShape(18.dp)).clickable(onClick = if (scene == Scene.TEAL) onPhoto else onVideo),
                    contentAlignment = Alignment.Center,
                ) {
                    Landscape(scene, Modifier.fillMaxSize())
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(icon, null, tint = Color.White, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.height(4.dp))
                        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
