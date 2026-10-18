package com.blueshield.app.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blueshield.app.engine.JobState
import com.blueshield.app.engine.VideoMeta
import com.blueshield.core.CensorSettings
import com.blueshield.core.gender.Override
import com.blueshield.core.pipeline.PersonSummary

@Composable
fun PrimaryButton(
    text: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, enabled: Boolean = true, trailingArrow: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier.fillMaxWidth().height(56.dp).clip(shape)
            .background(if (enabled) Brush.horizontalGradient(listOf(Palette.Shield400, Palette.Shield500, Palette.Shield600)) else Brush.linearGradient(listOf(Palette.VeilStrong, Palette.Veil)))
            .background(Brush.verticalGradient(listOf(Color(0x26FFFFFF), Color.Transparent)))
            .border(1.dp, Brush.verticalGradient(listOf(Color(0x55FFFFFF), Color(0x0FFFFFFF))), shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val tint = if (enabled) Color.White else Palette.Ink300
            if (icon != null) {
                Icon(icon, null, tint = tint)
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = tint, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (trailingArrow) {
                Spacer(Modifier.width(10.dp))
                DesignArrow(tint)
            }
        }
    }
}

/** A secondary action: the same size as [PrimaryButton], made of clear glass. */
@Composable
fun GlassButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(50.dp).glass(RoundedCornerShape(18.dp)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Palette.Ink100)
            Spacer(Modifier.width(8.dp))
            Text(text, color = Palette.Ink100, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** The bar at the bottom of a screen that holds its main button, frosted over the content. */
@Composable
fun BottomBar(content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Box(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Glass.Bar, Glass.Bar))).padding(16.dp),
        contentAlignment = Alignment.Center, content = content,
    )
}

// ───────────────────────────────── Home ─────────────────────────────────

