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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Edit
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

/** Full settings page: how the app looks and behaves, then the censor settings and the processor. */
@Composable
fun SettingsScreen(
    settings: CensorSettings, enabled: Boolean, onChange: (CensorSettings) -> Unit,
    ui: com.blueshield.app.UiPrefs, onUi: ((com.blueshield.app.UiPrefs) -> com.blueshield.app.UiPrefs) -> Unit, onPresets: () -> Unit,
    onProfile: () -> Unit = {}, onAbout: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(tr("הגדרות"), color = Palette.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(tr("ההגדרות נשמרות אוטומטית וחלות על כל סרטון ותמונה."), color = Palette.Ink300, fontSize = 13.sp)
        if (!enabled) Text(tr("עיבוד פועל כרגע — השינויים יחולו בעיבוד הבא."), color = Palette.Amber, fontSize = 12.5.sp)
        Row(
            Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp)).clickable(onClick = onProfile).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(46.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Palette.Shield400, Palette.Shield700))),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Person, null, tint = Color.White) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(ui.name.ifBlank { tr("הפרופיל שלי") }, color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(ui.email.ifBlank { tr("שם ומייל, נשמרים רק בטלפון") }, color = Palette.Ink300, fontSize = 12.5.sp)
            }
            Icon(Icons.Filled.Edit, tr("עריכה"), tint = Palette.Ink300)
        }
        AppearanceSection(ui, onUi)
        BehaviourSection(ui, onUi, onPresets)
        SettingsSection(settings, hasAudio = true, enabled = true, onChange = onChange)
        EngineSection()
        GlassOutlined(onClick = onAbout, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Icon(Icons.Filled.Info, null); Spacer(Modifier.width(8.dp)); Text(tr("אודות ועדכון"))
        }
    }
}

private val ACCENTS = listOf(0xFF7C5CFF, 0xFF4A73FF, 0xFF0EA5E9, 0xFF14B8A6, 0xFF22C55E, 0xFFF59E0B, 0xFFEF4444, 0xFFEC4899, 0xFF8B5E3C, 0xFF64748B)

