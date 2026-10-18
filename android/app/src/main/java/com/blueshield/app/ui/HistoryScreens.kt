package com.blueshield.app.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blueshield.app.AppViewModel
import com.blueshield.app.engine.History
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A small preview of a recent result, loaded off the main thread. */
@Composable
private fun Thumb(e: History.Entry, modifier: Modifier = Modifier, selected: Boolean = false) {
    val bmp by produceState<Bitmap?>(null, e.thumb) { value = withContext(Dispatchers.IO) { History.decode(e.thumb, 400) } }
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(Palette.Ink800)
            .border(if (selected) 2.dp else 1.dp, if (selected) Palette.Shield400 else Palette.Border, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (e.video) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
        if (e.gallery != null) {
            Icon(
                Icons.Filled.CheckCircle, tr("נשמר בגלריה"), tint = Palette.Emerald,
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(14.dp),
            )
        }
    }
}

/** The home screen's row of recent results, with a link to all of them. */
@Composable
fun RecentStrip(entries: List<History.Entry>, onOpen: (Int) -> Unit, onAll: () -> Unit) {
    if (entries.isEmpty()) return
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PhotoLibrary, null, tint = Palette.Shield400, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("האחרונים"), color = Palette.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(tr("הכול ({0})", entries.size), color = Palette.Shield400, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onAll).padding(6.dp))
            }
            Spacer(Modifier.height(8.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(entries.take(12), key = { _, e -> e.id }) { i, e ->
                    Thumb(e, Modifier.size(76.dp).clickable { onOpen(i) })
                }
            }
        }
    }
}

/** The "פרויקטים" tab: every recent result (the last 20 photos and 5 videos), filtered by kind or by "in the gallery". */
@Composable
fun HistoryScreen(
    entries: List<History.Entry>, autoSave: Boolean, onAutoSave: (Boolean) -> Unit,
    actions: EntryActions, onOpen: (List<Long>, Int) -> Unit, galleryOnly: Boolean = false,
) {
    var filter by remember(galleryOnly) { mutableStateOf(if (galleryOnly) "gallery" else "all") }
    val shown = when (filter) {
        "photos" -> entries.filter { !it.video }
        "videos" -> entries.filter { it.video }
        "gallery" -> entries.filter { it.gallery != null }
        else -> entries
    }
    val rowActions = EntryActions({ i -> onOpen(shown.map { it.id }, i) }, actions.save, actions.share, actions.whatsApp, actions.delete)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 6.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (galleryOnly) tr("הקבצים שלי") else tr("פרויקטים"), color = Palette.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            tr("{0} התמונות האחרונות ו-{1} הסרטונים האחרונים נשמרים כאן, גם אחרי סגירת האפליקציה. לחיצה פותחת, ואפשר לדפדף ביניהם.", History.MAX_PHOTOS, History.MAX_VIDEOS),
            color = Palette.Ink300, fontSize = 13.sp, lineHeight = 18.sp,
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((k, label) in listOf("all" to tr("הכול"), "photos" to tr("תמונות"), "videos" to tr("סרטונים"), "gallery" to tr("בגלריה"))) {
                val on = filter == k
                Text(
                    label, color = if (on) Color.White else Palette.Ink200, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, softWrap = false,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Palette.Shield500 else Palette.Ink850)
                        .border(1.dp, if (on) Palette.Shield400 else Palette.Border, RoundedCornerShape(50))
                        .clickable { filter = k }.padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
        Panel(Modifier.fillMaxWidth()) {
            if (shown.isEmpty()) Text(tr("עדיין אין כאן כלום. תמונות וסרטונים שתצנזרו יופיעו כאן."), color = Palette.Ink300, fontSize = 14.sp, modifier = Modifier.padding(20.dp))
            shown.forEachIndexed { i, e ->
                if (i > 0) androidx.compose.material3.HorizontalDivider(color = Palette.Border, modifier = Modifier.padding(horizontal = 14.dp))
                RecentRow(e, i, rowActions)
            }
        }
        ToggleRow(
            Icons.Filled.Download, tr("שמירה אוטומטית בגלריה"),
            tr("כל תמונה וסרטון שמסיימים לצנזר נשמרים מיד בגלריה של הטלפון (תיקייה BlueShield)."),
            autoSave, onAutoSave,
        )
    }
}

