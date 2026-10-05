package com.blueshield.app.ui

import android.graphics.Matrix
import android.net.Uri
import android.view.TextureView
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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

/** Plays a single video (preview before processing). */
@Composable
fun SinglePlayer(uri: Uri, aspect: Float, modifier: Modifier = Modifier) {
    val player = rememberPlayer(uri, muted = false) ?: return
    Column(modifier) {
        Box(Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(14.dp)).background(Color.Black)) {
            VideoSurface(player, Modifier.fillMaxSize())
        }
        Transport(player, null)
    }
}

/**
 * Original vs censored: wipe slider, split (stacked) or censored only. Both players are
 * driven by one transport; the original follows the censored player's clock.
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
    var wipe by remember { mutableFloatStateOf(0.5f) }
    Column(modifier) {
        when (mode) {
            CompareMode.WIPE -> BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(14.dp)).background(Color.Black)) {
                val full = maxWidth
                VideoSurface(master, Modifier.fillMaxSize())
                Box(Modifier.fillMaxHeight().width(full * wipe).clipToBounds()) {
                    VideoSurface(follower, Modifier.requiredWidth(full).fillMaxHeight().align(Alignment.CenterStart))
                }
                Label("ORIGINAL", false, Modifier.align(Alignment.TopStart).padding(10.dp))
                Label("CENSORED", true, Modifier.align(Alignment.TopEnd).padding(10.dp))
                val density = LocalDensity.current
                Box(
                    Modifier.fillMaxHeight().width(44.dp).offset(x = full * wipe - 22.dp)
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures { _, dx ->
                                wipe = (wipe + dx / with(density) { full.toPx() }).coerceIn(0f, 1f)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.fillMaxHeight().width(2.dp).background(Color.White))
                    Box(Modifier.size(38.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.SwapHoriz, null, tint = Palette.Ink950)
                    }
                }
            }
            CompareMode.SPLIT -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(14.dp)).background(Color.Black)) {
                    VideoSurface(follower, Modifier.fillMaxSize())
                    Label("ORIGINAL", false, Modifier.align(Alignment.TopStart).padding(10.dp))
                }
                Box(Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(14.dp)).background(Color.Black)) {
                    VideoSurface(master, Modifier.fillMaxSize())
                    Label("CENSORED", true, Modifier.align(Alignment.TopEnd).padding(10.dp))
                }
            }
            CompareMode.CENSORED -> Box(Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(14.dp)).background(Color.Black)) {
                VideoSurface(master, Modifier.fillMaxSize())
                // keep the follower attached (and paused in sync) without showing it
                Box(Modifier.size(1.dp)) { VideoSurface(follower, Modifier.size(1.dp)) }
                Label("CENSORED", true, Modifier.align(Alignment.TopEnd).padding(10.dp))
            }
        }
        Transport(master, timeline)
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

@Composable
private fun Transport(player: ExoPlayer, timeline: List<Float>?) {
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(player) {
        while (true) {
            pos = player.currentPosition
            dur = player.duration.coerceAtLeast(0)
            playing = player.isPlaying
            if (player.playbackState == Player.STATE_ENDED && !player.isPlaying) playing = false
            delay(100)
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        if (!timeline.isNullOrEmpty()) {
            val mx = timeline.maxOrNull()?.takeIf { it > 0f } ?: 1f
            Row(Modifier.fillMaxWidth().height(12.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.Bottom) {
                for (v in timeline) Box(
                    Modifier.weight(1f).fillMaxHeight(if (v > 0) (0.15f + 0.85f * v / mx).coerceAtMost(1f) else 0.001f)
                        .background(Palette.Shield500.copy(alpha = if (v > 0) 0.4f + 0.6f * v / mx else 0f)),
                )
            }
        }
        Slider(
            value = dragging ?: if (dur > 0) pos.toFloat() / dur else 0f,
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { player.seekTo((it * dur).toLong()) }; dragging = null },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Palette.Shield400, inactiveTrackColor = Palette.Ink600),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    if (player.isPlaying) player.pause() else {
                        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                        player.play()
                    }
                },
                modifier = Modifier.size(40.dp).clip(CircleShape).background(Color.White),
            ) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Palette.Ink950) }
            Spacer(Modifier.width(12.dp))
            Text("${formatDuration(pos / 1000.0)} / ${formatDuration(dur / 1000.0)}", color = Palette.Ink200, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp)
        }
    }
}

/** Tap target helper used by the result screen (keeps unused import warnings away). */
fun Modifier.onTap(action: () -> Unit) = pointerInput(Unit) { detectTapGestures { action() } }
