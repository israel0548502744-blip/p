package com.blueshield.app.engine

import android.content.Context
import com.blueshield.core.ml.ModelStore

/**
 * Which inference engine each model runs on, measured once on this phone ([ModelStore] times the plain CPU
 * engine against XNNPACK) and kept until the app is updated (a new version may bring new models or a new
 * ONNX Runtime, so it measures again).
 */
class EngineChoices(context: Context) : ModelStore.EngineMemory {
    private val prefs = context.getSharedPreferences("engine_choices", Context.MODE_PRIVATE)
    private val version = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode
    }.getOrDefault(0)

    init {
        if (prefs.getInt("version", -1) != version) prefs.edit().clear().putInt("version", version).apply()
    }

    override fun get(file: String): Boolean? = if (prefs.contains(file)) prefs.getBoolean(file, false) else null

    override fun put(file: String, alternative: Boolean) {
        prefs.edit().putBoolean(file, alternative).apply()
    }
}