/** A picked video: preview, details, the part to censor, a settings summary and the start button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoHome(
    video: VideoMeta, settings: CensorSettings, trim: Pair<Double, Double>?, onTrim: (Pair<Double, Double>?) -> Unit,
    onClear: () -> Unit, onEditSettings: () -> Unit, onStart: () -> Unit, askWho: Boolean,
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SinglePlayer(video.uri, video.width.toFloat() / video.height, Modifier.fillMaxWidth())
            VideoInfo(video, onClear)
            TrimPanel(video, trim, onTrim)
            SettingsSummary(settings, onEditSettings)
        }
        BottomBar {
            PrimaryButton(if (askWho) tr("איתור האנשים") else tr("התחלת צנזור"), Icons.Filled.PlayArrow, onClick = onStart)
        }
    }
}

/** "Censor only part of the video": a two-handled slider over its length. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrimPanel(video: VideoMeta, trim: Pair<Double, Double>?, onTrim: (Pair<Double, Double>?) -> Unit) {
    val dur = video.durationSec.coerceAtLeast(0.1)
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ContentCut, null, tint = Palette.Shield400, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("חיתוך: רק חלק מהסרטון"), color = Palette.Text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(
                    checked = trim != null, onCheckedChange = { onTrim(if (it) 0.0 to dur else null) },
                    colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Palette.Shield500),
                )
            }
            if (trim != null) {
                androidx.compose.material3.RangeSlider(
                    value = trim.first.toFloat()..trim.second.toFloat(),
                    onValueChange = { r -> if (r.endInclusive - r.start >= 0.5f) onTrim(r.start.toDouble() to r.endInclusive.toDouble()) },
                    valueRange = 0f..dur.toFloat(),
                    colors = androidx.compose.material3.SliderDefaults.colors(activeTrackColor = Palette.Shield400, thumbColor = Palette.Shield300),
                )
                Row {
                    Text(tr("מ-{0}", ltr(formatDuration(trim.first))), color = Palette.Ink200, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(tr("עד {0}", ltr(formatDuration(trim.second))), color = Palette.Ink200, fontSize = 13.sp)
                }
                Text(
                    tr("רק הקטע הזה יעובד ויישמר, וכך זה מהיר יותר. ההתחלה עשויה לזוז מעט אחורה, לפריים המפתח הקרוב."),
                    color = Palette.Ink400, fontSize = 11.5.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VideoInfo(v: VideoMeta, onClear: () -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Movie, null, tint = Palette.Shield300, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(v.name, color = Palette.Text, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(v.videoMime.removePrefix("video/").uppercase(), color = Palette.Ink400, fontSize = 11.sp)
                }
                IconButton(onClick = onClear) { Icon(Icons.Filled.Close, tr("הסרה"), tint = Palette.Ink300) }
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(tr("משך"), formatDuration(v.durationSec))
                Chip(tr("רזולוציה"), ltr("${v.width}×${v.height}"))
                Chip(tr("קצב פריימים"), "%.2f fps".format(v.fps).replace(".00", ""))
                Chip(tr("גודל"), formatBytes(v.sizeBytes))
                Chip(tr("שמע"), v.audioMime?.removePrefix("audio/")?.uppercase() ?: tr("אין"))
            }
        }
    }
}

// ─────────────────────────────── Processing ───────────────────────────────

private val STEPS get() = listOf(
    Triple(JobState.Stage.ANALYZING, tr("מנתח את הסרטון…"), tr("קריאת פריימים וטעינת מודלים")),
    Triple(JobState.Stage.DETECTING, tr("מאתר אזורים רגישים…"), tr("אנשים · מגדר · עור · מעקב")),
    Triple(JobState.Stage.APPLYING, tr("מחיל את הצנזור…"), tr("מסכות עם שוליים רכים")),
    Triple(JobState.Stage.ENCODING, tr("מקודד את הסרטון הסופי…"), tr("קידוד H.264 · קצב פריימים מקורי · שמע")),
)

@Composable
fun ProcessingScreen(job: JobState, onPause: () -> Unit, onResume: () -> Unit, onCancel: () -> Unit) {
    val active = STEPS.indexOfFirst { it.first == job.stage }.let { if (it < 0) 0 else it }
    ProcessingView(
        percent = job.percent, paused = job.paused,
        title = if (job.paused) tr("מושהה") else tr("מעבד את הסרטון שלך..."),
        sub = STEPS[active].second,
        stats = listOf(
            (if (job.passIndex == 2) tr("רינדור") else tr("ניתוח")) + " " + ltr("${job.frame} / ${job.totalFrames}"),
            if (job.paused) tr("מושהה") else formatEta(job.etaSeconds),
            if (job.fps > 0) ltr("%.1f fps".format(job.fps)) else "",
        ).filter { it.isNotEmpty() },
        preview = job.preview,
        onPause = if (job.paused) onResume else onPause, onCancel = onCancel,
    )
}

/**
 * The design's processing step: a large ring with the percentage, what is happening now, the three privacy
 * promises, the live preview, and the way to pause or cancel. [percent] null: no measure (a photo).
 */
