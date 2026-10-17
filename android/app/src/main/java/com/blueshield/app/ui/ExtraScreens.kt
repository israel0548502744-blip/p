package com.blueshield.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blueshield.app.BuildInfo
import com.blueshield.app.Preset
import com.blueshield.app.engine.BrushStroke
import com.blueshield.app.engine.VideoMeta
import com.blueshield.core.CensorSettings
import com.blueshield.core.pipeline.PersonSummary
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The extra actions under a result: WhatsApp, the brush (photos), deleting the original, reporting a mistake. */
class ExtraActions(
    val onWhatsApp: () -> Unit,
    val onReport: () -> Unit,
    val onDeleteOriginal: (() -> Unit)? = null,
    val onBrush: (() -> Unit)? = null,
    val showReport: Boolean = true,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoreActionsRow(a: ExtraActions) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        a.onBrush?.let { SmallAction(Icons.Filled.Brush, tr("תיקון במכחול"), it) }
        SmallAction(Icons.Filled.Send, tr("וואטסאפ"), a.onWhatsApp)
        a.onDeleteOriginal?.let { SmallAction(Icons.Filled.DeleteForever, tr("מחיקת המקור"), it) }
        if (a.showReport) SmallAction(Icons.Filled.Flag, tr("דיווח על טעות"), a.onReport)
    }
}

@Composable
private fun SmallAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Palette.Veil).border(1.dp, Palette.Border, RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Palette.Shield400, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Palette.Ink100, fontSize = 13.sp)
    }
}

/** Original and censored photo in one frame, split by a handle that is dragged sideways. */
@Composable
fun CompareImage(original: Bitmap, censored: Bitmap, modifier: Modifier = Modifier) {
    var split by remember { mutableFloatStateOf(0.5f) }
    var width by remember { mutableStateOf(1) }
    Box(
        modifier.fillMaxWidth().aspectRatio(censored.width.toFloat() / censored.height).clip(RoundedCornerShape(16.dp))
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) {
                detectTapGestures { split = (it.x / width).coerceIn(0f, 1f) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, drag -> change.consume(); split = (split + drag / width).coerceIn(0f, 1f) }
            },
    ) {
        Image(censored.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        Image(
            original.asImageBitmap(), null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().drawWithContent { clipRect(right = size.width * split) { this@drawWithContent.drawContent() } },
        )
        val density = LocalDensity.current
        Box(
            Modifier.fillMaxHeight().width(3.dp).offset { IntOffset((width * split - with(density) { 1.5.dp.toPx() }).roundToInt(), 0) }
                .background(Color.White),
        )
        Box(
            Modifier.align(Alignment.CenterStart).offset { IntOffset((width * split - with(density) { 18.dp.toPx() }).roundToInt(), 0) }
                .size(36.dp).clip(CircleShape).background(Color.White),
            contentAlignment = Alignment.Center,
        ) { Text("⇆", color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        Text(tr("מקור"), color = Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
            .clip(RoundedCornerShape(50)).background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 3.dp))
        Text(tr("מצונזר"), color = Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            .clip(RoundedCornerShape(50)).background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

/**
 * "Ask whom to censor": the people found, each with a tick (ticked as the automatic choice would be). Nothing
 * is drawn until the choice is confirmed.
 */
@Composable
fun ChooseScreen(
    people: List<PersonSummary>, thumbnail: (Int) -> Bitmap?, busy: Boolean,
    onConfirm: (Map<Int, Boolean>) -> Unit, onCancel: () -> Unit, video: Boolean,
) {
    val pick = remember(people) { mutableStateMapOf<Int, Boolean>().apply { people.forEach { put(it.id, it.censored) } } }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("את מי לצנזר?"), color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                tr("סמנו את מי לכסות. הסימון ההתחלתי הוא הבחירה האוטומטית. רק אחרי האישור מתחיל הצנזור."),
                color = Palette.Ink300, fontSize = 13.sp, lineHeight = 18.sp,
            )
            if (people.isEmpty()) Text(tr("לא זוהו אנשים."), color = Palette.Ink300, fontSize = 14.sp)
            for (p in people) {
                val on = pick[p.id] == true
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(if (on) Palette.Shield500.copy(alpha = 0.14f) else Palette.Veil)
                        .border(1.dp, if (on) Palette.Shield400.copy(alpha = 0.6f) else Palette.Border, RoundedCornerShape(16.dp))
                        .clickable { pick[p.id] = !on }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Ink700), contentAlignment = Alignment.Center) {
                        val bmp = thumbnail(p.id)
                        if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Icon(Icons.Filled.Person, null, tint = Palette.Ink400)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(personLabel(p), color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        if (video) Text(ltr("${formatDuration(p.startSec)}–${formatDuration(p.endSec)}"), color = Palette.Ink400, fontSize = 12.sp)
                        if (!p.child && (p.age ?: 99.0) < 18.0) Text(tr("הגיל לא ודאי, כדאי לבדוק"), color = Palette.Amber, fontSize = 12.sp)
                    }
                    Checkbox(on, { pick[p.id] = it }, colors = CheckboxDefaults.colors(checkedColor = Palette.Shield500))
                }
            }
        }
        BottomBar {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val n = pick.values.count { it }
                if (busy) androidx.compose.material3.CircularProgressIndicator(color = Palette.Shield400, modifier = Modifier.align(Alignment.CenterHorizontally))
                else PrimaryButton(if (n == 0) tr("בלי צנזור") else tr("צנזור ({0})", n), Icons.Filled.Check) { onConfirm(pick.toMap()) }
                GlassOutlined(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(tr("ביטול")) }
            }
        }
    }
}