/** Theme, the app's colour (any colour: ready swatches or a hue/brightness pick), language and text size. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSection(ui: com.blueshield.app.UiPrefs, onUi: ((com.blueshield.app.UiPrefs) -> com.blueshield.app.UiPrefs) -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle(tr("מראה"))
            Segmented(listOf("dark" to tr("כהה"), "light" to tr("בהיר"), "system" to tr("לפי הטלפון")), ui.theme, { t -> onUi { it.copy(theme = t) } })
            Text(tr("צבע האפליקציה"), color = Palette.Ink100, fontSize = 14.sp)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (c in ACCENTS) {
                    val on = ui.accent == c
                    Box(
                        Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(c.toInt()))
                            .border(if (on) 3.dp else 1.dp, if (on) Palette.Text else Palette.Border, androidx.compose.foundation.shape.CircleShape)
                            .clickable { onUi { it.copy(accent = c) } },
                    )
                }
            }
            // any colour: hue and lightness
            val hsv = remember(ui.accent) { FloatArray(3).also { android.graphics.Color.colorToHSV(ui.accent.toInt(), it) } }
            LabeledSlider(tr("גוון"), hsv[0].toInt(), { h -> onUi { it.copy(accent = hsvColor(h.toFloat(), hsv[1].coerceAtLeast(0.35f), hsv[2])) } },
                tr("אדום"), tr("סגול"), range = 0..359)
            LabeledSlider(tr("בהירות"), (hsv[2] * 100).toInt(), { v -> onUi { it.copy(accent = hsvColor(hsv[0], hsv[1], v / 100f)) } },
                tr("כהה"), tr("בהיר"), range = 30..100, suffix = "%")
            Text(tr("שפה"), color = Palette.Ink100, fontSize = 14.sp)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (l in I18n.LANGS) {
                    val on = ui.language == l.code
                    Text(
                        l.name, color = if (on) Color.White else Palette.Ink200, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Palette.Shield500 else Palette.Veil)
                            .border(1.dp, Palette.Border, RoundedCornerShape(50)).clickable { onUi { it.copy(language = l.code) } }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            Text(tr("גודל הטקסט"), color = Palette.Ink100, fontSize = 14.sp)
            Segmented(listOf(0.9f to tr("קטן"), 1f to tr("רגיל"), 1.15f to tr("גדול"), 1.3f to tr("גדול מאוד")), ui.textScale, { v -> onUi { it.copy(textScale = v) } })
        }
    }
}

private fun hsvColor(h: Float, s: Float, v: Float): Long = (android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)).toLong() and 0xFFFFFFFFL)

/** How the app works: ask whom to censor, auto-save, photo format, setting sets. */
@Composable
private fun BehaviourSection(ui: com.blueshield.app.UiPrefs, onUi: ((com.blueshield.app.UiPrefs) -> com.blueshield.app.UiPrefs) -> Unit, onPresets: () -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(tr("אופן העבודה"))
            ToggleRow(
                Icons.Filled.CheckCircle, tr("לשאול את מי לצנזר"),
                tr("האפליקציה מסמנת את האנשים שמצאה, ואתם בוחרים את מי לכסות לפני תחילת הצנזור. בסרטון זה גם חוסך את זמן הצנזור של מי שלא נבחר."),
                ui.askWho, { v -> onUi { it.copy(askWho = v) } },
            )
            ToggleRow(
                Icons.Filled.Download, tr("שמירה אוטומטית בגלריה"),
                tr("כל תמונה וסרטון שמסיימים לצנזר נשמרים מיד בגלריה של הטלפון (תיקייה BlueShield)."),
                ui.autoSave, { v -> onUi { it.copy(autoSave = v) } },
            )
            Text(tr("פורמט התמונות"), color = Palette.Ink100, fontSize = 14.sp)
            Segmented(listOf("original" to tr("מקורי"), "jpg" to "JPG", "png" to "PNG"), ui.photoFormat, { f -> onUi { it.copy(photoFormat = f) } })
            Text(tr("\"מקורי\": תמונת PNG נשמרת כ-PNG, וכל השאר כ-JPG. PNG שומר בלי שום איבוד איכות, אבל הקובץ גדול יותר."),
                color = Palette.Ink400, fontSize = 11.5.sp, lineHeight = 15.sp)
            GlassOutlined(onClick = onPresets, modifier = Modifier.fillMaxWidth().height(46.dp)) { Text(tr("תבניות שמירה")) }
        }
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
                Text(tr("הגדרות צנזור"), color = Palette.Text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    (if (s.target == "female") tr("נשים בוגרות בלבד") else tr("כולם")) + " · " +
                        (if (s.uncertainPolicy == "censor") tr("בספק: לצנזר") else tr("בספק: לא לצנזר")) + " · " +
                        when (s.speed) { "quality" -> tr("איכות מרבית"); "fast" -> tr("מהיר"); else -> tr("מאוזן") },
                    color = Palette.Ink300, fontSize = 12.sp,
                )
            }
            Icon(Icons.Filled.Settings, null, tint = Palette.Ink300)
        }
    }
}

