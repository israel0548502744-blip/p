package com.blueshield.core.ml

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.blueshield.core.image.Box
import com.blueshield.core.image.RgbImage
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Every output of one model run, as (shape, data). */
typealias Outputs = List<Pair<LongArray, FloatArray>>

/**
 * Does another engine (an AI chip, a GPU, other CPU kernels) compute what the plain CPU engine computes, as far as
 * the censoring is concerned? A real photo — the bundled probe, a woman and a man ([PROBE]) — goes through each
 * model's own preprocessing, and the two engines' outputs are compared on what the pipeline decides from them:
 * the skin mask, person / face / region boxes, gender and age, the person outlines.
 *
 * Random noise is no test for this (the check it replaces): it fails a 16-bit chip whose masks are identical
 * (noise has no structure, so rounding shows up as large relative differences) and passes faults that move real
 * masks (a chip resizing with other pixel-centre rules: skin IoU 0.85-0.96 on real crops, yet within 3 % on noise).
 * Calibration (fp16 emulation of every model / int8 / resize faults, on this probe): the segmenter in fp16 keeps a
 * skin IoU of 0.999, argmax agreement 0.9997 and a largest probability difference of 0.02; int8 drops to IoU 0.5-0.67;
 * person, face and region boxes keep IoU >= 0.995 in fp16 and fall to 0.83-0.95 with int8 weights; gender moves 0.0003
 * in fp16. The outline encoders overflow in fp16 (mask IoU 0): they only run on full-precision engines ([FP32_ONLY]).
 */
