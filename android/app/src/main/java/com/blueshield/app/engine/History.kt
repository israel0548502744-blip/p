package com.blueshield.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max

/**
 * The app's own gallery of recent results ("האחרונים"): the last [MAX_PHOTOS] censored photos and the last
 * [MAX_VIDEOS] censored videos, kept in the app's private storage so they survive closing the app and can be
 * viewed, saved or shared again. Each result is a hard link to the output file (no copy) when the file system
 * allows it.
 */
object History {
    const val MAX_PHOTOS = 20
    const val MAX_VIDEOS = 5

    data class Entry(
        val id: Long,
        val video: Boolean,
        val name: String,
        val file: String,
        val thumb: String,
        val width: Int,
        val height: Int,
        val time: Long,
        /** Where the original came from: lets a photo be opened again for per-person changes. */
        val source: String? = null,
        /** The copy in the phone's gallery (MediaStore uri), once saved. */
        val gallery: String? = null,
    )

    private val lock = Any()

    private fun dir(context: Context) = File(context.filesDir, "history").also { it.mkdirs() }
    private fun index(context: Context) = File(dir(context), "index.json")

    fun list(context: Context): List<Entry> = synchronized(lock) {
        val f = index(context)
        if (!f.exists()) return emptyList()
        runCatching {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Entry(
                    o.getLong("id"), o.getBoolean("video"), o.getString("name"), o.getString("file"), o.getString("thumb"),
                    o.getInt("width"), o.getInt("height"), o.getLong("time"),
                    o.optString("source").ifEmpty { null }, o.optString("gallery").ifEmpty { null },
                )
            }
        }.getOrDefault(emptyList()).filter { File(it.file).exists() }
    }

    private fun write(context: Context, entries: List<Entry>) {
        val f = index(context)
        val tmp = File(f.path + ".tmp")
        val a = JSONArray()
        for (e in entries) {
            a.put(
                JSONObject().put("id", e.id).put("video", e.video).put("name", e.name).put("file", e.file).put("thumb", e.thumb)
                    .put("width", e.width).put("height", e.height).put("time", e.time)
                    .put("source", e.source ?: "").put("gallery", e.gallery ?: ""),
            )
        }
        tmp.writeText(a.toString())
        tmp.renameTo(f)
    }

    /**
     * Keeps [output] (a finished photo or video) as a recent result. [replace]: the entry of an earlier version of
     * the same result (re-rendered with per-person changes), which this one takes the place of. Entries in
     * [protect] (a batch still on screen) are never trimmed.
     */
    fun add(
        context: Context, output: File, video: Boolean, name: String, source: String?,
        replace: Long? = null, protect: Set<Long> = emptySet(),
    ): Entry = synchronized(lock) {
        val id = System.currentTimeMillis().let { t -> max(t, (list(context).maxOfOrNull { it.id } ?: 0) + 1) }
        val dst = File(dir(context), "$id." + output.extension)
        link(output, dst)
        val thumbBmp = if (video) videoThumb(dst) else photoThumb(dst)
        val thumb = File(dir(context), "$id.thumb.jpg")
        thumb.outputStream().use { thumbBmp?.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        val (w, h) = if (video) videoSize(dst) else photoSize(dst)
        val old = list(context)
        val previous = old.firstOrNull { it.id == replace }
        val entry = Entry(id, video, name, dst.path, thumb.path, w, h, System.currentTimeMillis(), source ?: previous?.source, previous?.gallery)
        var entries = listOf(entry) + old.filter { it.id != replace }
        previous?.let { deleteFiles(it) }
        entries = trim(entries, protect)
        write(context, entries)
        entry
    }

    private fun trim(entries: List<Entry>, protect: Set<Long>): List<Entry> {
        val keep = ArrayList<Entry>()
        var photos = 0
        var videos = 0
        for (e in entries) {
            val n = if (e.video) ++videos else ++photos
            if (n <= (if (e.video) MAX_VIDEOS else MAX_PHOTOS) || e.id in protect) keep += e else deleteFiles(e)
        }
        return keep
    }

    /** Re-applies the limits once a batch no longer needs all its photos. */
    fun trim(context: Context) = synchronized(lock) { write(context, trim(list(context), emptySet())) }

    fun setGallery(context: Context, id: Long, uri: String?) = synchronized(lock) {
        write(context, list(context).map { if (it.id == id) it.copy(gallery = uri) else it })
    }

    fun delete(context: Context, id: Long) = synchronized(lock) {
        val all = list(context)
        all.firstOrNull { it.id == id }?.let { deleteFiles(it) }
        write(context, all.filter { it.id != id })
    }

    private fun deleteFiles(e: Entry) {
        File(e.file).delete()
        File(e.thumb).delete()
    }

    private fun link(src: File, dst: File) {
        dst.delete()
        val linked = runCatching { android.system.Os.link(src.path, dst.path); true }.getOrDefault(false)
        if (!linked) src.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
    }

    /** A photo decoded to at most [maxSide] px on its long side (the viewer never needs the full 12 MP). */
    fun decode(path: String, maxSide: Int): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, b)
        var sample = 1
        while (max(b.outWidth, b.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun photoThumb(f: File): Bitmap? = decode(f.path, 640)?.let { scaled(it, 360) }

    private fun photoSize(f: File): Pair<Int, Int> {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, b)
        return b.outWidth to b.outHeight
    }

    private fun videoThumb(f: File): Bitmap? = runCatching {
        MediaMetadataRetriever().run {
            try {
                setDataSource(f.path)
                getFrameAtTime(0)?.let { scaled(it, 360) }
            } finally {
                release()
            }
        }
    }.getOrNull()

    private fun videoSize(f: File): Pair<Int, Int> = runCatching {
        MediaMetadataRetriever().run {
            try {
                setDataSource(f.path)
                val w = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 16
                val h = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 9
                val rot = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
                if (rot % 180 != 0) h to w else w to h
            } finally {
                release()
            }
        }
    }.getOrDefault(16 to 9)

    private fun scaled(b: Bitmap, side: Int): Bitmap {
        val k = side.toFloat() / max(b.width, b.height)
        if (k >= 1f) return b
        return Bitmap.createScaledBitmap(b, max(1, (b.width * k).toInt()), max(1, (b.height * k).toInt()), true)
    }

    /** Output files (filesDir/outputs) left from earlier runs: everything kept is linked into the history. */
    fun cleanOutputs(context: Context, keep: File?) {
        val cutoff = System.currentTimeMillis() - 6 * 3600_000L
        File(context.filesDir, "outputs").listFiles()?.forEach { f ->
            if (f != keep && f.lastModified() < cutoff) f.delete()
        }
    }
}
