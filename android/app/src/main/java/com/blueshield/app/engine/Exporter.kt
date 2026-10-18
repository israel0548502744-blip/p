package com.blueshield.app.engine

import com.blueshield.app.ui.tr

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** Saves to the phone's gallery (Movies/BlueShield) through MediaStore, and shares via FileProvider. */
object Exporter {
    fun saveToGallery(context: Context, file: File, displayName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/BlueShield")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values) ?: error(tr("לא ניתן ליצור קובץ בגלריה"))
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 20) } } ?: error(tr("לא ניתן לפתוח את הקובץ בגלריה"))
            if (Build.VERSION.SDK_INT >= 29) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    /** Saves a censored photo to the gallery (Pictures/BlueShield). */
    fun savePhotoToGallery(context: Context, file: File, displayName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mime(file))
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/BlueShield")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values) ?: error(tr("לא ניתן ליצור קובץ בגלריה"))
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 20) } } ?: error(tr("לא ניתן לפתוח את הקובץ בגלריה"))
            if (Build.VERSION.SDK_INT >= 29) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    /**
     * Saves [file] to the gallery, or, when [existing] (an earlier save of the same result) is still there, writes
     * over it: re-rendering with per-person changes then doesn't leave two copies in the gallery.
     */
    fun saveOrReplace(context: Context, file: File, displayName: String, video: Boolean, existing: String?): Uri {
        if (existing != null) {
            val uri = Uri.parse(existing)
            val replaced = runCatching {
                context.contentResolver.openOutputStream(uri, "wt")?.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 20) } } != null
            }.getOrDefault(false)
            if (replaced) return uri
            // can't write over it: the new copy takes its place (not one more copy next to it)
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
        return if (video) saveToGallery(context, file, displayName) else savePhotoToGallery(context, file, displayName)
    }

    /** Opens a finished video in the phone's own player. */
    fun viewIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Several results at once (the batch viewer's "share all"). */
    fun shareManyIntent(context: Context, files: List<File>): Intent {
        val uris = ArrayList(files.map { FileProvider.getUriForFile(context, context.packageName + ".files", it) })
        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = files.map(::mime).distinct().singleOrNull() ?: if (files.all { mime(it).startsWith("image/") }) "image/*" else "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.let { Intent.createChooser(it, null) }
    }

    fun mime(file: File) = when (file.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        else -> "video/mp4"
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = mime(file)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.let { Intent.createChooser(it, null) }
    }
}
