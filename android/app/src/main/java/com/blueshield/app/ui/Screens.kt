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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.OutlinedButton
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
fun PrimaryButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Palette.Shield600, disabledContainerColor = Palette.Shield700.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth().height(54.dp),
    ) {
        Icon(icon, null)
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ───────────────────────────────── Home ─────────────────────────────────

@Composable
fun HomeScreen(
    video: VideoMeta?, settings: CensorSettings, loading: Boolean, error: String?,
    onPick: () -> Unit, onClear: () -> Unit, onEditSettings: () -> Unit, onStart: () -> Unit,
    onPickMany: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (video == null) {
                EmptyState(loading, error, onPick, onPickMany)
            } else {
                SinglePlayer(video.uri, video.width.toFloat() / video.height, Modifier.fillMaxWidth())
                VideoInfo(video, onClear)
                SettingsSummary(settings, onEditSettings)
            }
        }
        if (video != null) Box(Modifier.background(Palette.Ink950).padding(16.dp)) { PrimaryButton("התחלת צנזור", Icons.Filled.PlayArrow, onClick = onStart) }
    }
}

@Composable
private fun EmptyState(loading: Boolean, error: String?, onPick: () -> Unit, onPickMany: () -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val pulse = rememberInfiniteTransition(label = "pulse")
            val scale by pulse.animateFloat(0.92f, 1.08f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "scale")
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size((84 * scale).dp).clip(RoundedCornerShape(26.dp)).background(Palette.Shield500.copy(alpha = 0.18f)))
                Box(
                    Modifier.size(72.dp).clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(Palette.Shield400, Palette.Shield700))),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.VideoLibrary, null, tint = Color.White, modifier = Modifier.size(34.dp)) }
            }
            Spacer(Modifier.height(20.dp))
            Text("בחרו סרטון או תמונה", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                // RLM first so the paragraph is right-to-left even though it starts with the Latin brand name
                "\u200FBlueShield מאתרת עור חשוף של נשים בוגרות — פריים אחר פריים בסרטון, או בתמונה בודדת — ומכסה אותו במסכה נקייה בצבע שתבחרו. פנים, צוואר וטקסט שעל המסך נשארים גלויים.",
                color = Palette.Ink300, fontSize = 14.sp, lineHeight = 20.sp,
            )
            Spacer(Modifier.height(20.dp))
            if (loading) CircularProgressIndicator(color = Palette.Shield400)
            else {
                PrimaryButton("בחירת סרטון או תמונה", Icons.Filled.Movie, onClick = onPick)
                Spacer(Modifier.height(10.dp))
                androidx.compose.material3.OutlinedButton(
                    onClick = onPickMany, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Filled.Collections, null)
                    Spacer(Modifier.width(8.dp))
                    Text("כמה תמונות בבת אחת")
                }
            }
            if (error != null) Text(error, color = Palette.Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(16.dp))
            PrivacyPill()
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
                    Text(v.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(v.videoMime.removePrefix("video/").uppercase(), color = Palette.Ink400, fontSize = 11.sp)
                }
                IconButton(onClick = onClear) { Icon(Icons.Filled.Close, "הסרה", tint = Palette.Ink300) }
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("משך", formatDuration(v.durationSec))
                Chip("רזולוציה", ltr("${v.width}×${v.height}"))
                Chip("קצב פריימים", "%.2f fps".format(v.fps).replace(".00", ""))
                Chip("גודל", formatBytes(v.sizeBytes))
                Chip("שמע", v.audioMime?.removePrefix("audio/")?.uppercase() ?: "אין")
            }
        }
    }
}

// ─────────────────────────────── Processing ───────────────────────────────

private val STEPS = listOf(
    Triple(JobState.Stage.ANALYZING, "מנתח את הסרטון…", "קריאת פריימים וטעינת מודלים"),
    Triple(JobState.Stage.DETECTING, "מאתר אזורים רגישים…", "אנשים · מגדר · עור · מעקב"),
    Triple(JobState.Stage.APPLYING, "מחיל את הצנזור…", "מסכות עם שוליים רכים"),
    Triple(JobState.Stage.ENCODING, "מקודד את הסרטון הסופי…", "קידוד H.264 · קצב פריימים מקורי · שמע"),
)