fun personLabel(p: PersonSummary): String {
    val g = when {
        p.child && p.gender == "female" -> tr("ילדה")
        p.child -> tr("ילד")
        p.gender == "female" -> tr("אישה")
        p.gender == "male" -> tr("גבר")
        else -> tr("לא בטוח")
    }
    return "#${p.id}  $g" + (p.age?.let { " · " + tr("גיל כ-{0}", it.toInt()) } ?: "")
}

/** Several videos picked together: censored one after another in the background, with the same settings. */
@Composable
fun QueueScreen(queue: List<VideoMeta>, settings: CensorSettings, onRemove: (VideoMeta) -> Unit, onStart: () -> Unit, onClear: () -> Unit, onEditSettings: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr("תור סרטונים ({0})", queue.size), color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                tr("הסרטונים יצונזרו אחד אחרי השני, גם כשהמסך כבוי. בסוף תופיע הודעה, והם יחכו ב\"פעילות\"."),
                color = Palette.Ink300, fontSize = 13.sp, lineHeight = 18.sp,
            )
            for ((i, m) in queue.withIndex()) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Veil).border(1.dp, Palette.Border, RoundedCornerShape(14.dp)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${i + 1}", color = Palette.Shield400, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(26.dp))
                    Icon(Icons.Filled.Movie, null, tint = Palette.Ink300)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.name, color = Palette.Text, fontSize = 14.sp, maxLines = 1)
                        Text(ltr(formatDuration(m.durationSec) + " · ${m.width}×${m.height} · " + formatBytes(m.sizeBytes)), color = Palette.Ink400, fontSize = 12.sp)
                    }
                    IconButton(onClick = { onRemove(m) }) { Icon(Icons.Filled.Close, tr("הסרה"), tint = Palette.Ink300) }
                }
            }
            SettingsSummary(settings, onEditSettings)
        }
        BottomBar {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(tr("צנזור כל הסרטונים"), Icons.Filled.PlayArrow, enabled = queue.isNotEmpty(), onClick = onStart)
                GlassOutlined(onClick = onClear, modifier = Modifier.fillMaxWidth()) { Text(tr("ביטול")) }
            }
        }
    }
}