@Composable
fun ProcessingView(
    percent: Float?, paused: Boolean, title: String, sub: String, stats: List<String>, preview: Bitmap?,
    onPause: (() -> Unit)?, onCancel: (() -> Unit)?,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BigRing(percent, paused)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Palette.Text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(tr("זה יכול לקחת מספר דקות"), color = Palette.Ink300, fontSize = 13.5.sp)
            Spacer(Modifier.height(6.dp))
            Text(sub, color = Palette.Shield300, fontSize = 13.sp)
            if (stats.isNotEmpty()) Text(stats.joinToString("  ·  "), color = Palette.Ink400, fontSize = 12.sp)
        }
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PromiseRow(Icons.Filled.Check, tr("הצנזור מתבצע מקומית במכשיר"), Palette.Teal)
                PromiseRow(Icons.Filled.Lock, tr("הקבצים לא עולים לשום שרת ולא נשלחים"), Palette.Ink200)
                PromiseRow(Icons.Filled.Shield, tr("הפרטיות שלך נשמרת"), Palette.Ink200)
            }
        }
        if (preview != null) Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
            Image(preview.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(preview.width.toFloat() / preview.height.coerceAtLeast(1)), contentScale = ContentScale.Fit)
            Row(
                Modifier.align(Alignment.TopStart).padding(10.dp).clip(RoundedCornerShape(50)).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(if (paused) Palette.Amber else Color(0xFFEF4444))
                Spacer(Modifier.width(6.dp))
                Text(tr("תצוגה חיה"), color = Color.White, fontSize = 11.5.sp)
            }
        }
        if (onPause != null) GlassOutlined(onClick = onPause, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp)) {
            Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null)
            Spacer(Modifier.width(6.dp))
            Text(if (paused) tr("המשך") else tr("השהיה"))
        }
        if (onCancel != null) Box(
            Modifier.fillMaxWidth().height(52.dp).glass(RoundedCornerShape(16.dp)).clickable(onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) { Text(tr("ביטול עיבוד"), color = Palette.Danger, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun PromiseRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(Palette.Ink800), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(text, color = Palette.Ink100, fontSize = 14.sp)
    }
}

/** The ring of the design: violet into teal, the number large with a small "%" beside it. */
@Composable
private fun BigRing(percent: Float?, paused: Boolean) {
    val animated by animateFloatAsState((percent ?: 0f) / 100f, tween(450), label = "ring")
    val spin by rememberInfiniteTransition(label = "busy").animateFloat(
        0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "spin",
    )
    Box(Modifier.size(196.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 14.dp.toPx()
            val inset = stroke / 2 + 6.dp.toPx()
            val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
            val tl = Offset(inset, inset)
            drawCircle(Brush.radialGradient(listOf(Palette.Shield500.copy(alpha = 0.18f), Color.Transparent)), size.minDimension / 2)
            drawArc(Palette.Ink800, 0f, 360f, false, tl, arcSize, style = Stroke(stroke))
            val brush = Brush.sweepGradient(listOf(Palette.Teal, Palette.Shield400, Palette.Shield500, Palette.Teal))
            if (percent == null) drawArc(brush, spin - 90f, 110f, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            else if (animated > 0f) drawArc(brush, -90f, 360f * animated, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        if (percent != null) Row(verticalAlignment = Alignment.Bottom) {
            Text("${percent.toInt()}", color = Palette.Text, fontSize = 50.sp, fontWeight = FontWeight.Bold, lineHeight = 52.sp)
            Text("%", color = Palette.Ink200, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp, start = 2.dp))
        } else Icon(if (paused) Icons.Filled.Pause else Icons.Filled.Shield, null, tint = Palette.Shield300, modifier = Modifier.size(48.dp))
    }
}

/** The design's last step: the round tick, "העיבוד הושלם בהצלחה", and the line under it. */
@Composable
fun DoneHeader(sub: String? = null) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
            Confetti(Modifier.width(220.dp).fillMaxHeight())
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(Palette.Teal.copy(alpha = 0.08f)).border(2.dp, Palette.Teal, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Check, tr("העיבוד הושלם בהצלחה"), tint = Palette.Teal, modifier = Modifier.size(34.dp)) }
        }
        if (sub != null) Text(sub, color = Palette.Ink300, fontSize = 13.sp)
    }
}

/** Save, then "more editing" and share side by side, then back to the projects: the design's result buttons. */
@Composable
fun ResultButtons(saved: Boolean, onSave: () -> Unit, onEdit: () -> Unit, onShare: () -> Unit, onProjects: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // saved already (by hand or automatically): the same button saves one more copy
        PrimaryButton(if (saved) tr("נשמר ✓ · שמירה נוספת") else tr("שמור למכשיר"), Icons.Filled.Download, onClick = onSave)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassOutlined(onClick = onEdit, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Filled.Edit, null); Spacer(Modifier.width(8.dp)); Text(tr("עריכה נוספת"))
            }
            GlassOutlined(onClick = onShare, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Filled.Share, null); Spacer(Modifier.width(8.dp)); Text(tr("שיתוף"))
            }
        }
        Box(
            Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, Palette.Shield500.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                .clickable(onClick = onProjects),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("חזרה לפרויקטים"), color = Palette.Shield300, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(8.dp))
                DesignArrow(Palette.Shield300)
            }
        }
    }
}

// ───────────────────────────────── Result ─────────────────────────────────

