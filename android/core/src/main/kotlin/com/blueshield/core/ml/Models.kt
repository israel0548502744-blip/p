package com.blueshield.core.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * Loads the ONNX models. All inference goes through ONNX Runtime so the exact same
 * code runs on Android (onnxruntime-android) and on the JVM (tests).
 *
 * [engines] are the other processors this device may run a model on (fast CPU kernels, the GPU, an AI chip),
 * besides the plain CPU engine ([options]). Per single-input model they are tried once on this device: an
 * engine counts only when its session can be created and its outputs match the plain engine's; then either
 * the fastest one is kept ([pickFastest], the "automatic" setting) or the first one that works (the user chose
 * a kind of processor). Anything else stays on the plain engine. [memory] remembers the choice, and an engine
 * that crashed the app while being tried is never tried again for that model.
 */
class ModelStore(
    private val load: (String) -> ByteArray,
    private val options: () -> OrtSession.SessionOptions = { OrtSession.SessionOptions() },
    /** Progress breadcrumbs ("loading x", "loaded x"): lets the app tell where a native crash happened. */
    private val onEvent: (String) -> Unit = {},
    private val engines: List<Engine> = emptyList(),
    private val pickFastest: Boolean = true,
    private val memory: EngineMemory? = null,
) : AutoCloseable {
    /** An execution engine: [id] for memory and display, [options] builds its session options (may throw). */
    class Engine(val id: String, val options: () -> OrtSession.SessionOptions)

    /** Remembered engine per model file ("cpu" = the plain engine), and the crash guard for trying engines. */
    interface EngineMemory {
        fun get(file: String): String?
        fun put(file: String, engine: String)
        /** Called before trying [engine] on [file], and with null once the try is over (crashed if never cleared). */
        fun trying(file: String, engine: String?)
        fun broken(file: String, engine: String): Boolean
    }

    val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sessions = HashMap<String, OrtSession>()
    /** Engine in use per model, and the measured milliseconds per engine (for the settings screen). */
    val used = LinkedHashMap<String, String>()
    val measured = LinkedHashMap<String, Map<String, Double>>()

    @Synchronized
    fun session(file: String): OrtSession = sessions.getOrPut(file) {
        onEvent("model: loading $file")
        val bytes = load(file)
        val s = if (engines.isEmpty()) env.createSession(bytes, options()).also { used[file] = CPU } else pick(file, bytes)
        s.also { onEvent("model: loaded $file (${used[file]})") }
    }

    /** The engine [file] runs on (loads it if needed). */
    fun engineOf(file: String): String {
        session(file)
        return used[file] ?: CPU
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

    private fun plain(file: String, bytes: ByteArray) = env.createSession(bytes, options()).also { used[file] = CPU }

    private fun create(file: String, bytes: ByteArray, e: Engine): OrtSession? {
        memory?.trying(file, e.id)
        val s = runCatching { env.createSession(bytes, e.options()) }
            .onFailure { onEvent("model: ${e.id} unavailable for $file: ${it.message?.take(120)}") }.getOrNull()
        memory?.trying(file, null)
        return s
    }

    private fun pick(file: String, bytes: ByteArray): OrtSession {
        val remembered = memory?.get(file)
        if (remembered != null) {
            val e = engines.find { it.id == remembered }
            if (e == null || memory?.broken(file, e.id) == true) return plain(file, bytes)
            return create(file, bytes, e)?.also { used[file] = e.id } ?: plain(file, bytes)
        }
        val base = plain(file, bytes)
        val probe = probe(base)
        if (probe == null) { // several inputs (the outline decoder): not verifiable this way, and cheap anyway
            memory?.put(file, CPU)
            return base
        }
        val times = LinkedHashMap<String, Double>()
        val baseOut = base.runFloat(env, probe.first, probe.second)
        var bestTime = time(base, probe).also { times[CPU] = it }
        // only one session of this model in memory at a time while trying (the photo outline model is large)
        base.close()
        var best: Pair<String, OrtSession>? = null
        for (e in engines) {
            if (memory?.broken(file, e.id) == true) continue
            val s = create(file, bytes, e)
            memory?.trying(file, e.id) // still trying: its first runs
            val t = s?.let {
                runCatching {
                    val t0 = System.nanoTime()
                    val out = it.runFloat(env, probe.first, probe.second)
                    val first = (System.nanoTime() - t0) / 1e6
                    // the first run includes the accelerator's one-off setup; ten times slower than the plain engine
                    // even so (a software GPU, an emulator) is not worth measuring further
                    if (!same(baseOut, out) || (pickFastest && first > 10 * times.getValue(CPU))) null else time(it, probe, warm = false)
                }.getOrNull()
            }
            memory?.trying(file, null)
            if (s == null || t == null) {
                s?.close()
                if (s != null) onEvent("model: ${e.id} not used for $file (different results, or far too slow)")
                continue
            }
            times[e.id] = t
            if (!pickFastest || t < bestTime * 0.85) {
                best?.second?.close()
                best = e.id to s
                bestTime = t
                if (!pickFastest) break
            } else s.close()
            System.gc() // the closed session's buffers
        }
        measured[file] = times
        memory?.put(file, best?.first ?: CPU)
        val (id, s) = best ?: return plain(file, bytes)
        used[file] = id
        return s
    }

    /** A test input for a single-float-input model: pixel-like values in 0..1 (in range for every model here). */
    private fun probe(s: OrtSession): Pair<FloatArray, LongArray>? {
        if (s.inputInfo.size != 1) return null
        val info = s.inputInfo.values.first().info as? ai.onnxruntime.TensorInfo ?: return null
        if (info.type != ai.onnxruntime.OnnxJavaType.FLOAT) return null
        val shape = LongArray(info.shape.size) { k -> info.shape[k].takeIf { it > 0 } ?: if (k == 0) 1L else 320L }
        val rnd = java.util.Random(1)
        return FloatArray(shape.fold(1L) { x, y -> x * y }.toInt()) { rnd.nextFloat() } to shape
    }

    /** Same outputs within 3 % of each output's range (an AI chip or a GPU computes in 16-bit floats). */
    private fun same(a: List<Pair<LongArray, FloatArray>>, b: List<Pair<LongArray, FloatArray>>): Boolean {
        if (a.size != b.size) return false
        for ((x, y) in a.zip(b)) {
            if (x.second.size != y.second.size) return false
            var scale = 1e-6f
            var diff = 0f
            for (i in x.second.indices) {
                scale = maxOf(scale, kotlin.math.abs(x.second[i]))
                val d = kotlin.math.abs(x.second[i] - y.second[i])
                if (d.isNaN()) return false
                diff = maxOf(diff, d)
            }
            if (diff > 0.03f * scale) return false
        }
        return true
    }

    /** Median of three runs, milliseconds (after one warm-up run unless the session has just run). */
    private fun time(s: OrtSession, probe: Pair<FloatArray, LongArray>, warm: Boolean = true): Double {
        if (warm) s.runFloat(env, probe.first, probe.second)
        val t = DoubleArray(3) { val t0 = System.nanoTime(); s.runFloat(env, probe.first, probe.second); (System.nanoTime() - t0) / 1e6 }
        return t.sorted()[1]
    }

    /** Frees one model's sessions (memory); it is loaded again on next use. */
    @Synchronized
    fun release(file: String) {
        sessions.remove(file)?.close()
        sessions.remove("$file#1")?.close()
    }

    override fun close() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    companion object {
        const val CPU = "cpu"
        const val SEGMENTER = "selfie_multiclass_256x256.onnx"
        const val PERSONS = "yolox_tiny.onnx"
        const val FACES = "blaze_face_short_range.onnx"
        const val GENDER = "gender_faceres.onnx"
        const val NUDENET = "nudenet_320n.onnx"
        const val AGE_GENDER = "faceapi_agegender.onnx"
        const val SAM_ENCODER_512 = "mobilesam_encoder_512.onnx"
        const val SAM_ENCODER_1024 = "mobilesam_encoder_1024.onnx"
        const val SAM_DECODER = "mobilesam_decoder.onnx"
        /** Skin / clothes / hair segmenter: the clothes veto ([ClothesSegmenter]). */
        const val CLOTHES = "skin_clothes_hair_mnv3s_512.onnx"
        val ALL = listOf(SEGMENTER, PERSONS, FACES, GENDER, NUDENET, AGE_GENDER, SAM_ENCODER_512, SAM_ENCODER_1024, SAM_DECODER, CLOTHES)
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
