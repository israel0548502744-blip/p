package com.blueshield.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blueshield.app.UiPrefs
import com.blueshield.app.engine.History
import com.blueshield.core.CensorSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What a recent result's "⋮" menu can do. */
class EntryActions(
    val open: (Int) -> Unit,
    val save: (History.Entry) -> Unit,
    val share: (History.Entry) -> Unit,
    val whatsApp: (History.Entry) -> Unit,
    val delete: (History.Entry) -> Unit,
)

/** Something still being worked on, shown on the home screen when the user stepped away from it. */
class ActiveWork(val title: String, val percent: Int?, val onOpen: () -> Unit)

/** The start screen: the welcome, what to censor (two painted tiles), the latest files and the quick tools. */
@Composable
fun StartScreen(
    loading: Boolean, history: List<History.Entry>, name: String,
    onPhoto: () -> Unit, onVideo: () -> Unit, onAll: () -> Unit, actions: EntryActions,
    onGuide: () -> Unit, onSettings: () -> Unit, onPrivacy: () -> Unit, onPresets: () -> Unit,
    active: ActiveWork? = null,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 6.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                (if (name.isBlank()) tr("ברוך הבא") else tr("ברוך הבא, {0}", name)) + " 👋",
                color = Palette.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                tr("בחר פעולה כדי להתחיל בצנזור פריט חדש בקלות ובביטחון."),
                color = Palette.Ink300, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        active?.let { ActiveCard(it) }
        if (loading) Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.Shield400) }
        else Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KindTile(false, tr("צנזור תמונה"), tr("העלאת תמונה וצנזור"), Modifier.weight(1f), onPhoto)
            KindTile(true, tr("צנזור סרטון"), tr("העלאת וידאו וצנזור"), Modifier.weight(1f), onVideo)
        }

        SectionHeading(tr("קבצים אחרונים"), if (history.isNotEmpty()) tr("הצג הכל") else null, onAll)
        if (history.isEmpty()) Panel(Modifier.fillMaxWidth()) {
            Text(tr("עוד אין כאן קבצים. תמונות וסרטונים שתצנזרו יופיעו כאן."), color = Palette.Ink300, fontSize = 13.5.sp, modifier = Modifier.padding(18.dp))
        } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            history.take(3).forEachIndexed { i, e -> RecentRow(e, i, actions) }
        }

        SectionHeading(tr("כלים מהירים"))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            QuickTool(Icons.AutoMirrored.Outlined.MenuBook, tr("מדריך למתחילים"), Modifier.weight(1f), onClick = onGuide)
            QuickTool(Icons.Outlined.Tune, tr("הגדרות"), Modifier.weight(1f), onClick = onSettings)
            QuickTool(Icons.Outlined.Shield, tr("בדיקת פרטיות"), Modifier.weight(1f), badge = tr("חדש"), onClick = onPrivacy)
            QuickTool(Icons.Outlined.GridView, tr("טמפלטים"), Modifier.weight(1f), onClick = onPresets)
        }
    }
}

/** "Still working on …": a card back to the photo or video that is being processed. */
@Composable
private fun ActiveCard(a: ActiveWork) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Shield500.copy(alpha = 0.16f))
            .border(1.dp, Palette.Shield400.copy(alpha = 0.4f), RoundedCornerShape(18.dp)).clickable(onClick = a.onOpen).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (a.percent != null) Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = { a.percent / 100f }, color = Palette.Shield300, trackColor = Palette.Ink700, strokeWidth = 3.dp, modifier = Modifier.size(38.dp))
            Text("${a.percent}%", color = Palette.Text, fontSize = 10.sp)
        } else CircularProgressIndicator(color = Palette.Shield300, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(12.dp))
        Text(a.title, color = Palette.Text, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, tint = Palette.Ink200)
    }
}

fun formatLabel(f: String) = when (f) {
    "jpg" -> "JPG"
    "png" -> "PNG"
    else -> tr("מקורי")
}

fun qualityLabel(q: String) = when (q) {
    "high" -> tr("גבוהה")
    "small" -> tr("קובץ קטן")
    else -> tr("רגילה")
}

/**
 * One of the two big start tiles: its name, a preview of what the censoring looks like (people with the
 * see-through cover box), the line under it, and the round arrow. Violet for video, teal for photos.
 */
