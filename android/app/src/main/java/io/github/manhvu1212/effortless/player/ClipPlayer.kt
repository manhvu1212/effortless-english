package io.github.manhvu1212.effortless.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

/** Plays the sentence a flashcard was saved from, apart from the lesson player. */
class ClipPlayer(private val context: Context) {
    private var player: ExoPlayer? = null

    fun play(audioUri: String, startMs: Long?, endMs: Long?) {
        val clipping = MediaItem.ClippingConfiguration.Builder().apply {
            if (startMs != null) setStartPositionMs(startMs)
            if (endMs != null) setEndPositionMs(endMs)
        }.build()
        val item = MediaItem.Builder().setUri(Uri.parse(audioUri)).setClippingConfiguration(clipping).build()
        val p = player ?: ExoPlayer.Builder(context).build().also { player = it }
        p.setMediaItem(item)
        p.prepare()
        p.play()
    }

    fun release() {
        player?.release()
        player = null
    }
}