/**
 * Fixing a photo by hand: paint to cover what was missed, or to take away cover that isn't needed. The strokes are
 * applied to the full-size photo when confirmed.
 */
@Composable
fun BrushScreen(censored: Bitmap, colour: Color, initial: List<BrushStroke>, onApply: (List<BrushStroke>) -> Unit, onCancel: () -> Unit) {
    val strokes = remember { mutableStateListOf<BrushStroke>().apply { addAll(initial) } }
    val baked = remember { initial.size }
    var erase by remember { mutableStateOf(false) }
    var size by remember { mutableFloatStateOf(0.035f) }
    var area by remember { mutableStateOf(IntSize(1, 1)) }
    val current = remember { mutableStateListOf<Offset>() }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr("תיקון במכחול"), color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(tr("מעבירים אצבע על מקום שצריך לכסות, או על כיסוי מיותר במצב \"מחיקה\"."), color = Palette.Ink300, fontSize = 12.5.sp)
        Segmented(listOf(false to tr("הוספת כיסוי"), true to tr("מחיקת כיסוי")), erase, { erase = it })
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(censored.width.toFloat() / censored.height, matchHeightConstraintsFirst = censored.height > censored.width)
                    .clip(RoundedCornerShape(12.dp)).onSizeChanged { area = it }
                    .pointerInput(erase, size) {
                        detectDragGestures(
                            onDragStart = { current.clear(); current += it },
                            onDrag = { ch, _ -> ch.consume(); current += ch.position },
                            onDragEnd = {
                                if (current.isNotEmpty()) strokes += BrushStroke(current.map { it.x / area.width to it.y / area.height }, size, erase)
                                current.clear()
                            },
                        )
                    },
            ) {
                Image(censored.asImageBitmap(), null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize()) {
                    fun draw(points: List<Offset>, r: Float, er: Boolean) {
                        if (points.isEmpty()) return
                        val path = Path().apply { moveTo(points[0].x, points[0].y); points.drop(1).forEach { lineTo(it.x, it.y) }; if (points.size == 1) lineTo(points[0].x + 0.1f, points[0].y) }
                        drawPath(path, if (er) Color(0x99FF4D4D) else colour, style = Stroke(width = r * 2, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    for (s in strokes.drop(baked)) draw(s.points.map { Offset(it.first * this.size.width, it.second * this.size.height) }, s.radius * this.size.width, s.erase)
                    draw(current, size * this.size.width, erase)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("גודל"), color = Palette.Ink200, fontSize = 13.sp)
            androidx.compose.material3.Slider(
                value = size, onValueChange = { size = it }, valueRange = 0.008f..0.09f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                colors = androidx.compose.material3.SliderDefaults.colors(activeTrackColor = Palette.Shield400, thumbColor = Palette.Shield300),
            )
            IconButton(onClick = { if (strokes.size > 0) strokes.removeAt(strokes.lastIndex) }, enabled = strokes.isNotEmpty()) {
                Icon(Icons.AutoMirrored.Filled.Undo, tr("ביטול פעולה"), tint = Palette.Ink200)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassOutlined(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(tr("ביטול")) }
            androidx.compose.material3.Button(
                onClick = { onApply(strokes.toList()) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Palette.Shield500, contentColor = Color.White),
            ) { Icon(Icons.Filled.Check, null); Spacer(Modifier.width(6.dp)); Text(tr("החלה")) }
        }
    }
}

/** "Report a mistake": the user picks what goes into the message, then sends it with any app. */
@Composable
fun ReportDialog(hasResult: Boolean, onSend: (String, Boolean, Boolean, Boolean) -> Unit, onDismiss: () -> Unit) {
    var note by remember { mutableStateOf("") }
    var censored by remember { mutableStateOf(hasResult) }
    var original by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Palette.Ink850, shape = RoundedCornerShape(26.dp),
        title = { Text(tr("דיווח על טעות"), color = Palette.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tr("נשלח רק מה שתסמנו, דרך אפליקציה שתבחרו (מייל, וואטסאפ). לאפליקציה עצמה אין אינטרנט."), color = Palette.Ink300, fontSize = 12.5.sp)
                OutlinedTextField(
                    note, { note = it }, label = { Text(tr("מה לא היה תקין?")) }, minLines = 2, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Shield500, unfocusedBorderColor = Palette.Ink600),
                )
                @Composable
                fun opt(label: String, v: Boolean, set: (Boolean) -> Unit, enabled: Boolean = true) = Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(enabled = enabled) { set(!v) }) {
                    Checkbox(v, set, enabled = enabled, colors = CheckboxDefaults.colors(checkedColor = Palette.Shield500))
                    Text(label, color = if (enabled) Palette.Ink100 else Palette.Ink400, fontSize = 13.5.sp)
                }
                opt(tr("לצרף את התוצאה המצונזרת"), censored, { censored = it }, hasResult)
                opt(tr("לצרף את המקור (לא מצונזר!)"), original, { original = it }, hasResult)
                opt(tr("לצרף פרטים טכניים (גרסה, הגדרות, אנשים)"), details, { details = it })
            }
        },
        confirmButton = { TextButton(onClick = { onSend(note, censored, original, details) }) { Text(tr("שליחה")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("ביטול")) } },
    )
}

