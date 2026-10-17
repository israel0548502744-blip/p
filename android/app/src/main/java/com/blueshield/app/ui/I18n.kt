package com.blueshield.app.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/**
 * The app's languages. Hebrew is the source language: every text is written in Hebrew in the code and passed
 * through [tr], which looks it up in the chosen language's table (assets/i18n/<code>.json, keyed by the Hebrew
 * text). A text missing from a table shows in Hebrew.
 */
object I18n {
    class Lang(val code: String, val name: String, val rtl: Boolean)

    val LANGS = listOf(
        Lang("he", "עברית", true),
        Lang("en", "English", false),
        Lang("ar", "العربية", true),
        Lang("ru", "Русский", false),
        Lang("fr", "Français", false),
        Lang("es", "Español", false),
    )

    /** Read by [tr], so every text on screen is redrawn when the language changes. */
    var lang by mutableStateOf("he")
        private set
    @Volatile private var table: Map<String, String> = emptyMap()

    val rtl: Boolean get() = LANGS.firstOrNull { it.code == lang }?.rtl ?: true

    fun load(context: Context, code: String) {
        table = if (code == "he") emptyMap() else runCatching {
            val o = JSONObject(context.assets.open("i18n/$code.json").bufferedReader().use { it.readText() })
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())
        lang = if (LANGS.any { it.code == code }) code else "he"
    }

    internal fun lookup(he: String): String = table[he] ?: he
}

/** The Hebrew text [he] in the chosen language; {0}, {1}… are replaced by [args]. */
fun tr(he: String, vararg args: Any?): String {
    @Suppress("UNUSED_VARIABLE") val l = I18n.lang
    var s = I18n.lookup(he)
    args.forEachIndexed { i, a -> s = s.replace("{$i}", a.toString()) }
    return s
}
