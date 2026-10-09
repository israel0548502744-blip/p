package com.blueshield.core

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.blueshield.core.image.AreaScaler
import com.blueshield.core.image.RgbImage
import com.blueshield.core.ml.EngineCheck
import com.blueshield.core.ml.ModelStore
import java.io.File
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The engine check ([EngineCheck]) on the real models: what an AI chip computing in 16-bit floats changes must pass,
 * what an 8-bit chip or a broken driver changes must not. The "other engines" here are the same models with their
 * weights changed in place (fp16-like rounding, int8 quantisation, noise) or a resize fault, run on the CPU.
 */
class EngineCheckTest {
    private val repo = File(System.getProperty("blueshield.repo") ?: "../..")
    private val env = OrtEnvironment.getEnvironment()
    private fun model(file: String) = File(repo, "models/onnx/$file").readBytes()
    private fun session(bytes: ByteArray): OrtSession = env.createSession(bytes, OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) })

    private fun verdicts(file: String, variants: Map<String, ByteArray>): Map<String, EngineCheck.Verdict> {
        val probe = probe()
        val decoder by lazy { session(model(ModelStore.SAM_DECODER)) }
        val check = EngineCheck.forModel(file, probe, env) { decoder }!!
        val base = session(model(file)).use { check.run(it, env) }
        assertTrue(check.compare(base, base).same, "$file: the plain engine agrees with itself")
        return variants.mapValues { (name, bytes) ->
            session(bytes).use { s -> check.compare(base, check.run(s, env)) }.also { println("ENGINECHECK $file $name: ${it.same} (${it.detail})") }
        }
    }

    @Test fun sixteenBitRoundingPassesInt8AndGarbageFail() {
        for (file in listOf(ModelStore.SEGMENTER, ModelStore.PERSONS, ModelStore.FACES, ModelStore.GENDER, ModelStore.NUDENET)) {
            val bytes = model(file)
            val v = verdicts(file, mapOf(
                "fp16" to Weights.map(bytes, Weights::fp16Like),
                "int8" to Weights.map(bytes, Weights::int8),
                "garbage" to Weights.map(bytes, Weights::noise),
            ))
            assertTrue(v.getValue("fp16").same, "$file: 16-bit rounding is the same result (${v.getValue("fp16").detail})")
            assertFalse(v.getValue("int8").same, "$file: 8-bit weights change the result (${v.getValue("int8").detail})")
            assertFalse(v.getValue("garbage").same, "$file: noisy weights change the result")
        }
        // age + gender: int8 weights move it too little to matter (P(male) 0.004, age 0.2 years); noise does not
        val bytes = model(ModelStore.AGE_GENDER)
        val v = verdicts(ModelStore.AGE_GENDER, mapOf("fp16" to Weights.map(bytes, Weights::fp16Like), "garbage" to Weights.map(bytes, Weights::noise)))
        assertTrue(v.getValue("fp16").same)
        assertFalse(v.getValue("garbage").same)
    }

    @Test fun outlineCheckDecodesTheOutlines() {
        // the outline encoder is judged by the person outlines decoded from its output (on the plain decoder)
        val bytes = model(ModelStore.SAM_ENCODER_512)
        val v = verdicts(ModelStore.SAM_ENCODER_512, mapOf("rounded" to Weights.map(bytes, Weights::fp16Like), "garbage" to Weights.map(bytes, Weights::noise)))
        assertTrue(v.getValue("rounded").same, v.getValue("rounded").detail)
        assertFalse(v.getValue("garbage").same, v.getValue("garbage").detail)
    }

    @Test fun segmenterResizeWithOtherPixelCentresIsCaught() {
        // an accelerator resizing with "asymmetric" instead of "half_pixel" pixel centres: within 3 % on random input
        // (the old check passed it), but the skin mask moves (IoU 0.85-0.9 on real crops)
        val bytes = model(ModelStore.SEGMENTER)
        val fault = Weights.replaceAttribute(bytes, "half_pixel", "asymmetric")
        val v = verdicts(ModelStore.SEGMENTER, mapOf("asymmetric" to fault)).getValue("asymmetric")
        assertFalse(v.same, v.detail)
    }

    @Test fun outlineEncodersRunOnlyOnFullPrecisionEngines() {
        var chipTried = 0
        val engines = listOf(
            ModelStore.Engine("fp16-chip") { chipTried++; OrtSession.SessionOptions() },
            ModelStore.Engine("fp32-kernels", fp32 = true) { OrtSession.SessionOptions() },
        )
        assertFalse(ModelStore.allowed(ModelStore.SAM_ENCODER_512, engines[0]))
        assertFalse(ModelStore.allowed(ModelStore.SAM_ENCODER_1024, engines[0]))
        assertTrue(ModelStore.allowed(ModelStore.SAM_ENCODER_512, engines[1]))
        assertTrue(ModelStore.allowed(ModelStore.SEGMENTER, engines[0]))
        val memory = Memory()
        ModelStore({ model(it) }, engines = engines, pickFastest = false, memory = memory, probeImage = ::probe).use { m ->
            // the full-precision engine computes the same outlines (checked by decoding them) and is taken
            assertEquals("fp32-kernels", m.engineOf(ModelStore.SAM_ENCODER_512))
            assertEquals("fp32-kernels", memory.chosen[ModelStore.SAM_ENCODER_512])
            // the outline decoder (several inputs) is never tried
            assertEquals(ModelStore.CPU, m.engineOf(ModelStore.SAM_DECODER))
        }
        assertEquals(0, chipTried, "a 16-bit engine is never even created for the outline encoder")
        // a remembered 16-bit choice (an older app) is not used either
        val old = Memory().apply { chosen[ModelStore.SAM_ENCODER_512] = "fp16-chip" }
        ModelStore({ model(it) }, engines = engines, memory = old, probeImage = ::probe).use { m ->
            assertEquals(ModelStore.CPU, m.engineOf(ModelStore.SAM_ENCODER_512))
        }
        assertEquals(0, chipTried)
    }

    @Test fun storeWithoutEnginesNeverUsesOne() {
        // photos: a store without engines; whatever was remembered for videos plays no part
        val memory = Memory().apply { for (f in ModelStore.ALL) chosen[f] = "chip" }
        ModelStore({ model(it) }, memory = memory, probeImage = ::probe).use { m ->
            assertFalse(m.accelerated)
            for (f in listOf(ModelStore.SEGMENTER, ModelStore.PERSONS, ModelStore.FACES)) assertEquals(ModelStore.CPU, m.engineOf(f))
            assertTrue(m.measured.isEmpty())
        }
        assertEquals(0, memory.calls, "the engine memory is not even read")
        // engines but no probe photo: nothing can be checked, so nothing is used (nor remembered)
        var created = 0
        val engines = listOf(ModelStore.Engine("chip") { created++; OrtSession.SessionOptions() })
        val fresh = Memory()
        ModelStore({ model(it) }, engines = engines, memory = fresh).use { m -> assertEquals(ModelStore.CPU, m.engineOf(ModelStore.FACES)) }
        assertEquals(0, created)
        assertTrue(fresh.chosen.isEmpty())
    }

    @Test fun areaScalerAveragesTheCoveredArea() {
        // 2x: plain 2 × 2 block means
        val img = RgbImage(4, 2, ByteArray(24) { (it * 10).toByte() })
        val half = AreaScaler(4, 2, 2, 1).scale { y, row -> for (x in 0 until 4) row[x] = argb(img, x, y) }
        for (c in 0 until 3) {
            val mean = listOf(0, 1, 4, 5).sumOf { (img.data[it * 3 + c].toInt() and 0xFF) } / 4f
            assertEquals(Math.round(mean), half.data[c].toInt() and 0xFF)
        }
        // a fractional reduction (5 → 3) against a brute-force mean over a fine grid; same size is the identity
        val rnd = java.util.Random(3)
        val src = RgbImage(5, 4, ByteArray(60) { rnd.nextInt(256).toByte() })
        val out = AreaScaler(5, 4, 3, 2).scale { y, row -> for (x in 0 until 5) row[x] = argb(src, x, y) }
        val n = 60
        for (oy in 0 until 2) for (ox in 0 until 3) for (c in 0 until 3) {
            var s = 0.0
            for (j in 0 until n) for (i in 0 until n) {
                val sx = ((ox + (i + 0.5) / n) * 5 / 3).toInt()
                val sy = ((oy + (j + 0.5) / n) * 4 / 2).toInt()
                s += src.data[(sy * 5 + sx) * 3 + c].toInt() and 0xFF
            }
            assertTrue(abs(s / (n * n) - (out.data[(oy * 3 + ox) * 3 + c].toInt() and 0xFF)) <= 1.0)
        }
        val same = AreaScaler(5, 4, 5, 4).scale { y, row -> for (x in 0 until 5) row[x] = argb(src, x, y) }
        assertTrue(same.data.contentEquals(src.data))
    }

    @Test fun areaScalerMatchesTheTestedDecodePath() {
        // the photo tests decode with ffmpeg's area scaler; the app scales the decoded photo with AreaScaler
        val f = File(repo, "tests/fixtures/woman_and_man.jpg")
        val full = PipelineIntegrationTest.decode(f, 768, 432).first()
        val w = 300
        val h = 168
        val ref = PipelineIntegrationTest.decode(f, w, h).first()
        val area = AreaScaler(768, 432, w, h).scale { y, row -> for (x in 0 until 768) row[x] = argb(full, x, y) }
        val bilinear = full.resize(w, h)
        fun mad(a: RgbImage) = a.data.indices.sumOf { abs((a.data[it].toInt() and 0xFF) - (ref.data[it].toInt() and 0xFF)) }.toDouble() / a.data.size
        println("AREASCALER vs ffmpeg area: mean |d| ${"%.3f".format(mad(area))}; bilinear ${"%.3f".format(mad(bilinear))}")
        assertTrue(mad(area) < 1.0, "area mean |d| ${mad(area)}")
        assertTrue(mad(area) < mad(bilinear))
    }

    private fun argb(img: RgbImage, x: Int, y: Int): Int {
        val i = (y * img.width + x) * 3
        return (0xFF shl 24) or ((img.data[i].toInt() and 0xFF) shl 16) or ((img.data[i + 1].toInt() and 0xFF) shl 8) or (img.data[i + 2].toInt() and 0xFF)
    }

    class Memory : ModelStore.EngineMemory {
        val chosen = HashMap<String, String>()
        var calls = 0
        override fun get(file: String): String? { calls++; return chosen[file] }
        override fun put(file: String, engine: String) { calls++; chosen[file] = engine }
        override fun trying(file: String, engine: String?) { calls++ }
        override fun broken(file: String, engine: String): Boolean { calls++; return false }
    }

    companion object {
        /** The probe photo as the JVM decodes it (the app decodes it with BitmapFactory). */
        fun probe(): RgbImage {
            val img = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(EngineCheck.probeJpeg()!!))
            val out = RgbImage(img.width, img.height)
            for (y in 0 until img.height) for (x in 0 until img.width) {
                val p = img.getRGB(x, y)
                val i = (y * img.width + x) * 3
                out.data[i] = (p shr 16).toByte()
                out.data[i + 1] = (p shr 8).toByte()
                out.data[i + 2] = p.toByte()
            }
            return out
        }
    }

    /**
     * Model variants made by rewriting an ONNX file's weights in place: the float / half initializers stored as raw
     * data (every model here keeps its weights that way) keep their size, so no protobuf re-encoding is needed.
     */
    object Weights {
        /**
         * fp16-like: 32-bit weights rounded to 16-bit floats (what an fp16 chip computes with); weights stored in
         * 16 bits already (yolox, the outline encoders) moved by about one 16-bit step instead (rounded differently).
         */
        fun fp16Like(w: FloatArray, half: Boolean, rnd: java.util.Random) {
            for (i in w.indices) w[i] = halfToFloat(floatToHalf(if (half) w[i] * (1f + 2f.pow(-11) * rnd.nextGaussian().toFloat()) else w[i]))
        }

        /** int8-like: one symmetric 8-bit scale per tensor. */
        fun int8(w: FloatArray, half: Boolean, rnd: java.util.Random) {
            val s = (w.maxOf { abs(it) } / 127f).takeIf { it > 0f } ?: return
            for (i in w.indices) w[i] = Math.round(w[i] / s).coerceIn(-127, 127) * s
        }

        /** garbage: 30 % noise on every weight. */
        fun noise(w: FloatArray, half: Boolean, rnd: java.util.Random) {
            for (i in w.indices) w[i] *= 1f + 0.3f * rnd.nextGaussian().toFloat()
        }

        fun map(model: ByteArray, f: (FloatArray, Boolean, java.util.Random) -> Unit): ByteArray {
            val b = model.copyOf()
            val rnd = java.util.Random(7)
            var tensors = 0
            fields(b, 0, b.size) { num, wire, s, e -> if (num == 7 && wire == 2) fields(b, s, e) { gn, gw, gs, ge ->
                if (gn == 5 && gw == 2) {
                    var type = 0
                    var raw = -1 to -1
                    fields(b, gs, ge) { tn, tw, ts, te -> if (tn == 2 && tw == 0) type = varint(b, intArrayOf(ts)).toInt(); if (tn == 9 && tw == 2) raw = ts to te }
                    // weights only: small float tensors are resize scales and the like
                    if (raw.first >= 0 && (type == 1 || type == 10) && (raw.second - raw.first) >= 16 * (if (type == 1) 4 else 2)) {
                        val buf = java.nio.ByteBuffer.wrap(b, raw.first, raw.second - raw.first).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        val n = (raw.second - raw.first) / if (type == 1) 4 else 2
                        val w = FloatArray(n) { k -> if (type == 1) buf.getFloat(raw.first + 4 * k) else halfToFloat(buf.getShort(raw.first + 2 * k).toInt() and 0xFFFF) }
                        f(w, type == 10, rnd)
                        for (k in 0 until n) if (type == 1) buf.putFloat(raw.first + 4 * k, w[k]) else buf.putShort(raw.first + 2 * k, floatToHalf(w[k]).toShort())
                        tensors++
                    }
                }
            } }
            check(tensors > 0) { "no weights found" }
            return b
        }

        /** A string attribute value replaced by another of the same length (e.g. a Resize node's coordinate mode). */
        fun replaceAttribute(model: ByteArray, from: String, to: String): ByteArray {
            require(from.length == to.length)
            // AttributeProto.s (field 4, length-delimited) holding exactly [from]
            val pattern = byteArrayOf(0x22, from.length.toByte()) + from.toByteArray()
            val b = model.copyOf()
            var n = 0
            var i = 0
            while (i <= b.size - pattern.size) {
                if ((pattern.indices).all { b[i + it] == pattern[it] }) { to.toByteArray().copyInto(b, i + 2); n++; i += pattern.size } else i++
            }
            check(n > 0) { "no '$from' attribute" }
            return b
        }

        private fun varint(b: ByteArray, pos: IntArray): Long {
            var r = 0L
            var shift = 0
            while (true) {
                val x = b[pos[0]++].toInt() and 0xFF
                r = r or ((x and 0x7F).toLong() shl shift)
                if (x < 0x80) return r
                shift += 7
            }
        }

        /** Every field of the protobuf message b[start, end): number, wire type, and where its value lies. */
        private fun fields(b: ByteArray, start: Int, end: Int, onField: (num: Int, wire: Int, s: Int, e: Int) -> Unit) {
            val pos = intArrayOf(start)
            while (pos[0] < end) {
                val key = varint(b, pos)
                val num = (key ushr 3).toInt()
                val wire = (key and 7).toInt()
                val s = pos[0]
                when (wire) {
                    0 -> { varint(b, pos); onField(num, wire, s, pos[0]) }
                    1 -> { pos[0] += 8; onField(num, wire, s, pos[0]) }
                    2 -> { val len = varint(b, pos).toInt(); val vs = pos[0]; pos[0] += len; onField(num, wire, vs, pos[0]) }
                    5 -> { pos[0] += 4; onField(num, wire, s, pos[0]) }
                    else -> error("unsupported wire type $wire")
                }
            }
        }

        fun halfToFloat(h: Int): Float {
            val e = (h shr 10) and 0x1F
            val m = h and 0x3FF
            val v = when (e) {
                0 -> m * 2f.pow(-24)
                31 -> if (m == 0) Float.POSITIVE_INFINITY else Float.NaN
                else -> (1f + m / 1024f) * 2f.pow(e - 15)
            }
            return if ((h shr 15) and 1 == 1) -v else v
        }

        fun floatToHalf(f: Float): Int {
            val sign = if (f < 0f || (f == 0f && 1f / f < 0f)) 0x8000 else 0
            val v = abs(f)
            if (v.isNaN()) return 0x7E00
            if (v >= 65504f) return sign or 0x7BFF
            if (v < 6.1035156e-5f) return sign or Math.round(v / 2f.pow(-24))
            val e = Math.getExponent(v)
            var m = Math.round((v / 2f.pow(e) - 1f) * 1024f)
            var be = e + 15
            if (m == 1024) { m = 0; be++ }
            return if (be >= 31) sign or 0x7BFF else sign or (be shl 10) or m
        }
    }
}