/** The saved setting sets (tr("תבניות")): switch with one tap, save the current settings under a name, delete. */
@Composable
fun PresetsScreen(presets: List<Preset>, current: CensorSettings, onApply: (Preset) -> Unit, onSave: (String) -> Unit, onDelete: (Preset) -> Unit) {
    var name by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(tr("תבניות שמירה"), color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(tr("כל תבנית היא סט הגדרות צנזור. לחיצה מפעילה אותה, וההגדרות חלות על הצנזור הבא."), color = Palette.Ink300, fontSize = 13.sp, lineHeight = 18.sp)
        for (p in presets) {
            val active = p.settings.copy(color = current.color) == current
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(if (active) Palette.Shield500.copy(alpha = 0.14f) else Palette.Veil)
                    .border(1.dp, if (active) Palette.Shield400.copy(alpha = 0.6f) else Palette.Border, RoundedCornerShape(16.dp))
                    .clickable { onApply(p) }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(Color(CensorSettings.parseColor(p.settings.color) or -0x1000000)).border(1.dp, Palette.Border, RoundedCornerShape(8.dp)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr(p.name), color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(presetSummary(p.settings), color = Palette.Ink400, fontSize = 12.sp)
                }
                if (active) Icon(Icons.Filled.CheckCircle, null, tint = Palette.Shield400)
                IconButton(onClick = { onDelete(p) }) { Icon(Icons.Filled.Close, tr("מחיקה"), tint = Palette.Ink400) }
            }
        }
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("שמירת ההגדרות הנוכחיות כתבנית"), color = Palette.Text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    name, { name = it }, singleLine = true, label = { Text(tr("שם התבנית")) }, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Shield500, unfocusedBorderColor = Palette.Ink600),
                )
                PrimaryButton(tr("שמירה"), Icons.Filled.Save, enabled = name.isNotBlank()) { onSave(name); name = "" }
            }
        }
    }
}

fun presetSummary(s: CensorSettings): String = listOf(
    if (s.target == "female") tr("נשים בוגרות") else tr("כולם"),
    if (s.fill == "clothing") tr("המשך הבגד") else tr("צבע אחיד"),
    tr("רגישות {0}", s.sensitivity),
    if (s.aggressive) tr("אגרסיבי") else null,
).filterNotNull().joinToString(" · ")