@Composable
fun AboutScreen(onWhatsNew: () -> Unit, onFaq: () -> Unit, onPrivacy: () -> Unit, onOnboarding: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(tr("גרסה {0}", ltr(BuildInfo.version)), color = Palette.Ink300, fontSize = 13.sp)
        val context = androidx.compose.ui.platform.LocalContext.current
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassOutlined(onClick = onWhatsNew, modifier = Modifier.weight(1f)) { Text(tr("מה חדש")) }
            GlassOutlined(onClick = onFaq, modifier = Modifier.weight(1f)) { Text(tr("שאלות נפוצות")) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassOutlined(onClick = onPrivacy, modifier = Modifier.weight(1f)) { Text(tr("פרטיות ואבטחה")) }
            GlassOutlined(onClick = onOnboarding, modifier = Modifier.weight(1f)) { Text(tr("הדרכה")) }
        }
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(tr("עדכון"))
                Text(
                    tr("מוריד בדפדפן את הגרסה האחרונה שעברה את כל הבדיקות. בסיום ההורדה לוחצים על הקובץ ואז \"עדכון\": ") +
                        tr("ההגדרות נשמרות, ואין צורך למחוק את האפליקציה."),
                    color = Palette.Ink100, fontSize = 13.sp, lineHeight = 18.sp,
                )
                PrimaryButton(tr("עדכון לגרסה האחרונה"), Icons.Filled.Download) {
                    runCatching {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(BuildInfo.LATEST_APK)))
                    }
                }
            }
        }
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(tr("דוח אבחון"))
                Text(
                    tr("מה האפליקציה עשתה בעיבוד האחרון: מעבדים, זמנים, מקודד ושגיאות. בלי תמונות ובלי סרטונים. ") +
                        tr("אם משהו יצא לא תקין, שלחו את הדוח למפתח."),
                    color = Palette.Ink100, fontSize = 13.sp, lineHeight = 18.sp,
                )
                GlassOutlined(
                    onClick = {
                        val text = "BlueShield ${BuildInfo.version} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}\n" +
                            (com.blueshield.app.engine.Accelerators.report(context)?.let { "\n$it\n" } ?: "") + "\n" +
                            com.blueshield.app.engine.Breadcrumbs.tail(200)
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(android.content.Intent.EXTRA_TEXT, text)
                        runCatching { context.startActivity(android.content.Intent.createChooser(send, tr("שליחת דוח אבחון"))) }
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp), shape = RoundedCornerShape(12.dp),
                ) { Text(tr("שליחת דוח אבחון")) }
            }
        }
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(tr("איך זה עובד"))
                for (line in listOf(
                    tr("האפליקציה מזהה אנשים ועוקבת אחריהם לאורך הסרטון; כל אדם מסווג לפי הפנים שלו (שני מודלים, וההחלטה מתקבלת על פני פריימים רבים)."),
                    tr("כברירת מחדל מצונזרות רק נשים בוגרות. ילדות, גברים וכל מי שסימנתם \"אל תצנזר\" נשארים כמו שהם."),
                    tr("העור החשוף של כל מי שמצונזרת מכוסה — זרועות, כתפיים, גב חשוף ומחשוף נמוך מסוף הגרון ומטה. הפנים והגרון נשארים גלויים."),
                    tr("טקסט וכתוביות שעל המסך אף פעם לא מכוסים."),
                    tr("אפשר לשלוח לאפליקציה תמונה או סרטון ישר מהגלריה או מווטסאפ: \"שיתוף\" ← BlueShield. כמה תמונות יחד מעובדות ברצף ונשמרות בגלריה."),
                    tr("לא בטוח? ברירת המחדל הבטוחה היא לצנזר. אפשר לשנות את ההחלטה לכל אדם ולעבד מחדש בלי לנתח שוב."),
                )) Text("• $line", color = Palette.Ink100, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle(tr("מודלים"))
                for (m in listOf(
                    "MediaPipe Selfie Multiclass, BlazeFace (Apache-2.0)",
                    "YOLOX-tiny, Megvii (Apache-2.0)",
                    "MobileSAM (Apache-2.0)",
                    tr("מודל מגדר ") + ltr("FaceRes, @vladmandic/human-models (MIT)"),
                    "face-api.js AgeGenderNet, @vladmandic/face-api (MIT)",
                    "YuNet, OpenCV Zoo (MIT)",
                    "Skin-Clothes-Hair Segmentation, Kazuhito Takahashi (MIT)",
                )) Text(m, color = Palette.Ink300, fontSize = 12.sp)
                // the full licence texts and copyright notices (Apache-2.0 and MIT ask for them to ship with the app)
                val context = androidx.compose.ui.platform.LocalContext.current
                var notices by remember { mutableStateOf<String?>(null) }
                Text(
                    if (notices == null) tr("הצגת הרישיונות וזכויות היוצרים המלאים") else tr("הסתרת הרישיונות"),
                    color = Palette.Ink100, fontSize = 13.sp,
                    modifier = Modifier.clickable {
                        notices = if (notices != null) null
                        else runCatching { context.assets.open("third_party_notices.txt").bufferedReader().use { it.readText() } }.getOrElse { "—" }
                    }.padding(vertical = 6.dp),
                )
                notices?.let {
                    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                        Text(it, color = Palette.Ink300, fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

/** A picked photo: preview, settings summary, and the start button. */
@Composable
fun PhotoHome(preview: Bitmap, name: String, settings: CensorSettings, busy: Boolean, onEditSettings: () -> Unit, onClear: () -> Unit, onStart: () -> Unit) {
    if (busy) {
        ProcessingView(
            percent = null, paused = false, title = tr("מעבד את התמונה שלך..."), sub = tr("מאתר אנשים, מגדר ועור"), stats = emptyList(),
            preview = preview, onPause = null, onCancel = null,
        )
        return
    }
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
                        Text(name, color = Palette.Text, fontSize = 14.sp, maxLines = 1)
                        Text(tr("תמונה · תצוגה מקדימה ") + ltr("${preview.width}×${preview.height}"), color = Palette.Ink400, fontSize = 12.sp)
                    }
                    Text(tr("החלפה"), color = Palette.Shield400, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onClear).padding(6.dp))
                }
            }
            SettingsSummary(settings, onEditSettings)
        }
        BottomBar {
            PrimaryButton(tr("צנזור התמונה"), null, trailingArrow = true, onClick = onStart)
        }
    }
}

