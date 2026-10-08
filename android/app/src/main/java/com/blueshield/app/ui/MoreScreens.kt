package com.blueshield.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blueshield.app.BuildInfo
import com.blueshield.app.engine.Processor
import com.blueshield.core.CensorSettings
import com.blueshield.core.gender.Override

/** Full settings page (from the menu, or "Edit" on the home screen). */
@Composable
fun SettingsScreen(settings: CensorSettings, enabled: Boolean, onChange: (CensorSettings) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("הגדרות", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text("ההגדרות נשמרות אוטומטית וחלות על כל סרטון ותמונה.", color = Palette.Ink300, fontSize = 13.sp)
        if (!enabled) Text("עיבוד פועל כרגע — השינויים יחולו בעיבוד הבא.", color = Palette.Amber, fontSize = 12.5.sp)
        SettingsSection(settings, hasAudio = true, enabled = true, onChange = onChange)
        EngineSection()
    }
}

/** A one-glance summary of the current settings with a button to the settings page. */
@Composable
fun SettingsSummary(s: CensorSettings, onEdit: () -> Unit) {
    Panel(Modifier.fillMaxWidth().clickable(onClick = onEdit)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Color(CensorSettings.parseColor(s.color) or -0x1000000))
                    .border(1.dp, Palette.Border, RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("הגדרות צנזור", color = Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    (if (s.target == "female") "נשים בוגרות בלבד" else "כולם") + " · " +
                        (if (s.uncertainPolicy == "censor") "בספק: לצנזר" else "בספק: לא לצנזר") + " · " +
                        when (s.speed) { "quality" -> "איכות מרבית"; "fast" -> "מהיר"; else -> "מאוזן" },
                    color = Palette.Ink300, fontSize = 12.sp,
                )
            }
            Icon(Icons.Filled.Settings, null, tint = Palette.Ink300)
        }
    }
}

@Composable
fun AboutScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("אודות BlueShield", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text("גרסה ${BuildInfo.version}", color = Palette.Ink300, fontSize = 13.sp)
        val context = androidx.compose.ui.platform.LocalContext.current
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("עדכון")
                Text(
                    "מוריד בדפדפן את הגרסה האחרונה שעברה את כל הבדיקות. בסיום ההורדה לוחצים על הקובץ ואז \"עדכון\": " +
                        "ההגדרות נשמרות, ואין צורך למחוק את האפליקציה.",
                    color = Palette.Ink100, fontSize = 13.sp, lineHeight = 18.sp,
                )
                PrimaryButton("עדכון לגרסה האחרונה", Icons.Filled.Download) {
                    runCatching {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(BuildInfo.LATEST_APK)))
                    }
                }
            }
        }
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("דוח אבחון")
                Text(
                    "מה האפליקציה עשתה בעיבוד האחרון: מעבדים, זמנים, מקודד ושגיאות. בלי תמונות ובלי סרטונים. " +
                        "אם משהו יצא לא תקין, שלחו את הדוח למפתח.",
                    color = Palette.Ink100, fontSize = 13.sp, lineHeight = 18.sp,
                )
                GlassOutlined(
                    onClick = {
                        val text = "BlueShield ${BuildInfo.version} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}\n" +
                            (com.blueshield.app.engine.Accelerators.report(context)?.let { "\n$it\n" } ?: "") + "\n" +
                            com.blueshield.app.engine.Breadcrumbs.tail(200)
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(android.content.Intent.EXTRA_TEXT, text)
                        runCatching { context.startActivity(android.content.Intent.createChooser(send, "שליחת דוח אבחון")) }
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp), shape = RoundedCornerShape(12.dp),
                ) { Text("שליחת דוח אבחון") }
            }
        }
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("איך זה עובד")
                for (line in listOf(
                    "האפליקציה מזהה אנשים ועוקבת אחריהם לאורך הסרטון; כל אדם מסווג לפי הפנים שלו (שני מודלים, וההחלטה מתקבלת על פני פריימים רבים).",
                    "כברירת מחדל מצונזרות רק נשים בוגרות. ילדות, גברים וכל מי שסימנתם \"אל תצנזר\" נשארים כמו שהם.",
                    "העור החשוף של כל מי שמצונזרת מכוסה — זרועות, כתפיים, גב חשוף ומחשוף נמוך מסוף הגרון ומטה. הפנים והגרון נשארים גלויים.",
                    "טקסט וכתוביות שעל המסך אף פעם לא מכוסים.",
                    "לא בטוח? ברירת המחדל הבטוחה היא לצנזר. אפשר לשנות את ההחלטה לכל אדם ולעבד מחדש בלי לנתח שוב.",
                )) Text("• $line", color = Palette.Ink100, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("פרטיות")
                Text("הכול רץ על הטלפון הזה. סרטונים ותמונות אף פעם לא מועלים.", color = Palette.Ink100, fontSize = 13.sp)
            }
        }
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle("מודלים")
                for (m in listOf(
                    "MediaPipe Selfie Multiclass, BlazeFace (Apache-2.0)",
                    "YOLOX-tiny, Megvii (Apache-2.0)",
                    "MobileSAM (Apache-2.0)",
                    "מודל מגדר " + ltr("FaceRes, @vladmandic/human-models (MIT)"),
                    "face-api.js AgeGenderNet, @vladmandic/face-api (MIT)",
                    "NudeNet v3 (MIT)",
                )) Text(m, color = Palette.Ink300, fontSize = 12.sp)
            }
        }
    }
}

