@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.media3.exoplayer

import androidx.media3.common.Player

interface ExoPlayer : Player {
    class Builder(context: android.content.Context) { fun build(): ExoPlayer = error("stub") }
}