/** The censored photo: the design's done step (compare, save, more editing, share), then the people and more. */
@Composable
fun PhotoResultScreen(
    result: Processor.PhotoResult, draft: Map<Int, Override>, thumbnail: (Int) -> Bitmap?, busy: Boolean,
    onOverride: (Int, Override) -> Unit, onApply: () -> Unit, onSave: () -> Unit, onShare: () -> Unit, onAdjust: () -> Unit, onNew: () -> Unit,
    saved: Boolean, settings: CensorSettings, extra: ExtraActions? = null, onProjects: () -> Unit = onNew,
) {
    var view by remember { mutableStateOf("compare") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DoneHeader()
        if (view == "compare") CompareImage(result.original, result.censored)
        else {
            val bmp = if (view == "original") result.original else result.censored
            Image(
                bmp.asImageBitmap(), null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height).clip(RoundedCornerShape(16.dp)),
            )
        }
        Segmented(listOf("compare" to tr("השוואה"), "censored" to tr("מצונזר"), "original" to tr("מקור")), view, { view = it })
        if (busy) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.Shield400) }
        ResultButtons(saved, onSave, onEdit = extra?.onBrush ?: onAdjust, onShare = onShare, onProjects = onProjects)
        SectionHeading(tr("עוד אפשרויות"))
        extra?.let { MoreActionsRow(it) }
        PeoplePanel(result.people, draft, thumbnail, onOverride, onApply, settings)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassOutlined(onClick = onAdjust, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) { Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(6.dp)); Text(tr("כוונון")) }
            GlassOutlined(onClick = onNew, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) { Icon(Icons.Filled.Image, null); Spacer(Modifier.width(6.dp)); Text(tr("תמונה חדשה")) }
        }
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
                    if (b.finished) (if (b.saved > 0) tr("התמונות צונזרו ונשמרו בגלריה.") else tr("העיבוד הסתיים.")) else tr("מצנזר תמונות…"),
                    color = Palette.Text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (b.finished) (if (b.saved > 0) tr("בתיקייה BlueShield בגלריה.") else "") else (b.current ?: ""),
                    color = Palette.Ink300, fontSize = 12.5.sp, maxLines = 1,
                )
            }
        }
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row {
                    Text(tr("הושלמו"), color = Palette.Ink300, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(ltr("${b.done} / ${b.total}"), color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { if (b.total == 0) 0f else b.done.toFloat() / b.total },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = Palette.Shield400, trackColor = Palette.Ink800,
                )
                Text(tr("נשמרו בגלריה: {0}", b.saved), color = Palette.Ink200, fontSize = 13.sp)
                if (b.failed.isNotEmpty()) {
                    Text(tr("לא הצליחו ({0}): {1}", b.failed.size, b.failed.joinToString(", ")), color = Palette.Amber, fontSize = 12.5.sp)
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
            PrimaryButton(tr("סיום"), Icons.Filled.CheckCircle, onClick = onClose)
            GlassOutlined(onClick = onMore, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Filled.Image, null)
                Spacer(Modifier.width(8.dp))
                Text(tr("עוד תמונות"))
            }
        } else {
            GlassOutlined(onClick = onClose, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(16.dp)) {
                Text(tr("עצירה (מה שכבר נשמר נשאר בגלריה)"))
            }
        }
        PrivacyPill()
    }
}