@Composable
fun ResultScreen(
    video: VideoMeta, job: JobState, censoredUri: android.net.Uri, draftOverrides: Map<Int, Override>, thumbnail: (Int) -> Bitmap?,
    onOverride: (Int, Override) -> Unit, onApply: () -> Unit, onSave: () -> Unit, onShare: () -> Unit, onAdjust: () -> Unit, onNew: () -> Unit,
    saved: Boolean, settings: CensorSettings,
    ranges: Map<Int, ClosedFloatingPointRange<Double>> = emptyMap(), onRange: ((Int, ClosedFloatingPointRange<Double>?) -> Unit)? = null,
    rangesChanged: Boolean = false, extra: ExtraActions? = null, onProjects: () -> Unit = onNew,
) {
    var mode by remember { mutableStateOf(CompareMode.CENSORED) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DoneHeader()
        key(job.version) {
            ComparePlayer(video.uri, censoredUri, video.width.toFloat() / video.height, mode, null, Modifier.fillMaxWidth())
        }
        Segmented(listOf(CompareMode.CENSORED to tr("מצונזר"), CompareMode.WIPE to tr("השוואה"), CompareMode.SPLIT to tr("מקור / מצונזר")), mode, { mode = it })
        ResultButtons(saved, onSave, onEdit = onAdjust, onShare = onShare, onProjects = onProjects)
        SectionHeading(tr("עוד אפשרויות"))
        extra?.let { MoreActionsRow(it) }
        PeoplePanel(job.people, draftOverrides, thumbnail, onOverride, onApply, settings, ranges, onRange, rangesChanged)
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                InfoRow(tr("פורמט"), "MP4 · H.264")
                InfoRow(tr("רזולוציה"), ltr("${video.width}×${video.height}"))
                InfoRow(tr("קצב פריימים"), ltr("%.2f fps".format(video.fps)) + tr(" (תזמון מקורי)"))
                InfoRow(tr("שמע"), if (!video.hasAudio) tr("אין במקור") else if (settings.keepAudio) tr("נשמר") else tr("הוסר"))
                InfoRow(tr("פריימים שצונזרו"), ltr("${job.censoredFrames} / ${job.totalFrames}"))
            }
        }
        GlassOutlined(onClick = onNew, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Filled.Movie, null); Spacer(Modifier.width(6.dp)); Text(tr("סרטון חדש"))
        }
    }
}

@Composable
internal fun InfoRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, color = Palette.Ink400, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(v, color = Palette.Ink100, fontSize = 13.sp)
    }
}

