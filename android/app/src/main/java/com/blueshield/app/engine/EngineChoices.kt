package com.blueshield.app.engine

import android.content.Context
import com.blueshield.core.ml.ModelStore

/**
 * The engine each model runs on for one setting ([Accelerators] mode), measured once on this phone and kept
 * until ONNX Runtime or a model changes ([signature]).
 *
 * Crash guard: before an engine is tried on a model, that pair is written down synchronously and cleared once
 * the try is over. An accelerator driver that takes the whole app down leaves the note behind; on the next start
 * the pair is marked broken (for every setting) and never tried again, so a bad driver can't crash it twice.
 */
class EngineChoices(context: Context, mode: String, signature: String) : ModelStore.EngineMemory {
    private val prefs = context.getSharedPreferences("engines_$mode", Context.MODE_PRIVATE)
    private val guard = context.getSharedPreferences("engines_guard", Context.MODE_PRIVATE)

    init {
        if (prefs.getString("signature", null) != signature) prefs.edit().clear().putString("signature", signature).commit()
        if (guard.getString("signature", null) != signature) guard.edit().clear().putString("signature", signature).commit()
        guard.getString("pending", null)?.let { crashed ->
            Breadcrumbs.mark("engines: $crashed crashed while being tried; not used again")
            guard.edit().putStringSet("broken", (guard.getStringSet("broken", emptySet()) ?: emptySet()) + crashed).remove("pending").commit()
        }
    }

    override fun get(file: String): String? = prefs.getString("use:$file", null)

    override fun put(file: String, engine: String) {
        prefs.edit().putString("use:$file", engine).apply()
    }

    override fun trying(file: String, engine: String?) {
        if (engine == null) guard.edit().remove("pending").commit() else guard.edit().putString("pending", "$file|$engine").commit()
    }

    override fun broken(file: String, engine: String): Boolean = "$file|$engine" in (guard.getStringSet("broken", emptySet()) ?: emptySet())
}
