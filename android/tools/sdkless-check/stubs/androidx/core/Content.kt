@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.core.content

import android.content.Context
import android.net.Uri
import java.io.File

open class FileProvider : android.content.ContentProvider() {
    companion object {
        @JvmStatic fun getUriForFile(context: Context, authority: String, file: File): Uri = error("stub")
    }
    override fun onCreate() = true
    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): android.database.Cursor? = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: android.content.ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<String>?) = 0
    override fun update(u: Uri, v: android.content.ContentValues?, s: String?, a: Array<String>?) = 0
}