@Composable
fun ProcessingScreen(job: JobState, onPause: () -> Unit, onResume: () -> Unit, onCancel: () -> Unit) {
    val active = STEPS.indexOfFirst { it.first == job.stage }.let { if (it < 0) 0 else it }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(16.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
            val p: Bitmap? = job.preview
            if (p != null) Image(p.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else CircularProgressIndicator(color = Palette.Shield300)
            if (!job.paused) {
                val scan = rememberInfiniteTransition(label = "scan")
                val y by scan.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Reverse), label = "y")
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(
                        Brush.verticalGradient(listOf(Color.Transparent, Palette.Shield400.copy(alpha = 0.25f), Color.Transparent), startY = size.height * y - 40f, endY = size.height * y + 40f),
                        topLeft = Offset(0f, size.height * y - 40f), size = Size(size.width, 80f),
                    )
                }
            }
            Row(
                Modifier.align(Alignment.TopStart).padding(10.dp).clip(RoundedCornerShape(50)).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(if (job.paused) Palette.Amber else Color(0xFFEF4444))
                Spacer(Modifier.width(6.dp))
                Text(if (job.paused) "מושהה" else if (job.passIndex == 2) "תצוגה חיה של התוצאה" else "תצוגה חיה של הזיהוי", color = Palette.Ink100, fontSize = 11.5.sp)
            }
        }
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Ring(job.percent)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (job.paused) "מושהה" else STEPS[active].second, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { job.percent / 100f }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                            color = Palette.Shield500, trackColor = Palette.Ink700, strokeCap = StrokeCap.Round,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(if (job.passIndex == 2) "רינדור פריים" else "ניתוח פריים", ltr("${job.frame} / ${job.totalFrames}"), Modifier.weight(1f))
                    Chip("זמן שנותר", if (job.paused) "מושהה" else formatEta(job.etaSeconds), Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("מהירות", if (job.fps > 0) "%.1f fps".format(job.fps) else "—", Modifier.weight(1f))
                    Chip("זמן שעבר", formatDuration(job.elapsed), Modifier.weight(1f))
                }
                Spacer(Modifier.height(14.dp))
                for ((i, step) in STEPS.withIndex()) StepRow(i, step.second, step.third, done = i < active, now = i == active, paused = job.paused)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = if (job.paused) onResume else onPause, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                        Icon(if (job.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (job.paused) "המשך" else "השהיה")
                    }
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Filled.Stop, null, tint = Palette.Danger)
                        Spacer(Modifier.width(6.dp))
                        Text("ביטול", color = Color(0xFFFECACA))
                    }
                }
            }
        }
        PrivacyPill()
    }
}

@Composable
private fun Ring(percent: Float) {
    val animated by animateFloatAsState(percent / 100f, tween(450), label = "ring")
    Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawArc(Palette.Ink700, 0f, 360f, false, style = Stroke(9.dp.toPx()))
            drawArc(Brush.sweepGradient(listOf(Palette.Shield300, Palette.Shield600, Palette.Shield300)), -90f, 360f * animated, false, style = Stroke(9.dp.toPx(), cap = StrokeCap.Round))
        }
        Text("${percent.toInt()}%", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StepRow(i: Int, label: String, sub: String, done: Boolean, now: Boolean, paused: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp))
            .background(if (now) Palette.Shield500.copy(alpha = 0.10f) else Color(0x05FFFFFF)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(if (done) Palette.Emerald else if (now) Palette.Shield500 else Palette.Ink700),
            contentAlignment = Alignment.Center,
        ) {
            when {
                done -> Icon(Icons.Filled.CheckCircle, null, tint = Color.White, modifier = Modifier.size(18.dp))
                now && !paused -> CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(15.dp))
                else -> Text("${i + 1}", color = Palette.Ink300, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, color = if (done || now) Color.White else Palette.Ink400, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            Text(sub, color = Palette.Ink400, fontSize = 11.sp)
        }
    }
}

// ───────────────────────────────── Result ─────────────────────────────────