/** A picked photo: preview, settings summary, and the start button. */
@Composable
fun PhotoHome(preview: Bitmap, name: String, settings: CensorSettings, busy: Boolean, onEditSettings: () -> Unit, onClear: () -> Unit, onStart: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Image(
                preview.asImageBitmap(), null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio(preview.width.toFloat() / preview.height).clip(RoundedCornerShape(16.dp)),
            )
            Panel(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Image, null, tint = Palette.Shield400)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, color = Color.White, fontSize = 14.sp, maxLines = 1)
                        Text("תמונה · תצוגה מקדימה " + ltr("${preview.width}×${preview.height}"), color = Palette.Ink400, fontSize = 12.sp)
                    }
                    Text("החלפה", color = Palette.Shield400, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onClear).padding(6.dp))
                }
            }
            SettingsSummary(settings, onEditSettings)
        }
        BottomBar {
            if (busy) CircularProgressIndicator(color = Palette.Shield400)
            else PrimaryButton("צנזור התמונה", Icons.Filled.PlayArrow, onClick = onStart)
        }
    }
}

/** The censored photo: compare, people list with overrides, save / share. */
@Composable
fun PhotoResultScreen(
    result: Processor.PhotoResult, draft: Map<Int, Override>, thumbnail: (Int) -> Bitmap?, busy: Boolean,
    onOverride: (Int, Override) -> Unit, onApply: () -> Unit, onSave: () -> Unit, onShare: () -> Unit, onAdjust: () -> Unit, onNew: () -> Unit,
    saved: Boolean, settings: CensorSettings,
) {
    var showOriginal by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Emerald.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.CheckCircle, null, tint = Palette.Emerald)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("התמונה צונזרה.", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text("בדקו את התוצאה, ואז שמרו או שתפו.", color = Palette.Ink300, fontSize = 12.5.sp)
            }
        }
        Segmented(listOf(false to "מצונזר", true to "מקור"), showOriginal, { showOriginal = it })
        val bmp = if (showOriginal) result.original else result.censored
        Image(
            bmp.asImageBitmap(), null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height).clip(RoundedCornerShape(16.dp)),
        )
        if (busy) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.Shield400) }
        PrimaryButton(if (saved) "נשמרה בגלריה" else "שמירה בגלריה", Icons.Filled.Download, enabled = !saved, onClick = onSave)
        GlassOutlined(onClick = onShare, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Filled.Share, null)
            Spacer(Modifier.width(8.dp))
            Text("שיתוף")
        }
        PeoplePanel(result.people, draft, thumbnail, onOverride, onApply, settings)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassOutlined(onClick = onAdjust, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) { Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(6.dp)); Text("כוונון") }
            GlassOutlined(onClick = onNew, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) { Icon(Icons.Filled.Image, null); Spacer(Modifier.width(6.dp)); Text("תמונה חדשה") }
        }
        PrivacyPill()
    }
}

/** Several photos at once: progress, the latest censored photo, and a summary when done. */
@Composable
fun BatchScreen(b: com.blueshield.app.AppViewModel.BatchState, onClose: () -> Unit, onMore: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                    .background((if (b.finished) Palette.Emerald else Palette.Shield500).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                if (b.finished) Icon(Icons.Filled.CheckCircle, null, tint = Palette.Emerald)
                else CircularProgressIndicator(color = Palette.Shield400, strokeWidth = 2.5.dp, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    if (b.finished) "התמונות צונזרו ונשמרו בגלריה." else "מצנזר תמונות…",
                    color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (b.finished) "בתיקייה BlueShield בגלריה." else (b.current ?: ""),
                    color = Palette.Ink300, fontSize = 12.5.sp, maxLines = 1,
                )
            }
        }
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row {
                    Text("הושלמו", color = Palette.Ink300, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(ltr("${b.done} / ${b.total}"), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { if (b.total == 0) 0f else b.done.toFloat() / b.total },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = Palette.Shield400, trackColor = Palette.Ink800,
                )
                Text("נשמרו בגלריה: ${b.saved}", color = Palette.Ink200, fontSize = 13.sp)
                if (b.failed.isNotEmpty()) {
                    Text("לא הצליחו (${b.failed.size}): " + b.failed.joinToString(", "), color = Palette.Amber, fontSize = 12.5.sp)
                }
            }
        }
        b.last?.let { bmp ->
            Image(
                bmp.asImageBitmap(), null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height).clip(RoundedCornerShape(16.dp)),
            )
        }
        if (b.finished) {
            PrimaryButton("סיום", Icons.Filled.CheckCircle, onClick = onClose)
            GlassOutlined(onClick = onMore, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Filled.Image, null)
                Spacer(Modifier.width(8.dp))
                Text("עוד תמונות")
            }
        } else {
            GlassOutlined(onClick = onClose, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(16.dp)) {
                Text("עצירה (מה שכבר נשמר נשאר בגלריה)")
            }
        }
        PrivacyPill()
    }
}
