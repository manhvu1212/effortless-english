package io.github.manhvu1212.effortless.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import io.github.manhvu1212.effortless.R
import java.io.ByteArrayOutputStream

/** The launcher icon as a PNG, for the car screen and the media notification. */
object Artwork {
    private const val SIZE = 320

    @Volatile
    private var cached: ByteArray? = null

    fun png(context: Context): ByteArray = cached ?: synchronized(this) {
        cached ?: render(context).also { cached = it }
    }

    private fun render(context: Context): ByteArray {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        for (layer in listOf(R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground)) {
            ContextCompat.getDrawable(context, layer)?.apply {
                setBounds(0, 0, SIZE, SIZE)
                draw(canvas)
            }
        }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }
}