@Composable
internal fun PeoplePanel(
    people: List<PersonSummary>, draft: Map<Int, Override>, thumbnail: (Int) -> Bitmap?,
    onOverride: (Int, Override) -> Unit, onApply: () -> Unit, settings: CensorSettings,
    ranges: Map<Int, ClosedFloatingPointRange<Double>>? = null, onRange: ((Int, ClosedFloatingPointRange<Double>?) -> Unit)? = null,
    rangesChanged: Boolean = false,
) {
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("אנשים ({0})", people.size), color = Palette.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(if (settings.target == "female") tr("נשים בלבד · ") + ltr("≥${settings.genderThreshold}%") else tr("כולם"), color = Palette.Ink400, fontSize = 11.5.sp)
            }
            if (people.isEmpty()) Text(tr("לא זוהו אנשים."), color = Palette.Ink300, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            for (p in people) {
                val ov = draft[p.id] ?: p.override
                val willCensor = when (ov) {
                    Override.CENSOR -> true
                    Override.KEEP -> false
                    Override.AUTO -> com.blueshield.core.gender.censorDecision(
                        com.blueshield.core.gender.GenderEstimate.Label.entries.first { it.key == p.gender }, settings.target, settings.uncertainPolicy,
                        child = p.child,
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Veil).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Palette.Ink700), contentAlignment = Alignment.Center) {
                        val bmp = thumbnail(p.id)
                        if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Icon(Icons.Filled.Person, null, tint = Palette.Ink400)
                        Box(
                            Modifier.align(Alignment.BottomEnd).padding(2.dp).size(14.dp).clip(CircleShape)
                                .background(if (willCensor) Palette.Shield500 else Palette.Ink400),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        val gender = when {
                            p.child && p.gender == "female" -> tr("ילדה")
                            p.child -> tr("ילד")
                            p.gender == "female" -> tr("אישה")
                            p.gender == "male" -> tr("גבר")
                            else -> tr("לא בטוח")
                        }
                        Text(
                            "#${p.id}  $gender" + (if (p.gender != "uncertain") " ${(p.confidence * 100).toInt()}%" else "") +
                                (p.age?.let { " · " + tr("גיל כ-{0}", it.toInt()) } ?: ""),
                            color = if (p.gender == "uncertain") Palette.Amber else Palette.Text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                        )
                        Text(ltr("${formatDuration(p.startSec)}–${formatDuration(p.endSec)}") + " · " + tr("{0} זיהויי פנים", p.votes), color = Palette.Ink400, fontSize = 11.sp)
                        // no model tells a 13-17 year old from an adult reliably: an estimated age in between is flagged
                        // for a look (the automatic choice stays the safe one, covered)
                        if (!p.child && (p.age ?: 99.0) < 18.0) Text(tr("הגיל לא ודאי, כדאי לבדוק ולבחור ידנית"), color = Palette.Amber, fontSize = 11.sp)
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.clip(RoundedCornerShape(8.dp)).background(Palette.VeilStrong).padding(2.dp)) {
                            for ((o, label) in listOf(Override.AUTO to tr("אוטומטי"), Override.CENSOR to tr("צנזר"), Override.KEEP to tr("אל תצנזר"))) {
                                Text(
                                    label, color = if (ov == o) Color.White else Palette.Ink300, fontSize = 11.5.sp, fontWeight = FontWeight.Medium,
                                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (ov == o) Palette.Shield500 else Color.Transparent)
                                        .clickable { onOverride(p.id, o) }.padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                        }
                        if (onRange != null && willCensor && p.endSec - p.startSec > 1.0) PersonRange(p, ranges?.get(p.id), onRange)
                    }
                }
            }
            val dirty = rangesChanged || people.any { (draft[it.id] ?: it.override) != it.override }
            if (dirty) {
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onApply, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Amber.copy(alpha = 0.18f), contentColor = Palette.Amber),
                ) { Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(6.dp)); Text(tr("החלת השינויים ועיבוד מחדש")) }
            }
        }
    }
}

/** "Censor only part of the time": a two-handled slider over the time the person is on screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonRange(p: PersonSummary, r: ClosedFloatingPointRange<Double>?, onRange: (Int, ClosedFloatingPointRange<Double>?) -> Unit) {
    Column(Modifier.padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("רק בחלק מהזמן"), color = Palette.Ink200, fontSize = 12.sp, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(
                checked = r != null, onCheckedChange = { onRange(p.id, if (it) p.startSec..p.endSec else null) },
                modifier = Modifier.height(28.dp),
                colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Palette.Shield500),
            )
        }
        if (r != null) {
            androidx.compose.material3.RangeSlider(
                value = r.start.toFloat()..r.endInclusive.toFloat(),
                onValueChange = { v -> onRange(p.id, v.start.toDouble()..v.endInclusive.toDouble()) },
                valueRange = p.startSec.toFloat()..p.endSec.toFloat(),
                colors = androidx.compose.material3.SliderDefaults.colors(activeTrackColor = Palette.Shield400, thumbColor = Palette.Shield300),
            )
            Text(tr("מצונזרת רק בין {0} ל-{1}", ltr(formatDuration(r.start)), ltr(formatDuration(r.endInclusive))), color = Palette.Ink400, fontSize = 11.sp)
        }
    }
}

@Composable
fun ErrorBanner(text: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Danger.copy(alpha = 0.12f))
            .border(1.dp, Palette.Danger.copy(alpha = 0.3f), RoundedCornerShape(12.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Palette.DangerText, fontSize = 13.sp, modifier = Modifier.weight(1f))
        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, null, tint = Palette.Ink300) }
    }
}

@Suppress("unused")
private val mono = FontFamily.Monospace
