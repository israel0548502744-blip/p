package com.blueshield.app.ui

import android.graphics.Matrix
import android.net.Uri
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlin.math.abs

enum class CompareMode { WIPE, SPLIT, CENSORED }

@Composable
private fun rememberPlayer(uri: Uri?, muted: Boolean): ExoPlayer? {
    val context = LocalContext.current
    val player = remember(uri) {
        uri?.let { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(it)); repeatMode = Player.REPEAT_MODE_OFF; prepare() } }
    }
    LaunchedEffect(player, muted) { player?.volume = if (muted) 0f else 1f }
    DisposableEffect(player) { onDispose { player?.release() } }
    return player
}

/** A TextureView bound to [player]; applies any rotation the decoder didn't (TextureView needs it done manually). */
@Composable
private fun VideoSurface(player: ExoPlayer, modifier: Modifier) {
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).also { tv ->
                player.setVideoTextureView(tv)
                player.addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) = applyRotation(tv, videoSize)
                })
                tv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyRotation(tv, player.videoSize) }
            }
        },
        modifier = modifier,
    )
}

@Suppress("DEPRECATION")
private fun applyRotation(tv: TextureView, size: VideoSize) {
    val rot = size.unappliedRotationDegrees
    if (rot == 0 || tv.width == 0) {
        tv.setTransform(null)
        return
    }
    val w = tv.width.toFloat()
    val h = tv.height.toFloat()
    val m = Matrix()
    m.postRotate(rot.toFloat(), w / 2, h / 2)
    if (rot % 180 != 0) m.postScale(w / h, h / w, w / 2, h / 2)
    tv.setTransform(m)
}

/** The shape the video really has on screen (rotation and pixel shape included), once the player knows it. */
@Composable
private fun rememberAspect(player: ExoPlayer, fallback: Float): Float {
    var aspect by remember(player) { mutableFloatStateOf(fallback) }
    DisposableEffect(player) {
        fun update(v: VideoSize) {
            if (v.width <= 0 || v.height <= 0) return
            @Suppress("DEPRECATION")
            val turned = v.unappliedRotationDegrees % 180 != 0
            val a = v.width * v.pixelWidthHeightRatio / v.height
            aspect = if (turned) 1f / a else a
        }
        update(player.videoSize)
        val l = object : Player.Listener { override fun onVideoSizeChanged(videoSize: VideoSize) = update(videoSize) }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    return aspect.takeIf { it.isFinite() && it > 0f } ?: 1f
}

/**
 * The video's frame: as wide as it can be while still fitting the height it is given (or, in a scrolling page,
 * at most [maxHeightFraction] of the screen), so the controls on it are always on screen.
 */
@Composable
private fun FittedFrame(aspect: Float, modifier: Modifier, maxHeightFraction: Float = 0.62f, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val maxH = if (constraints.hasBoundedHeight) minOf(maxHeight, screenH * maxHeightFraction) else screenH * maxHeightFraction
        val w = minOf(maxWidth, maxH * aspect)
        Box(Modifier.width(w).height(w / aspect).clip(RoundedCornerShape(16.dp)).background(Color.Black), content = content)
    }
}

/** Plays a single video: tap to play or pause, the time and a thin seek bar along the bottom of the picture. */
@Composable
fun SinglePlayer(uri: Uri, aspect: Float, modifier: Modifier = Modifier) {
    val player = rememberPlayer(uri, muted = false) ?: return
    val a = rememberAspect(player, aspect)
    FittedFrame(a, modifier) {
        VideoSurface(player, Modifier.fillMaxSize())
        Controls(player, null)
    }
}

/**
 * Original vs censored: wipe slider, split (stacked) or censored only. Both players are
 * driven by one set of controls; the original follows the censored player's clock.
 */
@Composable
fun ComparePlayer(original: Uri, censored: Uri, aspect: Float, mode: CompareMode, timeline: List<Float>?, modifier: Modifier = Modifier) {
    val master = rememberPlayer(censored, muted = false) ?: return
    val follower = rememberPlayer(original, muted = true) ?: return
    LaunchedEffect(master, follower) {
        while (true) {
            if (master.isPlaying != follower.playWhenReady) follower.playWhenReady = master.isPlaying
            if (abs(follower.currentPosition - master.currentPosition) > 120) follower.seekTo(master.currentPosition)
            delay(80)
        }
    }
    val a = rememberAspect(master, aspect)
    var wipe by remember { mutableFloatStateOf(0.5f) }
    when (mode) {
        CompareMode.WIPE -> FittedFrame(a, modifier) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val full = maxWidth
                VideoSurface(master, Modifier.fillMaxSize())
                Box(Modifier.fillMaxHeight().width(full * wipe).clipToBounds()) {
                    VideoSurface(follower, Modifier.wrapContentWidth(Alignment.Start, unbounded = true).requiredWidth(full).fillMaxHeight())
                }
                Controls(master, timeline)
                Label(tr("מקור"), false, Modifier.align(Alignment.TopStart).padding(10.dp))
                Label(tr("מצונזר"), true, Modifier.align(Alignment.TopEnd).padding(10.dp))
                val density = LocalDensity.current
                // the original is revealed from the start edge (the right in RTL), so a drag toward the end grows it
                val towardEnd = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
                Box(
                    Modifier.fillMaxHeight(0.8f).width(44.dp).offset(x = full * wipe - 22.dp)
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures { _, dx ->
                                wipe = (wipe + towardEnd * dx / with(density) { full.toPx() }).coerceIn(0f, 1f)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.fillMaxHeight().width(2.dp).background(Color.White))
                    Box(Modifier.size(34.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.SwapHoriz, null, tint = Color(0xFF0B0A19))
                    }
                }
            }
        }
        CompareMode.SPLIT -> Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FittedFrame(a, Modifier, maxHeightFraction = 0.4f) {
                VideoSurface(follower, Modifier.fillMaxSize())
                Label(tr("מקור"), false, Modifier.align(Alignment.TopStart).padding(10.dp))
            }
            FittedFrame(a, Modifier, maxHeightFraction = 0.4f) {
                VideoSurface(master, Modifier.fillMaxSize())
                Controls(master, timeline)
                Label(tr("מצונזר"), true, Modifier.align(Alignment.TopEnd).padding(10.dp))
            }
        }
        CompareMode.CENSORED -> FittedFrame(a, modifier) {
            VideoSurface(master, Modifier.fillMaxSize())
            // keep the follower attached (and paused in sync) without showing it
            Box(Modifier.size(1.dp)) { VideoSurface(follower, Modifier.size(1.dp)) }
            Controls(master, timeline)
        }
    }
}