/** Privacy and security: what stays on the phone, how to check, and the signing fingerprint. */
@Composable
fun PrivacyScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(tr("פרטיות ואבטחה"), color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        for ((icon, title, body) in listOf(
            Triple(Icons.Filled.Lock, tr("הכול נשאר בטלפון"), tr("הזיהוי והצנזור רצים על המעבד של הטלפון. תמונות וסרטונים אף פעם לא נשלחים לשום מקום.")),
            Triple(Icons.Filled.Shield, tr("אין לאפליקציה אינטרנט"), tr("לאפליקציה אין בכלל הרשאת אינטרנט, כך שהיא לא יכולה לשלוח כלום. אפשר לבדוק: הגדרות הטלפון ← אפליקציות ← BlueShield ← הרשאות.")),
            Triple(Icons.Filled.TouchApp, tr("רק מה שאתם שולחים"), tr("שיתוף, וואטסאפ ודיווח על טעות נשלחים רק כשאתם לוחצים, ורק דרך אפליקציה שאתם בוחרים.")),
            Triple(Icons.Filled.AutoFixHigh, tr("מה נשמר באפליקציה"), tr("20 התמונות ו-5 הסרטונים האחרונים שצונזרו, כדי שתוכלו לחזור אליהם. אפשר למחוק כל אחד מהם ב\"פעילות\".")),
        )) {
            Panel(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp)) {
                    Icon(icon, null, tint = Palette.Shield400, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(title, color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(body, color = Palette.Ink300, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }
            }
        }
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("טביעת אצבע של הקובץ"), color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(tr("טביעת האצבע של חתימת האפליקציה (SHA-256), לבדיקה שהקובץ מקורי:"), color = Palette.Ink300, fontSize = 12.5.sp)
                Text(ltr(BuildInfo.SIGNING_SHA256), color = Palette.Ink300, fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        }
    }
}

