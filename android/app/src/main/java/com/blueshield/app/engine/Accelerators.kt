package com.blueshield.app.engine

import android.content.Context
import android.os.Build
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import com.blueshield.core.ml.ModelStore
import java.util.EnumSet

/**
 * Which processor the models run on — a setting, because phones differ so much:
 *  - auto: every engine this phone has is tried once per model, and the fastest with the same results is kept
 *  - cpu:  the processor only (plain or XNNPACK kernels, whichever is faster)
 *  - gpu:  the graphics processor (Qualcomm's Adreno driver on Snapdragon, WebGPU/Vulkan on any phone)
 *  - npu:  the AI chip (Qualcomm Hexagon on Snapdragon, the vendor's NNAPI driver elsewhere)
 * A model the chosen engine can't run, or runs with different results, stays on the plain CPU engine.
 */
object Accelerators {
    data class Mode(val id: String, val label: String, val hint: String)

    val MODES = listOf(
        Mode("auto", "אוטומטי", "בודק פעם אחת את כל המעבדים שיש בטלפון ובוחר לכל מודל את המהיר ביותר שנותן תוצאות זהות. מומלץ."),
        Mode("cpu", "מעבד", "המעבד הרגיל בלבד. הכי יציב, הכי איטי."),
        Mode("gpu", "כרטיס גרפי", "GPU: Adreno בטלפוני Snapdragon, ובשאר הטלפונים דרך Vulkan."),
        Mode("npu", "שבב AI", "NPU: שבב הבינה המלאכותית (Hexagon ב-Snapdragon, NNAPI בשאר)."),
    )

    /** Engine id → Hebrew name, for the settings report. */
    val ENGINE_NAMES = mapOf(
        ModelStore.CPU to "מעבד", "xnnpack" to "מעבד מהיר", "qnn-htp" to "שבב AI (Hexagon)", "qnn-gpu" to "כרטיס גרפי (Adreno)",
        "webgpu" to "כרטיס גרפי (Vulkan)", "nnapi" to "שבב AI (NNAPI)",
    )
    val MODEL_NAMES = mapOf(
        ModelStore.SEGMENTER to "עור", ModelStore.PERSONS to "אנשים", ModelStore.FACES to "פנים", ModelStore.GENDER to "מגדר",
        ModelStore.AGE_GENDER to "גיל", ModelStore.NUDENET to "אזורים רגישים", ModelStore.SAM_ENCODER_512 to "קווי מתאר",
        ModelStore.SAM_ENCODER_1024 to "קווי מתאר (תמונות)", ModelStore.SAM_DECODER to "קווי מתאר (פענוח)",
    )

    private fun prefs(c: Context) = c.getSharedPreferences("accelerators", Context.MODE_PRIVATE)
    fun mode(c: Context): String = prefs(c).getString("mode", "auto") ?: "auto"
    fun setMode(c: Context, id: String) = prefs(c).edit().putString("mode", id).apply()

    val snapdragon: Boolean
        get() = (Build.VERSION.SDK_INT >= 31 && Build.SOC_MANUFACTURER.equals("QTI", ignoreCase = true)) ||
            "qcom" in Build.HARDWARE.lowercase() || Build.BOARD.lowercase().let { it in setOf("sun", "pineapple", "kalama", "taro", "lahaina") }

    /** The phone's chip, for the settings screen. */
    val chip: String
        get() = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE

    private val threads get() = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)

    /** Engines to try for [mode], and whether the fastest wins (otherwise the first that works). */
    fun engines(mode: String): Pair<List<ModelStore.Engine>, Boolean> {
        val xnnpack = ModelStore.Engine("xnnpack") {
            // XNNPACK brings its own thread pool: ONNX Runtime's stays at one thread and doesn't spin
            base().apply {
                addConfigEntry("session.intra_op.allow_spinning", "0")
                addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                setIntraOpNumThreads(1)
            }
        }
        val htp = ModelStore.Engine("qnn-htp") {
            base().apply {
                addQnn(mapOf("backend_path" to "libQnnHtp.so", "htp_performance_mode" to "burst", "enable_htp_fp16_precision" to "1"))
            }
        }
        val adreno = ModelStore.Engine("qnn-gpu") { base().apply { addQnn(mapOf("backend_path" to "libQnnGpu.so")) } }
        val webgpu = ModelStore.Engine("webgpu") { base().apply { addWebGPU(emptyMap()) } }
        val nnapi = ModelStore.Engine("nnapi") { base().apply { addNnapi(EnumSet.of(NNAPIFlags.USE_FP16, NNAPIFlags.CPU_DISABLED)) } }
        val q = snapdragon
        return when (mode) {
            "cpu" -> listOf(xnnpack) to true
            "gpu" -> (listOfNotNull(adreno.takeIf { q }, webgpu)) to false
            "npu" -> (listOfNotNull(htp.takeIf { q }, nnapi)) to false
            else -> listOfNotNull(xnnpack, htp.takeIf { q }, adreno.takeIf { q }, webgpu, nnapi) to true
        }
    }

    /** The plain CPU engine's options (also the base of the others: their unsupported parts run here). */
    fun base(): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setIntraOpNumThreads(threads)
        setInterOpNumThreads(1)
    }

    /** Engine choices are measured again when ONNX Runtime or a model changes (not on every app update). */
    fun signature(c: Context): String {
        val sizes = ModelStore.ALL.joinToString(",") { f -> runCatching { c.assets.openFd("models/$f").use { it.length } }.getOrDefault(-1L).toString() }
        return "ort=${runCatching { OrtEnvironment.getEnvironment().version }.getOrDefault("?")};$sizes"
    }

    /** What ran where in the last job, for the settings screen. */
    fun saveReport(c: Context, m: ModelStore) {
        if (m.used.isEmpty()) return
        val lines = m.used.entries.joinToString("\n") { (file, engine) ->
            val times = m.measured[file]?.entries?.joinToString(" · ") { (e, ms) -> "${ENGINE_NAMES[e] ?: e} ${"%.0f".format(ms)}ms" }
            "${MODEL_NAMES[file] ?: file}: ${ENGINE_NAMES[engine] ?: engine}" + (times?.let { "  ($it)" } ?: "")
        }
        prefs(c).edit().putString("report", lines).apply()
    }

    fun report(c: Context): String? = prefs(c).getString("report", null)

    /** Forget the measurements (and engines marked as crashed): everything is tried again on the next job. */
    fun remeasure(c: Context) {
        for (m in MODES) c.getSharedPreferences("engines_${m.id}", Context.MODE_PRIVATE).edit().clear().commit()
        c.getSharedPreferences("engines_guard", Context.MODE_PRIVATE).edit().clear().commit()
        prefs(c).edit().remove("report").apply()
    }
}
