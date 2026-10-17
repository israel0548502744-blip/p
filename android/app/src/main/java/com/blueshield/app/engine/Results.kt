package com.blueshield.app.engine

import android.content.Context
import com.blueshield.app.UiPrefs
import java.io.File

/**
 * What happens to every finished photo or video, from the screen or from the background service: it is kept in
 * the recent results ([History]) and, with auto-save on, written to the phone's gallery (an earlier version of the
 * same result is replaced in both).
 */
object Results {
    class Kept(val entry: History.Entry, val saveError: String?)

    fun keep(
        context: Context, file: File, video: Boolean, name: String, source: String?, replace: Long?,
        protect: Set<Long> = emptySet(),
    ): Kept {
        var e = History.add(context, file, video, name, source, replace, protect)
        var err: String? = null
        if (UiPrefs.load(context.getSharedPreferences("blueshield", Context.MODE_PRIVATE)).autoSave) {
            try {
                val uri = Exporter.saveOrReplace(context, File(e.file), galleryName(e), video, e.gallery)
                History.setGallery(context, e.id, uri.toString())
                e = e.copy(gallery = uri.toString())
            } catch (t: Throwable) {
                err = t.message ?: t.javaClass.simpleName
            }
        }
        return Kept(e, err)
    }

    fun galleryName(e: History.Entry) = e.name.substringBeforeLast('.') + "_blueshield." + File(e.file).extension
}