/** Questions people ask. */
@Composable
fun FaqScreen() {
    val items = listOf(
        tr("את מי האפליקציה מכסה?") to tr("כברירת מחדל רק נשים בוגרות. גברים וילדות לא מכוסים. אפשר לשנות בהגדרות ל\"כולם\", או לבחור ידנית לכל אדם."),
        tr("מה מכוסה בדיוק?") to tr("העור החשוף: זרועות, כתפיים, גב ומחשוף מסוף הגרון ומטה. הפנים והצוואר נשארים גלויים, וגם טקסט שעל המסך."),
        tr("משהו לא כוסה, או כוסה מיותר. מה עושים?") to tr("בתמונה: \"תיקון במכחול\" מוסיף או מוחק כיסוי. אפשר גם לשנות לכל אדם \"צנזר\" או \"אל תצנזר\" ולעבד מחדש."),
        tr("האם התמונות שלי עולות לאינטרנט?") to tr("לא. לאפליקציה אין הרשאת אינטרנט בכלל. הכול קורה בטלפון."),
        tr("איפה נשמרות התוצאות?") to tr("בגלריה של הטלפון, בתיקייה BlueShield (אם \"שמירה אוטומטית\" פועלת), וגם ב\"פעילות\" באפליקציה."),
        tr("אפשר לצנזר רק חלק מסרטון?") to tr("כן. אחרי בחירת הסרטון מפעילים \"חיתוך\" ובוחרים את הקטע. גם לכל אדם אפשר לבחור \"רק בחלק מהזמן\"."),
        tr("אפשר כמה קבצים ביחד?") to tr("כן. בוחרים כמה תמונות או כמה סרטונים יחד. התמונות מוצגות בסוף אחת אחרי השנייה, והסרטונים נכנסים לתור."),
        tr("למה הגיל מסומן \"לא ודאי\"?") to tr("קשה לזהות גיל מתמונה בין 13 ל-18. במקרה כזה האפליקציה מכסה ליתר ביטחון ומבקשת לבדוק."),
        tr("העיבוד איטי. מה אפשר לעשות?") to tr("בהגדרות: \"ביצועים\" ← \"מהיר\", או לחתוך את הסרטון לקטע הנחוץ. בטלפונים עם Snapdragon אפשר לבחור את שבב הבינה המלאכותית."),
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr("שאלות נפוצות"), color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        for ((q, a) in items) {
            var open by remember { mutableStateOf(false) }
            Panel(Modifier.fillMaxWidth().clickable { open = !open }) {
                Column(Modifier.padding(14.dp)) {
                    Text(q, color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    if (open) Text(a, color = Palette.Ink300, fontSize = 13.5.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

/** The welcome pages on the first launch. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Onboarding(onDone: () -> Unit) {
    val pages = listOf(
        Triple(Icons.Filled.Shield, tr("ברוכים הבאים ל-BlueShield"), tr("צנזור אוטומטי לתמונות ולסרטונים: עור חשוף של נשים בוגרות מכוסה בצבע אחיד, בדיוק לפי קווי הגוף.")),
        Triple(Icons.Filled.Lock, tr("פרטי לגמרי"), tr("הכול קורה בטלפון. לאפליקציה אין הרשאת אינטרנט, ושום תמונה לא יוצאת ממנו.")),
        Triple(Icons.Filled.TouchApp, tr("אתם מחליטים"), tr("אחרי הצנזור אפשר לבחור לכל אדם אם לצנזר, לתקן במכחול, ולשמור או לשתף.")),
    )
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    GlassBackground {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            HorizontalPager(pager, Modifier.weight(1f)) { i ->
                val (icon, title, body) = pages[i]
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(120.dp).clip(RoundedCornerShape(36.dp)).background(Glass.Accent),
                        contentAlignment = Alignment.Center,
                    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(60.dp)) }
                    Spacer(Modifier.height(28.dp))
                    Text(title, color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text(body, color = Palette.Ink300, fontSize = 16.sp, lineHeight = 23.sp, textAlign = TextAlign.Center)
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
                repeat(pages.size) { i ->
                    Box(Modifier.padding(4.dp).size(if (i == pager.currentPage) 22.dp else 8.dp, 8.dp).clip(CircleShape)
                        .background(if (i == pager.currentPage) Palette.Shield400 else Palette.Ink600))
                }
            }
            val last = pager.currentPage == pages.lastIndex
            PrimaryButton(if (last) tr("בואו נתחיל") else tr("הבא"), if (last) Icons.Filled.Check else Icons.Filled.PlayArrow) {
                if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
            }
            if (!last) TextButton(onClick = onDone, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(tr("דילוג"), color = Palette.Ink300) }
        }
    }
}

/** What changed in this version, shown once after an update. */
@Composable
fun WhatsNewDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose, containerColor = Palette.Ink850, shape = RoundedCornerShape(26.dp),
        title = { Text(tr("מה חדש בגרסה {0}", ltr(BuildInfo.version)), color = Palette.Text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (line in WHATS_NEW) Text("• " + tr(line), color = Palette.Ink100, fontSize = 13.5.sp, lineHeight = 19.sp)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(tr("מעולה")) } },
    )
}

val WHATS_NEW = listOf(
    "עיצוב חדש: תפריט צד, סרגל תחתון, ומסך בית עם הפעולות האחרונות והגדרות מהירות.",
    "בהגדרות: צבע האפליקציה לבחירה, מצב כהה או בהיר, גודל טקסט ושפה.",
    "\"לשאול את מי לצנזר\": האפליקציה מוצאת את האנשים, ואתם בוחרים לפני הצנזור.",
    "מכחול לתיקון ידני בתמונות, והשוואה בגרירה בין המקור לתוצאה.",
    "חיתוך סרטון לפני הצנזור, ולכל אדם \"רק בחלק מהזמן\".",
    "כמה סרטונים בבת אחת, בתור, גם כשהמסך כבוי.",
    "תבניות הגדרות, שליחה ישירה לוואטסאפ, מחיקת המקור ודיווח על טעות.",
    "ווידג'ט למסך הבית ואריח בהגדרות המהירות של הטלפון.",
)