@Composable
fun ResultScreen(
    video: VideoMeta, job: JobState, censoredUri: android.net.Uri, draftOverrides: Map<Int, Override>, thumbnail: (Int) -> Bitmap?,
    onOverride: (Int, Override) -> Unit, onApply: () -> Unit, onSave: () -> Unit, onShare: () -> Unit, onAdjust: () -> Unit, onNew: () -> Unit,
    saved: Boolean, settings: CensorSettings,
) {
    var mode by remember { mutableStateOf(CompareMode.WIPE) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Emerald.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.CheckCircle, null, tint = Palette.Emerald)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("הצנזור הושלם.", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text("בדקו את התוצאה, ואז שמרו או שתפו.", color = Palette.Ink300, fontSize = 12.5.sp)
            }
        }
        Segmented(listOf(CompareMode.WIPE to "השוואה", CompareMode.SPLIT to "מקור / מצונזר", CompareMode.CENSORED to "מצונזר"), mode, { mode = it })
        key(job.version) {
            ComparePlayer(video.uri, censoredUri, video.width.toFloat() / video.height, mode, null, Modifier.fillMaxWidth())
        }
        PrimaryButton(if (saved) "נשמר בגלריה" else "שמירה בגלריה", Icons.Filled.Download, enabled = !saved, onClick = onSave)
        OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Filled.Share, null)
            Spacer(Modifier.width(8.dp))
            Text("שיתוף")
        }
        PeoplePanel(job.people, draftOverrides, thumbnail, onOverride, onApply, settings)
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                InfoRow("פורמט", "MP4 · H.264")
                InfoRow("רזולוציה", ltr("${video.width}×${video.height}"))
                InfoRow("קצב פריימים", ltr("%.2f fps".format(video.fps)) + " (תזמון מקורי)")
                InfoRow("שמע", if (!video.hasAudio) "אין במקור" else if (settings.keepAudio) "נשמר" else "הוסר")
                InfoRow("פריימים שצונזרו", ltr("${job.censoredFrames} / ${job.totalFrames}"))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onAdjust, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(6.dp)); Text("כוונון") }
            OutlinedButton(onClick = onNew, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Filled.Movie, null); Spacer(Modifier.width(6.dp)); Text("סרטון חדש") }
        }
        PrivacyPill()
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
) {
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("אנשים (${people.size})", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(if (settings.target == "female") "נשים בלבד · " + ltr("≥${settings.genderThreshold}%") else "כולם", color = Palette.Ink400, fontSize = 11.5.sp)
            }
            if (people.isEmpty()) Text("לא זוהו אנשים.", color = Palette.Ink300, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
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
                    Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x08FFFFFF)).padding(8.dp),
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
                            p.child && p.gender == "female" -> "ילדה"
                            p.child -> "ילד"
                            p.gender == "female" -> "אישה"
                            p.gender == "male" -> "גבר"
                            else -> "לא בטוח"
                        }
                        Text(
                            "#${p.id}  $gender" + (if (p.gender != "uncertain") " ${(p.confidence * 100).toInt()}%" else "") +
                                (p.age?.let { " · גיל כ-${it.toInt()}" } ?: ""),
                            color = if (p.gender == "uncertain") Palette.Amber else Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                        )
                        Text(ltr("${formatDuration(p.startSec)}–${formatDuration(p.endSec)}") + " · ${p.votes} זיהויי פנים", color = Palette.Ink400, fontSize = 11.sp)
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x33000000)).padding(2.dp)) {
                            for ((o, label) in listOf(Override.AUTO to "אוטומטי", Override.CENSOR to "צנזר", Override.KEEP to "אל תצנזר")) {
                                Text(
                                    label, color = if (ov == o) Color.White else Palette.Ink400, fontSize = 11.5.sp, fontWeight = FontWeight.Medium,
                                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (ov == o) Palette.Ink600 else Color.Transparent)
                                        .clickable { onOverride(p.id, o) }.padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
            val dirty = people.any { (draft[it.id] ?: it.override) != it.override }
            if (dirty) {
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onApply, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Amber.copy(alpha = 0.18f), contentColor = Color(0xFFFEF3C7)),
                ) { Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(6.dp)); Text("החלת השינויים ועיבוד מחדש") }
            }
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
        Text(text, color = Color(0xFFFECACA), fontSize = 13.sp, modifier = Modifier.weight(1f))
        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, null, tint = Palette.Ink300) }
    }
}

@Suppress("unused")
private val mono = FontFamily.Monospace
