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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
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

/** The start screen: what to censor, the last results, quick settings, and the privacy promise. */
@Composable
fun StartScreen(
    loading: Boolean, history: List<History.Entry>, settings: CensorSettings, ui: UiPrefs,
    onPhoto: () -> Unit, onVideo: () -> Unit, onAll: () -> Unit, actions: EntryActions,
    onSettings: (CensorSettings) -> Unit, onUi: ((UiPrefs) -> UiPrefs) -> Unit, onPrivacy: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(tr("מה תרצה לצנזר היום?"), color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text(tr("כל העיבוד מתבצע במכשיר שלך – שום דבר לא עולה לאינטרנט."), color = Palette.Ink300, fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                if (loading) CircularProgressIndicator(color = Palette.Shield400, modifier = Modifier.padding(24.dp))
                else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    KindTile(Icons.Filled.Image, tr("תמונה"), tr("אחת או כמה ביחד"), Modifier.weight(1f), onPhoto)
                    KindTile(Icons.Filled.VideoLibrary, tr("סרטון"), tr("אחד או כמה בתור"), Modifier.weight(1f), onVideo)
                }
            }
        }

        if (history.isNotEmpty()) {
            SectionCard(tr("פעולות אחרונות"), Icons.Filled.History) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    history.take(3).forEachIndexed { i, e -> RecentRow(e, i, actions) }
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onAll).padding(6.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tr("הצג הכל"), color = Palette.Ink200, fontSize = 14.sp)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.ExpandMore, null, tint = Palette.Ink300, modifier = Modifier.size(18.dp))
                }
            }
        }

        SectionCard(tr("הגדרות מהירות"), Icons.Outlined.Settings) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Veil).border(1.dp, Palette.Border, RoundedCornerShape(14.dp))
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QuickCell(Icons.Outlined.CheckCircle, tr("שמירה אוטומטית"), if (ui.autoSave) tr("מופעל") else tr("כבוי"), ui.autoSave, Modifier.weight(1f)) {
                    onUi { it.copy(autoSave = !it.autoSave) }
                }
                Box(Modifier.width(1.dp).height(36.dp).background(Palette.Border))
                QuickCell(Icons.Outlined.Description, tr("פורמט"), formatLabel(ui.photoFormat), false, Modifier.weight(1f)) {
                    onUi { it.copy(photoFormat = when (it.photoFormat) { "original" -> "jpg"; "jpg" -> "png"; else -> "original" }) }
                }
                Box(Modifier.width(1.dp).height(36.dp).background(Palette.Border))
                QuickCell(Icons.Filled.Hd, tr("איכות"), qualityLabel(settings.quality), false, Modifier.weight(1f)) {
                    onSettings(settings.copy(quality = when (settings.quality) { "high" -> "balanced"; "balanced" -> "small"; else -> "high" }))
                }
            }
        }

        PrivacyCard(onPrivacy)
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

@Composable
private fun KindTile(icon: ImageVector, title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(Palette.Veil).border(1.dp, Palette.Border, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(vertical = 18.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = Palette.Shield400, modifier = Modifier.size(42.dp))
        Spacer(Modifier.height(10.dp))
        Text(title, color = Palette.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(sub, color = Palette.Ink300, fontSize = 12.5.sp)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, tint = Palette.Ink300, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun QuickCell(icon: ImageVector, label: String, value: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = if (on) Palette.Shield400 else Palette.Ink300, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(6.dp))
        Column {
            Text(label, color = Palette.Ink200, fontSize = 12.5.sp, maxLines = 1)
            Text(value, color = if (on) Palette.Shield400 else Palette.Ink300, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** One recent result: picture, name, date and size, and a "⋮" menu. */
@Composable
fun RecentRow(e: History.Entry, index: Int, actions: EntryActions) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Veil).border(1.dp, Palette.Border, RoundedCornerShape(14.dp))
            .clickable { actions.open(index) }.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val bmp by produceState<Bitmap?>(null, e.thumb) { value = withContext(Dispatchers.IO) { History.decode(e.thumb, 300) } }
        Box(Modifier.width(72.dp).height(52.dp).clip(RoundedCornerShape(10.dp)).background(Palette.Ink700), contentAlignment = Alignment.Center) {
            bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            if (e.video) Box(Modifier.size(24.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, color = Palette.Text, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(
                ltr(SimpleDateFormat("dd.MM.yy", Locale.US).format(Date(e.time)) + " • " + formatBytes(File(e.file).length())) +
                    if (e.gallery != null) " • " + tr("בגלריה") else "",
                color = Palette.Ink400, fontSize = 12.sp,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, tr("אפשרויות"), tint = Palette.Ink300) }
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

/** "Your privacy comes first": the promise, with a link to the privacy page. */
@Composable
fun PrivacyCard(onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)).clickable(onClick = onOpen).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(tr("הפרטיות שלך היא בראש סדר העדיפויות"), color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(tr("הקבצים נשארים במכשיר שלך. לא נשלחים, לא נשמרים בשרת, לא נחשפים."), color = Palette.Ink300, fontSize = 12.5.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Palette.Shield400, Palette.Shield700))),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Lock, null, tint = Color.White, modifier = Modifier.size(24.dp)) }
    }
}
