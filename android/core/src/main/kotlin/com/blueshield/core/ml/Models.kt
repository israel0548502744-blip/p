package com.blueshield.core.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * Loads the ONNX models. All inference goes through ONNX Runtime so the exact same
 * code runs on Android (onnxruntime-android, NNAPI/XNNPACK) and on the JVM (tests).
 */
class ModelStore(
    private val load: (String) -> ByteArray,
    private val options: () -> OrtSession.SessionOptions = { OrtSession.SessionOptions() },
    /** Progress breadcrumbs ("loading x", "loaded x"): lets the app tell where a native crash happened. */
    private val onEvent: (String) -> Unit = {},
) : AutoCloseable {
    val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sessions = HashMap<String, OrtSession>()

    @Synchronized
    fun session(file: String): OrtSession = sessions.getOrPut(file) {
        onEvent("model: loading $file")
        env.createSession(load(file), options()).also { onEvent("model: loaded $file") }
    }

    override fun close() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    companion object {
        const val SEGMENTER = "selfie_multiclass_256x256.onnx"
        const val PERSONS = "efficientdet_lite0.onnx"
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
