package com.blueshield.app

import android.content.SharedPreferences
import com.blueshield.app.ui.Palette
import com.blueshield.core.CensorSettings
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

/** How the app looks and behaves (not how it censors: that is [CensorSettings]). Saved in the app's preferences. */
data class UiPrefs(
    /** "dark" | "light" | "system". */
    val theme: String = "dark",
    /** The accent colour, ARGB. */
    val accent: Long = Palette.DEFAULT_ACCENT,
    /** An [com.blueshield.app.ui.I18n] language code; Hebrew is the source language. */
    val language: String = "he",
    /** Text size factor on top of the phone's own font size. */
    val textScale: Float = 1f,
    /** Saved photos: "original" (PNG stays PNG, the rest JPEG), "jpg" or "png". */
    val photoFormat: String = "original",
    /** Ask whom to censor once the people are found, before the censoring is drawn (the render is skipped until then). */
    val askWho: Boolean = false,
    /** Off unless switched on: results stay in the app's recent files until saved. */
    val autoSave: Boolean = false,
    /** The welcome pages were shown. */
    val onboarded: Boolean = false,
    /** The version whose "what's new" was last shown. */
    val seenVersion: String = "",
    /** The user's name (the greeting, the side menu) and an e-mail shown under it; both stay on the phone. */
    val name: String = "",
    val email: String = "",
) {
    fun save(p: SharedPreferences) = p.edit()
        .putString("ui.theme", theme).putLong("ui.accent", accent).putString("ui.language", language).putFloat("ui.textScale", textScale)
        .putString("ui.photoFormat", photoFormat).putBoolean("ui.askWho", askWho).putBoolean("autoSave", autoSave)
        .putBoolean("ui.onboarded", onboarded).putString("ui.seenVersion", seenVersion)
        .putString("ui.name", name).putString("ui.email", email).apply()

    companion object {
        fun load(p: SharedPreferences) = UiPrefs(
            theme = p.getString("ui.theme", "dark") ?: "dark",
            accent = p.getLong("ui.accent", Palette.DEFAULT_ACCENT),
            language = p.getString("ui.language", "he") ?: "he",
            textScale = p.getFloat("ui.textScale", 1f),
            photoFormat = p.getString("ui.photoFormat", "original") ?: "original",
            askWho = p.getBoolean("ui.askWho", false),
            // once more (after reports of saves without a tap): auto-save off, whatever was stored
            autoSave = if (p.getBoolean("ui.autoSaveOff2", false)) p.getBoolean("autoSave", false)
            else false.also { p.edit().putBoolean("ui.autoSaveOff2", true).putBoolean("autoSave", false).apply() },
            onboarded = p.getBoolean("ui.onboarded", false),
            seenVersion = p.getString("ui.seenVersion", "") ?: "",
            name = p.getString("ui.name", "") ?: "",
            email = p.getString("ui.email", "") ?: "",
        )
    }
}

/** A named set of censor settings ("תבנית"), switched to with one tap. */
data class Preset(val name: String, val settings: CensorSettings)

object Presets {
    private val json = Json { ignoreUnknownKeys = true }

    /** Ready-made ones, shown until the user saves their own. */
    val BUILT_IN = listOf(
        Preset("רגיל", CensorSettings()),
        Preset("מחמיר", CensorSettings(sensitivity = 85, aggressive = true, uncertainPolicy = "censor")),
        Preset("המשך הבגד", CensorSettings(fill = "clothing", color = "#E8D9C5", softness = 0)),
        Preset("כולם", CensorSettings(target = "everyone")),
    )

    fun load(p: SharedPreferences): List<Preset> = runCatching {
        val a = JSONArray(p.getString("presets", null) ?: return BUILT_IN)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Preset(o.getString("name"), json.decodeFromString(CensorSettings.serializer(), o.getString("settings")).validated())
        }
    }.getOrDefault(BUILT_IN)

    fun save(p: SharedPreferences, list: List<Preset>) {
        val a = JSONArray()
        for (x in list) a.put(JSONObject().put("name", x.name).put("settings", json.encodeToString(CensorSettings.serializer(), x.settings)))
        p.edit().putString("presets", a.toString()).apply()
    }
}
