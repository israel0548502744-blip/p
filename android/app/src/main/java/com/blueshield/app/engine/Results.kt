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
    /** [wrote]: this version was written to the gallery now (auto-save); without it the entry has no gallery copy. */
    class Kept(val entry: History.Entry, val saveError: String?, val wrote: Boolean = false)

    fun keep(
        context: Context, file: File, video: Boolean, name: String, source: String?, replace: Long?,
        protect: Set<Long> = emptySet(),
    ): Kept {
        var e = History.add(context, file, video, name, source, replace, protect)
        var err: String? = null
        var wrote = false
        if (UiPrefs.load(context.getSharedPreferences("blueshield", Context.MODE_PRIVATE)).autoSave) {
            try {
                val uri = Exporter.saveOrReplace(context, File(e.file), galleryName(e), video, e.gallery)
                History.setGallery(context, e.id, uri.toString())
                e = e.copy(gallery = uri.toString())
                wrote = true
            } catch (t: Throwable) {
                err = t.message ?: t.javaClass.simpleName
            }
        } else if (e.gallery != null) {
            // a re-render inherits the earlier version's gallery copy: this version is not saved (nothing is written
            // without auto-save), so it must not show as saved
            History.setGallery(context, e.id, null)
            e = e.copy(gallery = null)
        }
        return Kept(e, err, wrote)
    }

    fun galleryName(e: History.Entry) = e.name.substringBeforeLast('.') + "_blueshield." + File(e.file).extension
}