class EngineCheck private constructor(
    /** What the model is fed, each already through the model's own preprocessing: data and shape. */
    val inputs: List<Pair<FloatArray, LongArray>>,
    private val judge: (base: List<Outputs>, other: List<Outputs>) -> Verdict,
) {
    /** [same]: the other engine may replace the plain one; [detail]: the measured agreement (diagnostics). */
    class Verdict(val same: Boolean, val detail: String)

    fun run(s: OrtSession, env: OrtEnvironment): List<Outputs> = inputs.map { (x, shape) -> s.runFloat(env, x, shape) }

    /** [base] (the plain engine's outputs) against [other]'s: anything malformed or not finite counts as different. */
    fun compare(base: List<Outputs>, other: List<Outputs>): Verdict {
        if (base.size != other.size) return Verdict(false, "missing outputs")
        for ((a, b) in base.zip(other)) {
            if (a.size != b.size) return Verdict(false, "different outputs")
            for ((x, y) in a.zip(b)) {
                if (!x.first.contentEquals(y.first) || x.second.size != y.second.size) return Verdict(false, "different output shapes")
                if (y.second.any { !it.isFinite() }) return Verdict(false, "not a number in the outputs")
            }
        }
        return runCatching { judge(base, other) }.getOrElse { Verdict(false, "outputs not comparable (${it.message})") }
    }

    /** A detection reduced to what decisions use: label, score, box and (faces) the eye points. */
    private class Det(val label: String, val score: Float, val box: Box, val points: FloatArray = FloatArray(0))

    companion object {
        /** Raised whenever the check changes: every remembered engine choice is then measured again. */
        const val VERSION = 2

        /**
         * The probe photo, a classpath resource of the core: one frame of Intel's sample video
         * "head-pose-face-detection-female-and-male" (github.com/intel-iot-devkit/sample-videos, CC-BY-4.0
         * © Intel Corporation), scaled to [PROBE_W] × [PROBE_H]. See resources/blueshield/ENGINE_PROBE_NOTICE.txt.
         */
        const val PROBE = "/blueshield/engine_probe.jpg"
        const val PROBE_W = 384
        const val PROBE_H = 216

        fun probeJpeg(): ByteArray? = EngineCheck::class.java.getResourceAsStream(PROBE)?.use { it.readBytes() }

        // what counts as the same result
        const val SKIN_IOU = 0.98f
        const val ARGMAX_AGREEMENT = 0.995f
        const val MAX_PROB_DIFF = 0.05f
        const val BOX_IOU = 0.98f
        const val SCORE_DIFF = 0.05f
        /** Eye points (they align the face for the gender models): largest shift, as a share of the face box side. */
        const val EYE_SHIFT = 0.03f
        const val P_DIFF = 0.02f
        const val AGE_DIFF = 1f
        const val MASK_IOU = 0.98f

        /** Models whose activations overflow 16-bit floats (fp16 emulation: outline IoU 0): full-precision engines only. */
        val FP32_ONLY = setOf(ModelStore.SAM_ENCODER_512, ModelStore.SAM_ENCODER_1024)

        /** Models this check can verify; everything else (the outline decoder: several inputs) stays on the plain engine. */
        val CHECKED = setOf(
            ModelStore.SEGMENTER, ModelStore.PERSONS, ModelStore.FACES, ModelStore.GENDER, ModelStore.AGE_GENDER,
            ModelStore.NUDENET, ModelStore.SAM_ENCODER_512, ModelStore.SAM_ENCODER_1024,
        )

        // The probe's people and faces (probe pixels), measured once on the plain engine. Fixed, so that checking
        // one model never depends on another model's engine.
        private val PEOPLE = listOf(Box(39.5f, 35.7f, 174.0f, 214.9f), Box(199.4f, 27.3f, 356.9f, 214.6f))
        private val FACE_BOXES = listOf(
            FaceDetector.Face(Box(67.8f, 60.2f, 121.8f, 114.2f), 1f, 79.7f to 72.2f, 101.9f to 72.9f),
            FaceDetector.Face(Box(261.7f, 55.5f, 316.9f, 110.6f), 1f, 285.1f to 70.9f, 306.5f to 68.2f),
        )

        /**
         * The check for [file] on [probe] (the decoded [PROBE]), or null when [file] can't be checked this way.
         * [decoder]: the outline decoder on the plain engine (the outline encoders are judged by decoded masks).
         */
        fun forModel(file: String, probe: RgbImage, env: OrtEnvironment, decoder: () -> OrtSession): EngineCheck? {
            val sx = probe.width.toFloat() / PROBE_W
            val sy = probe.height.toFloat() / PROBE_H
            fun Box.onProbe() = Box(x1 * sx, y1 * sy, x2 * sx, y2 * sy)
            val people = PEOPLE.map { it.onProbe() }
            val faces = FACE_BOXES.map { f ->
                FaceDetector.Face(f.box.onProbe(), f.score, f.rightEye.first * sx to f.rightEye.second * sy, f.leftEye.first * sx to f.leftEye.second * sy)
            }
            return when (file) {
                ModelStore.SEGMENTER -> {
                    // the whole photo, and each person's close-up crop (the pipeline's two uses)
                    val crops = people.map { b -> SkinSegmenter.roiCrops(b, 1.15f)[0].let { (a, t, side) -> probe.crop(a, t, side, side) } }
                    EngineCheck((listOf(probe) + crops).map { SkinSegmenter.input(it).data to SkinSegmenter.SHAPE }, ::segmenter)
                }
                ModelStore.PERSONS -> {
                    val (x, r) = PersonDetector.input(probe)
                    EngineCheck(listOf(x to PersonDetector.SHAPE)) { a, b ->
                        fun dets(o: List<Outputs>) = PersonDetector.decode(o[0][0].second, r, probe.width, probe.height, 0.15f).map { Det(it.label, it.score, it.box) }
                        boxes(listOf(dets(a)), listOf(dets(b)), strong = 0.3f)
                    }
                }
                ModelStore.FACES -> {
                    // a square around each head, as the gender step crops it
                    val heads = faces.map { f ->
                        val side = (2.4f * max(f.box.w, f.box.h)).toInt()
                        probe.crop((f.box.cx - side / 2f).toInt(), (f.box.cy - side / 2f).toInt(), side, side)
                    }
                    EngineCheck(heads.map { FaceDetector.input(it) to FaceDetector.SHAPE }) { a, b ->
                        fun dets(o: List<Outputs>) = heads.indices.map { k ->
                            FaceDetector.decode(o[k], heads[k].width, heads[k].height, 0.3f).map { f ->
                                Det("face", f.score, f.box, floatArrayOf(f.rightEye.first, f.rightEye.second, f.leftEye.first, f.leftEye.second))
                            }
                        }
                        boxes(dets(a), dets(b), strong = 0.5f)
                    }
                }
                ModelStore.GENDER -> {
                    val aligned = faces.map { GenderModel.alignFace(probe, it) }
                    EngineCheck(aligned.map { GenderModel.input(it) to GenderModel.SHAPE }) { a, b ->
                        val d = a.indices.maxOf { abs(a[it][0].second[0] - b[it][0].second[0]) }
                        Verdict(d <= P_DIFF, "P(male) Δ %.4f".format(d))
                    }
                }
                ModelStore.AGE_GENDER -> {
                    val aligned = faces.map { GenderModel.alignFace(probe, it) }
                    EngineCheck(aligned.map { AgeGenderModel.input(it) to AgeGenderModel.SHAPE }) { a, b ->
                        var dp = 0f
                        var dAge = 0f
                        for (k in a.indices) {
                            val x = AgeGenderModel.decode(a[k])
                            val y = AgeGenderModel.decode(b[k])
                            dp = max(dp, abs(x.pMale - y.pMale))
                            dAge = max(dAge, abs(x.age - y.age))
                        }
                        Verdict(dp <= P_DIFF && dAge <= AGE_DIFF, "P(male) Δ %.4f, age Δ %.2f".format(dp, dAge))
                    }
                }
                ModelStore.NUDENET -> {
                    // two frames: the pipeline runs this model on batches of key frames
                    val frames = listOf(probe, mirrored(probe))
                    val (x, shape) = NudeNet.input(frames)
                    EngineCheck(listOf(x to shape)) { a, b ->
                        fun dets(o: List<Outputs>) = NudeNet.decode(o[0][0], frames, 0.2f).map { f -> f.map { Det(it.label, it.score, it.box) } }
                        boxes(dets(a), dets(b), strong = 0.4f)
                    }
                }
                ModelStore.SAM_ENCODER_512, ModelStore.SAM_ENCODER_1024 -> {
                    val size = if (file == ModelStore.SAM_ENCODER_1024) 1024 else 512
                    val (x, scale) = PersonMasks.input(probe, size)
                    EngineCheck(listOf(x to PersonMasks.shape(size))) { a, b ->
                        val dec = decoder()
                        fun masks(o: List<Outputs>) = PersonMasks.decode(dec, env, PersonMasks.embedding(o[0], scale, probe.width, probe.height), people, size)
                        val iou = masks(a).zip(masks(b)).minOf { (m, n) ->
                            var inter = 0
                            var union = 0
                            for (i in m.data.indices) {
                                val p = m.data[i] > 0f
                                val q = n.data[i] > 0f
                                if (p && q) inter++
                                if (p || q) union++
                            }
                            if (union == 0) 0f else inter.toFloat() / union
                        }
                        Verdict(iou >= MASK_IOU, "outline IoU %.4f".format(iou))
                    }
                }
                else -> null
            }
        }

        private fun mirrored(img: RgbImage): RgbImage {
            val out = RgbImage(img.width, img.height)
            for (y in 0 until img.height) for (x in 0 until img.width) {
                System.arraycopy(img.data, (y * img.width + x) * 3, out.data, (y * img.width + img.width - 1 - x) * 3, 3)
            }
            return out
        }

        /**
         * Segmenter: the skin mask (body-skin probability at the strict, middle and sensitive thresholds), the winning
         * class per pixel, and every class probability.
         */
        private fun segmenter(base: List<Outputs>, other: List<Outputs>): Verdict {
            val thresholds = floatArrayOf(0.3f, 0.5f, 0.7f)
            val inter = LongArray(thresholds.size)
            val union = LongArray(thresholds.size)
            var agree = 0L
            var total = 0L
            var maxDiff = 0f
            val c = SkinSegmenter.CLASSES
            val p = FloatArray(c)
            val q = FloatArray(c)
            for ((a, b) in base.zip(other)) {
                val x = a[0].second
                val y = b[0].second
                for (i in 0 until x.size / c) {
                    val am = softmax(x, i * c, p)
                    val bm = softmax(y, i * c, q)
                    if (am == bm) agree++
                    total++
                    for (k in 0 until c) maxDiff = max(maxDiff, abs(p[k] - q[k]))
                    for ((t, th) in thresholds.withIndex()) {
                        val s = p[2] >= th
                        val r = q[2] >= th
                        if (s && r) inter[t]++
                        if (s || r) union[t]++
                    }
                }
            }
            if (union[1] < 200) return Verdict(false, "no skin found in the probe")
            val iou = thresholds.indices.minOf { if (union[it] == 0L) 1f else inter[it].toFloat() / union[it] }
            val agreement = agree.toFloat() / max(1L, total)
            return Verdict(
                iou >= SKIN_IOU && agreement >= ARGMAX_AGREEMENT && maxDiff <= MAX_PROB_DIFF,
                "skin IoU %.4f, argmax %.4f, max Δp %.3f".format(iou, agreement, maxDiff),
            )
        }

        /** Softmax of [n] = out.size logits at [o] into [p]; returns the winning class. */
        private fun softmax(out: FloatArray, o: Int, p: FloatArray): Int {
            var mx = Float.NEGATIVE_INFINITY
            var best = 0
            for (k in p.indices) if (out[o + k] > mx) { mx = out[o + k]; best = k }
            var s = 0f
            for (k in p.indices) { p[k] = exp(out[o + k] - mx); s += p[k] }
            for (k in p.indices) p[k] /= s
            return best
        }

        /**
         * Detections per image: every [strong] detection of either engine must have a counterpart of the same label in
         * the other's (down to the decoding threshold, so a score just across [strong] is no difference) with box IoU
         * >= [BOX_IOU], score within [SCORE_DIFF] and points within [EYE_SHIFT] of the box side.
         */
        private fun boxes(a: List<List<Det>>, b: List<List<Det>>, strong: Float): Verdict {
            if (a.sumOf { d -> d.count { it.score >= strong } } == 0) return Verdict(false, "nothing found in the probe")
            var minIou = 1f
            var maxScore = 0f
            var maxShift = 0f
            for ((x, y) in a.zip(b)) for ((p, q) in listOf(x to y, y to x)) for (d in p) {
                if (d.score < strong) continue
                val m = q.filter { it.label == d.label }.maxByOrNull { it.box.iou(d.box) }
                if (m == null) { minIou = 0f; maxScore = 1f; continue }
                minIou = min(minIou, m.box.iou(d.box))
                maxScore = max(maxScore, abs(m.score - d.score))
                val side = max(1f, max(d.box.w, d.box.h))
                for (k in 0 until min(d.points.size, m.points.size) / 2) {
                    maxShift = max(maxShift, hypot(d.points[2 * k] - m.points[2 * k], d.points[2 * k + 1] - m.points[2 * k + 1]) / side)
                }
            }
            val n = a.sumOf { d -> d.count { it.score >= strong } }
            return Verdict(
                minIou >= BOX_IOU && maxScore <= SCORE_DIFF && maxShift <= EYE_SHIFT,
                "$n found, box IoU %.4f, score Δ %.3f".format(minIou, maxScore) + if (maxShift > 0f) ", points Δ %.3f".format(maxShift) else "",
            )
        }
    }
}
