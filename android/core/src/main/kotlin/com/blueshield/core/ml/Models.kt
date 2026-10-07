package com.blueshield.core.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * Loads the ONNX models. All inference goes through ONNX Runtime so the exact same
 * code runs on Android (onnxruntime-android) and on the JVM (tests).
 *
 * With an [alternative] engine (the app passes XNNPACK), every single-input model is timed once with both
 * engines on this device and the faster one is kept — the alternative only when it is clearly faster and gives
 * the same outputs; [memory] remembers the choice so the timing happens once per install.
 */
class ModelStore(
    private val load: (String) -> ByteArray,
    private val options: () -> OrtSession.SessionOptions = { OrtSession.SessionOptions() },
    /** Progress breadcrumbs ("loading x", "loaded x"): lets the app tell where a native crash happened. */
    private val onEvent: (String) -> Unit = {},
    private val alternative: (() -> OrtSession.SessionOptions)? = null,
    private val memory: EngineMemory? = null,
) : AutoCloseable {
    /** Remembered engine choice per model file: true = the alternative engine, null = not measured yet. */
    interface EngineMemory {
        fun get(file: String): Boolean?
        fun put(file: String, alternative: Boolean)
    }

    val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sessions = HashMap<String, OrtSession>()
    /** Engine in use per model (for the diagnostics trail). */
    val engines = LinkedHashMap<String, String>()

    @Synchronized
    fun session(file: String): OrtSession = sessions.getOrPut(file) {
        onEvent("model: loading $file")
        val bytes = load(file)
        val alt = alternative
        val s = if (alt == null) env.createSession(bytes, options()).also { engines[file] = "cpu" } else pick(file, bytes, alt)
        s.also { onEvent("model: loaded $file (${engines[file]})") }
    }

    /**
     * A second session of [file] on a single thread, for running several inputs side by side: a small model
     * keeps several cores busy better as parallel single-threaded runs than one run at a time on all of them.
     */
    @Synchronized
    fun singleThreaded(file: String): OrtSession = sessions.getOrPut("$file#1") {
        onEvent("model: loading $file (1 thread)")
        env.createSession(load(file), options().apply { setIntraOpNumThreads(1) }).also { onEvent("model: loaded $file (1 thread)") }
    }

    private fun pick(file: String, bytes: ByteArray, alt: () -> OrtSession.SessionOptions): OrtSession {
        val remembered = memory?.get(file)
        if (remembered == false) return env.createSession(bytes, options()).also { engines[file] = "cpu" }
        val other = runCatching { env.createSession(bytes, alt()) }.getOrNull()
        if (other == null) {
            memory?.put(file, false)
            return env.createSession(bytes, options()).also { engines[file] = "cpu" }
        }
        if (remembered == true) return other.also { engines[file] = "alt" }
        val base = env.createSession(bytes, options())
        val altWins = runCatching { faster(base, other) }.getOrDefault(false)
        memory?.put(file, altWins)
        return if (altWins) {
            base.close(); engines[file] = "alt"; other
        } else {
            other.close(); engines[file] = "cpu"; base
        }
    }

    /** True when [b] runs the model clearly faster than [a] with (nearly) the same outputs. */
    private fun faster(a: OrtSession, b: OrtSession): Boolean {
        if (a.inputInfo.size != 1) return false
        val info = a.inputInfo.values.first().info as? ai.onnxruntime.TensorInfo ?: return false
        if (info.type != ai.onnxruntime.OnnxJavaType.FLOAT) return false
        val shape = LongArray(info.shape.size) { k -> info.shape[k].takeIf { it > 0 } ?: if (k == 0) 1L else 320L }
        val rnd = java.util.Random(1)
        val input = FloatArray(shape.fold(1L) { x, y -> x * y }.toInt()) { rnd.nextFloat() * 255f }
        val outA = a.runFloat(env, input, shape)
        val outB = b.runFloat(env, input, shape)
        for ((x, y) in outA.zip(outB)) {
            var scale = 1e-6f
            var diff = 0f
            for (i in x.second.indices) {
                scale = maxOf(scale, kotlin.math.abs(x.second[i]))
                diff = maxOf(diff, kotlin.math.abs(x.second[i] - y.second[i]))
            }
            if (diff > 0.02f * scale) return false
        }
        fun time(s: OrtSession): Long {
            val t = LongArray(3) { val t0 = System.nanoTime(); s.runFloat(env, input, shape); System.nanoTime() - t0 }
            return t.sorted()[1]
        }
        val ta = time(a)
        val tb = time(b)
        return tb < ta * 0.85
    }

    override fun close() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    companion object {
        const val SEGMENTER = "selfie_multiclass_256x256.onnx"
        const val PERSONS = "yolox_tiny.onnx"
        const val FACES = "blaze_face_short_range.onnx"
        const val GENDER = "gender_faceres.onnx"
        const val NUDENET = "nudenet_320n.onnx"
        const val AGE_GENDER = "faceapi_agegender.onnx"
        const val SAM_ENCODER_512 = "mobilesam_encoder_512.onnx"
        const val SAM_ENCODER_1024 = "mobilesam_encoder_1024.onnx"
        const val SAM_DECODER = "mobilesam_decoder.onnx"
        val ALL = listOf(SEGMENTER, PERSONS, FACES, GENDER, NUDENET, AGE_GENDER, SAM_ENCODER_512, SAM_ENCODER_1024, SAM_DECODER)
    }
}

/** Runs a single-input session and returns every output as (shape, data). */
internal fun OrtSession.runFloat(env: OrtEnvironment, input: FloatArray, shape: LongArray): List<Pair<LongArray, FloatArray>> {
    OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { t ->
        run(mapOf(inputNames.first() to t)).use { res ->
            return res.map { entry ->
                val tensor = entry.value as OnnxTensor
                val shp = tensor.info.shape
                val buf = tensor.floatBuffer
                val arr = FloatArray(buf.remaining())
                buf.get(arr)
                shp to arr
            }
        }
    }
}