/**
 * Recent results one after another, full screen: swipe between them, double-tap or pinch to zoom, and save
 * (full size, every change included), share, edit or delete the one on screen. [batch]: the summary of a batch
 * that just ended, which adds "save all", "share all" and the batch's own buttons.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ViewerScreen(
    entries: List<History.Entry>, start: Int, batch: AppViewModel.BatchState?,
    onSave: (History.Entry) -> Unit, onShare: (History.Entry) -> Unit, onEdit: (History.Entry) -> Unit, onDelete: (History.Entry) -> Unit,
    onSaveAll: () -> Unit, onShareAll: () -> Unit, onClose: () -> Unit, onMore: (() -> Unit)?,
    onWhatsApp: ((History.Entry) -> Unit)? = null, onDeleteOriginal: ((History.Entry) -> Unit)? = null,
) {
    if (entries.isEmpty()) return
    val pager = rememberPagerState(initialPage = start.coerceIn(0, entries.lastIndex)) { entries.size }
    val scope = rememberCoroutineScope()
    var zoomed by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<History.Entry?>(null) }
    val strip = rememberLazyListState()
    LaunchedEffect(pager.currentPage) {
        zoomed = false
        strip.animateScrollToItem(pager.currentPage.coerceAtMost(entries.lastIndex))
    }
    val current = entries[pager.currentPage.coerceIn(0, entries.lastIndex)]

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            if (batch != null) {
                Text(
                    if (batch.saved == batch.ids.size && batch.ids.isNotEmpty()) tr("התמונות צונזרו ונשמרו בגלריה.") else tr("התמונות צונזרו."),
                    color = Palette.Text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                )
                if (batch.failed.isNotEmpty()) {
                    Text(tr("לא הצליחו ({0}): {1}", batch.failed.size, batch.failed.joinToString(", ")), color = Palette.Amber, fontSize = 12.5.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(current.name, color = Palette.Ink200, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
                Text(ltr("${pager.currentPage + 1} / ${entries.size}"), color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                (if (current.video) tr("סרטון") else tr("תמונה")) + " · " + ltr("${current.width}×${current.height}") + " · " +
                    SimpleDateFormat("d.M.yyyy HH:mm", Locale.getDefault()).format(Date(current.time)),
                color = Palette.Ink400, fontSize = 11.5.sp,
            )
        }
        HorizontalPager(
            pager, Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp), userScrollEnabled = !zoomed,
            pageSpacing = 12.dp, beyondViewportPageCount = 1, key = { entries[it].id },
        ) { page ->
            val e = entries[page]
            Box(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                if (e.video) {
                    if (page == pager.currentPage) {
                        SinglePlayer(Uri.fromFile(java.io.File(e.file)), e.width.toFloat() / e.height.coerceAtLeast(1), Modifier.fillMaxSize())
                    } else {
                        Thumb(e, Modifier.fillMaxWidth().aspectRatio(e.width.toFloat() / e.height.coerceAtLeast(1)))
                    }
                } else {
                    ZoomablePhoto(e, active = page == pager.currentPage, onZoom = { zoomed = it })
                }
            }
        }
        LazyRow(
            state = strip, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().height(58.dp),
        ) {
            itemsIndexed(entries, key = { _, e -> e.id }) { i, e ->
                Thumb(e, Modifier.size(54.dp).clickable { scope.launch { pager.animateScrollToPage(i) } }, selected = i == pager.currentPage)
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(
                if (current.gallery != null) tr("נשמר בגלריה") else tr("הורדה לגלריה (גודל מלא)"), Icons.Filled.Download,
                enabled = current.gallery == null, onClick = { onSave(current) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassOutlined(onClick = { onShare(current) }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Share, null); Spacer(Modifier.width(4.dp)); Text(tr("שיתוף")) }
                if (!current.video && current.source != null) {
                    GlassOutlined(onClick = { onEdit(current) }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Edit, null); Spacer(Modifier.width(4.dp)); Text(tr("עריכה")) }
                }
                GlassOutlined(onClick = { confirmDelete = current }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Delete, null); Spacer(Modifier.width(4.dp)); Text(tr("מחיקה")) }
            }
            if (onWhatsApp != null || (onDeleteOriginal != null && current.source != null)) {
                MoreActionsRow(ExtraActions(
                    onWhatsApp = { onWhatsApp?.invoke(current) }, onReport = {}, showReport = false,
                    onDeleteOriginal = if (onDeleteOriginal != null && current.source != null) { { onDeleteOriginal(current) } } else null,
                ))
            }
            if (batch != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val unsaved = entries.count { it.gallery == null }
                    if (unsaved > 0) {
                        GlassOutlined(onClick = onSaveAll, modifier = Modifier.weight(1f)) { Text(tr("שמירת הכול ({0})", unsaved)) }
                    }
                    GlassOutlined(onClick = onShareAll, modifier = Modifier.weight(1f)) { Text(tr("שיתוף הכול")) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    onMore?.let { more -> GlassOutlined(onClick = more, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Image, null); Spacer(Modifier.width(4.dp)); Text(tr("עוד תמונות")) } }
                    GlassOutlined(onClick = onClose, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.CheckCircle, null); Spacer(Modifier.width(4.dp)); Text(tr("סיום")) }
                }
            }
        }
    }

    confirmDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = Palette.Ink850, shape = RoundedCornerShape(26.dp),
            title = { Text(tr("למחוק מהאחרונים?"), color = Palette.Text) },
            text = {
                Text(
                    if (e.gallery != null) tr("העותק שנשמר בגלריה של הטלפון נשאר שם.") else tr("הקובץ עדיין לא נשמר בגלריה, והוא יימחק לגמרי."),
                    color = Palette.Ink200, fontSize = 13.sp,
                )
            },
            confirmButton = { TextButton(onClick = { confirmDelete = null; onDelete(e) }) { Text(tr("מחיקה"), color = Palette.Danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(tr("ביטול")) } },
        )
    }
}

/** A photo at screen size; pinch or double-tap to zoom in and see the edges up close. */
@Composable
private fun ZoomablePhoto(e: History.Entry, active: Boolean, onZoom: (Boolean) -> Unit) {
    val bmp by produceState<Bitmap?>(null, e.file) { value = withContext(Dispatchers.IO) { History.decode(e.file, 2048) } }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(active) {
        if (!active) {
            scale = 1f
            offset = Offset.Zero
        }
    }
    val b = bmp ?: run {
        Thumb(e, Modifier.fillMaxWidth().aspectRatio(e.width.toFloat() / e.height.coerceAtLeast(1)))
        return
    }
    Image(
        b.asImageBitmap(), null, contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    scale = if (scale > 1f) 1f else 2.5f
                    offset = Offset.Zero
                    onZoom(scale > 1f)
                })
            }
            .pointerInput(Unit) {
                // two fingers zoom; one finger pans only once zoomed in (otherwise it swipes to the next photo)
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1 || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            offset = if (scale == 1f) Offset.Zero else offset + event.calculatePan()
                            onZoom(scale > 1f)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
            .clip(RoundedCornerShape(14.dp)),
    )
}