@Composable
private fun KindTile(video: Boolean, title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    val bg = if (video) listOf(Color(0xFF3F2E99), Color(0xFF261B66)) else listOf(Color(0xFF1E625E), Color(0xFF113B3E))
    val rim = if (video) Color(0xFF8C74F0) else Color(0xFF4FD1C0)
    Column(
        modifier.fillMaxHeight().clip(shape).background(Brush.verticalGradient(bg))
            .border(1.dp, Brush.verticalGradient(listOf(rim.copy(alpha = 0.55f), rim.copy(alpha = 0.12f))), shape)
            .clickable(onClick = onClick).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().aspectRatio(1.55f).clip(RoundedCornerShape(14.dp))) {
            CensorPreview(video, Modifier.fillMaxSize())
            if (video) Icon(
                Icons.Filled.PlayArrow, null, tint = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).size(20.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x99000000)).padding(2.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(sub, color = Color.White.copy(alpha = 0.82f), fontSize = 12.5.sp, lineHeight = 16.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.weight(1f, fill = false))
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(Color(0x33FFFFFF)).border(1.dp, Color(0x44FFFFFF), CircleShape),
            contentAlignment = Alignment.Center,
        ) { DesignArrow(Color.White) }
    }
}

/** A small square tool under "כלים מהירים", with an optional label on its top edge ("חדש"). */
@Composable
private fun QuickTool(icon: ImageVector, label: String, modifier: Modifier, badge: String? = null, onClick: () -> Unit) {
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.padding(top = 9.dp).fillMaxWidth().height(92.dp).glass(RoundedCornerShape(18.dp)).clickable(onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, null, tint = Palette.Ink100, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, color = Palette.Ink100, fontSize = 12.sp, lineHeight = 14.sp, textAlign = TextAlign.Center, maxLines = 2)
        }
        if (badge != null) Text(
            badge, color = Color(0xFF062A27), fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Palette.Teal).padding(horizontal = 9.dp, vertical = 1.dp),
        )
    }
}

/** One recent result: picture, name, date and size, and a "⋮" menu. */
@Composable
fun RecentRow(e: History.Entry, index: Int, actions: EntryActions) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Ink850).border(1.dp, Palette.Border, RoundedCornerShape(16.dp))
            .clickable { actions.open(index) }.padding(start = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val bmp by produceState<Bitmap?>(null, e.thumb) { value = withContext(Dispatchers.IO) { History.decode(e.thumb, 300) } }
        Box(Modifier.width(72.dp).height(50.dp).clip(RoundedCornerShape(10.dp)).background(Palette.Ink700), contentAlignment = Alignment.Center) {
            bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            if (e.video) Box(Modifier.size(24.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
            if (e.gallery != null) Icon(
                Icons.Outlined.CheckCircle, tr("בגלריה"), tint = Palette.Teal,
                modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp).size(15.dp).clip(CircleShape).background(Color(0xAA000000)),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(isolate(e.name), color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            val length by produceState<Double?>(null, e.file) { if (e.video) value = withContext(Dispatchers.IO) { videoSeconds(e.file) } }
            Text(
                ltr(listOfNotNull(SimpleDateFormat("dd.MM.yy", Locale.US).format(Date(e.time)), formatBytes(File(e.file).length()), length?.let(::formatDuration)).joinToString(" • ")),
                color = Palette.Ink400, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, tr("אפשרויות"), tint = Palette.Ink200) }
            DropdownMenu(menu, { menu = false }, containerColor = Palette.Ink850) {
                DropdownMenuItem(text = { Text(tr("פתיחה")) }, leadingIcon = { Icon(Icons.Filled.PlayArrow, null) }, onClick = { menu = false; actions.open(index) })
                if (e.gallery == null) {
                    DropdownMenuItem(text = { Text(tr("הורדה לגלריה")) }, leadingIcon = { Icon(Icons.Filled.Download, null) }, onClick = { menu = false; actions.save(e) })
                }
                DropdownMenuItem(text = { Text(tr("שיתוף")) }, leadingIcon = { Icon(Icons.Filled.Share, null) }, onClick = { menu = false; actions.share(e) })
                DropdownMenuItem(text = { Text(tr("שליחה בוואטסאפ")) }, leadingIcon = { Icon(Icons.Filled.Share, null) }, onClick = { menu = false; actions.whatsApp(e) })
                DropdownMenuItem(text = { Text(tr("מחיקה")) }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; actions.delete(e) })
            }
        }
    }
}

/** A video file's length in seconds (null when it can't be read). */
private fun videoSeconds(path: String): Double? {
    val m = android.media.MediaMetadataRetriever()
    return try {
        m.setDataSource(path)
        m.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { it / 1000.0 }
    } catch (_: Exception) {
        null
    } finally {
        runCatching { m.release() }
    }
}
