@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.media3.common

class MediaItem { companion object { @JvmStatic fun fromUri(uri: android.net.Uri): MediaItem = error("stub") } }
class VideoSize(val width: Int, val height: Int, val unappliedRotationDegrees: Int)
interface Player {
    interface Listener { fun onVideoSizeChanged(videoSize: VideoSize) {} }
    var playWhenReady: Boolean
    var repeatMode: Int
    var volume: Float
    val isPlaying: Boolean
    val currentPosition: Long
    val duration: Long
    val playbackState: Int
    val videoSize: VideoSize
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun prepare()
    fun release()
    fun setMediaItem(item: MediaItem)
    fun addListener(l: Listener)
    fun setVideoTextureView(view: android.view.TextureView?)
    companion object {
        const val REPEAT_MODE_OFF = 0
        const val STATE_ENDED = 4
    }
}
