package com.blueshield.app.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny on-disk trail of what the processing pipeline was doing. If the process dies from a
 * native crash (no Java stack trace), the next launch shows the last steps so the cause can be located.
 */
object Breadcrumbs {
    private var file: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        file = File(context.filesDir, "breadcrumbs.log")
    }

    @Synchronized fun reset(header: String) {
        runCatching { file?.writeText("") }
        mark(header)
    }

    @Synchronized fun mark(msg: String) {
        Log.i("BlueShield", msg)
        val f = file ?: return
        runCatching {
            if (f.length() > 64 * 1024) f.writeText("")
            f.appendText("${fmt.format(Date())}  $msg\n")
        }
    }

    fun tail(lines: Int = 40): String =
        runCatching { file?.takeIf { it.exists() }?.readLines()?.takeLast(lines)?.joinToString("\n") }.getOrNull() ?: "(no breadcrumbs)"
}
