package com.blueshield.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import com.blueshield.core.image.RgbImage
import kotlin.math.max

/** Loads a photo upright (EXIF orientation applied), capped at [maxSide] px on its long side. */
object PhotoLoader {
    fun load(context: Context, uri: Uri, maxSide: Int = 4096): Bitmap {
        val cr = context.contentResolver
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri)) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                d.isMutableRequired = true
                val long = max(info.size.width, info.size.height)
                if (long > maxSide) {
                    val k = maxSide.toFloat() / long
                    d.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1), (info.size.height * k).toInt().coerceAtLeast(1))
                }
            }.let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, true) }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val bmp = cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
        } ?: error("Could not read this photo.")
        val rotation = runCatching {
            cr.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull()
        val deg = when (rotation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg == 0f) return if (bmp.isMutable) bmp else bmp.copy(Bitmap.Config.ARGB_8888, true)
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
            .copy(Bitmap.Config.ARGB_8888, true)
    }

    /** Area-averaged RGB copy at [w] × [h] (the analysis size). */
    fun toRgb(bmp: Bitmap, w: Int, h: Int): RgbImage {
        val scaled = if (bmp.width == w && bmp.height == h) bmp else Bitmap.createScaledBitmap(bmp, w, h, true)
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        val out = RgbImage(w, h)
        for (i in 0 until w * h) {
            val p = px[i]
            out.data[i * 3] = (p shr 16).toByte()
            out.data[i * 3 + 1] = (p shr 8).toByte()
            out.data[i * 3 + 2] = p.toByte()
        }
        return out
    }
}