@Composable
private fun Label(text: String, accent: Boolean, modifier: Modifier) {
    Text(
        text, color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        modifier = modifier.clip(RoundedCornerShape(50)).background(if (accent) Palette.Shield600.copy(alpha = 0.85f) else Color(0x8C000000))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/**
 * The controls drawn on the picture, as in the design: a round play button in the middle while paused (a tap on
 * the picture plays or pauses), the time at the bottom corner and a thin seek bar. Time runs left → right also in
 * RTL, as on every media player.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.Controls(player: ExoPlayer, timeline: List<Float>?) {
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(player) {
        while (true) {
            pos = player.currentPosition
            dur = player.duration.coerceAtLeast(0)
            playing = player.isPlaying
            delay(100)
        }
    }
    fun toggle() {
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
    }
    Box(Modifier.matchParentSize().pointerInput(player) { detectTapGestures { toggle() } })
    if (!playing) Box(
        Modifier.align(Alignment.Center).size(64.dp).clip(CircleShape).background(Color(0x73000000))
            .border(1.dp, Color(0x55FFFFFF), CircleShape).pointerInput(player) { detectTapGestures { toggle() } },
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Filled.PlayArrow, tr("הפעלה"), tint = Color.White, modifier = Modifier.size(38.dp)) }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000))))
                .padding(horizontal = 10.dp).padding(top = 18.dp, bottom = 6.dp),
        ) {
            if (!timeline.isNullOrEmpty()) {
                val mx = timeline.maxOrNull()?.takeIf { it > 0f } ?: 1f
                Row(Modifier.fillMaxWidth().height(8.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.Bottom) {
                    for (v in timeline) Box(
                        Modifier.weight(1f).fillMaxHeight(if (v > 0) (0.15f + 0.85f * v / mx).coerceAtMost(1f) else 0.001f)
                            .background(Palette.Shield300.copy(alpha = if (v > 0) 0.4f + 0.6f * v / mx else 0f)),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Color.White,
                    modifier = Modifier.size(28.dp).clip(CircleShape).pointerInput(player) { detectTapGestures { toggle() } }.padding(2.dp),
                )
                Spacer(Modifier.width(6.dp))
                val shown = dragging ?: if (dur > 0) pos.toFloat() / dur else 0f
                SeekBar(shown, Modifier.weight(1f), onSeek = { dragging = it }, onDone = { dragging?.let { player.seekTo((it * dur).toLong()) }; dragging = null })
                Spacer(Modifier.width(8.dp))
                Text(
                    "${formatDuration((dragging?.times(dur) ?: pos.toFloat()) / 1000.0)} / ${formatDuration(dur / 1000.0)}",
                    color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp,
                )
            }
        }
    }
}

/** A thin seek bar with a small round handle (the Material slider is too large to sit on a picture). */
@Composable
private fun SeekBar(value: Float, modifier: Modifier, onSeek: (Float) -> Unit, onDone: () -> Unit) {
    var width by remember { mutableFloatStateOf(1f) }
    androidx.compose.foundation.Canvas(
        modifier.height(28.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1).toFloat() }
            .pointerInput(Unit) { detectTapGestures { onSeek((it.x / width).coerceIn(0f, 1f)); onDone() } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = onDone, onDragCancel = onDone,
                    onHorizontalDrag = { change, _ -> change.consume(); onSeek((change.position.x / width).coerceIn(0f, 1f)) },
                )
            },
    ) {
        val y = size.height / 2
        val x = size.width * value.coerceIn(0f, 1f)
        drawLine(Color(0x55FFFFFF), Offset(0f, y), Offset(size.width, y), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        drawLine(Palette.Shield400, Offset(0f, y), Offset(x, y), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(Color.White, 6.dp.toPx(), Offset(x, y))
    }
}
