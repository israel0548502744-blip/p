package com.blueshield.core.ml

import com.blueshield.core.PipelineSpec
import com.blueshield.core.image.Box
import com.blueshield.core.image.FloatMask
import com.blueshield.core.image.Par
import com.blueshield.core.image.Resample
import com.blueshield.core.image.RgbImage
import com.blueshield.core.image.nms
import com.blueshield.core.track.Detection
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Skin segmentation (MediaPipe Selfie Multiclass): body skin, face skin and person probability maps. */
class SkinSegmenter(private val models: ModelStore, private val faceExclusion: Float = 0.35f) {
    class Result(val skin: FloatMask, val person: FloatMask, val face: FloatMask)

    /** Model output for one (letterboxed) input: SIZE × SIZE probability maps + where the input sits in them. */
    private class Raw(val skin: FloatArray, val person: FloatArray, val face: FloatArray, val side: Int, val left: Int, val top: Int) {
        val k get() = SIZE.toFloat() / side

        /** Resample the maps for the input-image rectangle [x0,x1)×[y0,y1), written at an offset into a frame-sized buffer. */
        fun into(out: Result, outX0: Int, outY0: Int, x0: Int, y0: Int, x1: Int, y1: Int, max: Boolean) {
            val w = out.skin.width
            // destination pixel (X, Y) ↔ input pixel (X − outX0 + ox, …) ↔ map position ((… + left + 0.5) k − 0.5)
            val offX = (left - outX0).toFloat()
            val offY = (top - outY0).toFloat()
            Resample.bilinear(skin, SIZE, SIZE, k, k, offX, offY, out.skin.data, w, x0, y0, x1, y1, max)
            Resample.bilinear(person, SIZE, SIZE, k, k, offX, offY, out.person.data, w, x0, y0, x1, y1, max)
            Resample.bilinear(face, SIZE, SIZE, k, k, offX, offY, out.face.data, w, x0, y0, x1, y1, max)
        }
    }

    fun segment(img: RgbImage, includeFace: Boolean, tiled: Boolean = false): Result {
        val out = Result(FloatMask(img.width, img.height), FloatMask(img.width, img.height), FloatMask(img.width, img.height))
        run(img, includeFace).into(out, 0, 0, 0, 0, img.width, img.height, max = false)
        if (tiled && (img.width > img.height * 1.2f || img.height > img.width * 1.2f)) {
            val side = min(img.width, img.height)
            val long = max(img.width, img.height)
            val n = ceil(long.toFloat() / side).toInt() + 1
            val horizontal = img.width >= img.height
            for (i in 0 until n) {
                val off = Math.round(i * (long - side).toFloat() / max(n - 1, 1))
                val tile = if (horizontal) img.crop(off, 0, side, side) else img.crop(0, off, side, side)
                val ox = if (horizontal) off else 0
                val oy = if (horizontal) 0 else off
                run(tile, includeFace).into(out, ox, oy, ox, oy, ox + side, oy + side, max = true)
            }
        }
        return out
    }

    /**
     * Re-segment every person at a much higher effective resolution: the model only sees 256x256 pixels,
     * so on a whole frame an arm is a handful of pixels. Each person gets a square crop (no distortion) and
     * the result replaces [base] inside their slightly padded box. Only that box is resampled.
     */
    fun segmentRois(img: RgbImage, boxes: List<Box>, base: Result, includeFace: Boolean, roi: PipelineSpec.Roi, baseIsFresh: Boolean = false): Result {
        val w = img.width
        val h = img.height
        val roiOut = Result(FloatMask(w, h), FloatMask(w, h), FloatMask(w, h))
        val cover = BooleanArray(w * h)
        var any = false
        for (box in boxes) {
            val side = Math.round(max(box.w, box.h) * roi.sideScale)
            if (side < roi.minSidePx) continue
            // the crop would be the whole frame again: a fresh full pass already is exactly that
            if (baseIsFresh && side >= roi.maxFrameRatio * max(w, h)) continue
            val a = Math.round(box.cx - side / 2f)
            val b = Math.round(box.cy - side / 2f)
            val px = roi.pastePad * box.w
            val py = roi.pastePad * box.h
            val rx1 = max(max(0, (box.x1 - px).toInt()), a)
            val ry1 = max(max(0, (box.y1 - py).toInt()), b)
            val rx2 = min(min(w, (box.x2 + px).toInt() + 1), a + side)
            val ry2 = min(min(h, (box.y2 + py).toInt() + 1), b + side)
            if (rx2 <= rx1 || ry2 <= ry1) continue
            run(img.crop(a, b, side, side), includeFace).into(roiOut, a, b, rx1, ry1, rx2, ry2, max = true)
            for (y in ry1 until ry2) java.util.Arrays.fill(cover, y * w + rx1, y * w + rx2, true)
            any = true
        }
        if (!any) return base
        val skin = base.skin.copy()
        val person = base.person.copy()
        val face = base.face.copy()
        for (i in 0 until w * h) if (cover[i]) {
            skin.data[i] = roiOut.skin.data[i]
            person.data[i] = roiOut.person.data[i]
            face.data[i] = roiOut.face.data[i]
        }
        return Result(skin, person, face)
    }

    /** Runs the model on [img] letterboxed (edge-replicated) to a square, so portrait video keeps its proportions. */
    private fun run(img: RgbImage, includeFace: Boolean): Raw {
        val side = max(img.width, img.height)
        val left = (side - img.width) / 2
        val top = (side - img.height) / 2
        val sq = if (side == img.width && side == img.height) img else img.crop(-left, -top, side, side)
        val x = sq.resize(SIZE, SIZE)
        val input = FloatArray(SIZE * SIZE * 3) { (x.data[it].toInt() and 0xFF) / 127.5f - 1f }
        val out = models.session(ModelStore.SEGMENTER).runFloat(models.env, input, longArrayOf(1, SIZE.toLong(), SIZE.toLong(), 3)).first().second
        val skin = FloatArray(SIZE * SIZE)
        val person = FloatArray(SIZE * SIZE)
        val face = FloatArray(SIZE * SIZE)
        val p = FloatArray(6)
        for (i in 0 until SIZE * SIZE) {
            var mx = Float.NEGATIVE_INFINITY
            for (c in 0 until 6) mx = max(mx, out[i * 6 + c])
            var s = 0f
            for (c in 0 until 6) {
                p[c] = exp(out[i * 6 + c] - mx)
                s += p[c]
            }
            val bodySkin = p[2] / s
            val faceSkin = p[3] / s
            face[i] = faceSkin
            // the body-skin class sometimes bleeds onto faces (glasses, side light): keep faces clear
            skin[i] = if (includeFace) max(bodySkin, faceSkin) else if (faceSkin >= faceExclusion) 0f else bodySkin
            person[i] = 1f - p[0] / s
        }
        return Raw(skin, person, face, side, left, top)
    }

    companion object {
        const val SIZE = 256
    }
}

/** Stage 1 — person detection (EfficientDet-Lite0, COCO "person"), with SSD anchor decoding. */
class PersonDetector(private val models: ModelStore) {
    private val anchors: FloatArray = buildAnchors()

    fun detect(img: RgbImage, minScore: Float): List<Detection> {
        val x = img.resize(INPUT, INPUT)
        val input = FloatArray(INPUT * INPUT * 3) { ((x.data[it].toInt() and 0xFF) - 127f) / 128f }
        val outs = models.session(ModelStore.PERSONS).runFloat(models.env, input, longArrayOf(1, INPUT.toLong(), INPUT.toLong(), 3))
        val scores = outs.first { it.first.last() != 4L }
        val deltas = outs.first { it.first.last() == 4L }.second
        val numClasses = scores.first.last().toInt()
        val n = deltas.size / 4
        val boxes = ArrayList<Box>()
        val sc = ArrayList<Float>()
        val sx = img.width.toFloat() / INPUT
        val sy = img.height.toFloat() / INPUT
        for (i in 0 until n) {
            val s = scores.second[i * numClasses] // class 0 = person
            if (s < minScore) continue
            val cy = deltas[i * 4] * anchors[i * 4 + 2] + anchors[i * 4]
            val cx = deltas[i * 4 + 1] * anchors[i * 4 + 3] + anchors[i * 4 + 1]
            val h = exp(deltas[i * 4 + 2]) * anchors[i * 4 + 2]
            val w = exp(deltas[i * 4 + 3]) * anchors[i * 4 + 3]
            val b = Box(
                ((cx - w / 2) * sx).coerceIn(0f, img.width.toFloat()), ((cy - h / 2) * sy).coerceIn(0f, img.height.toFloat()),
                ((cx + w / 2) * sx).coerceIn(0f, img.width.toFloat()), ((cy + h / 2) * sy).coerceIn(0f, img.height.toFloat()),
            )
            if (b.w > 4 && b.h > 8) {
                boxes += b
                sc += s
            }
        }
        return suppressContained(nms(boxes, sc, 0.5f).map { Detection("person", sc[it], boxes[it]) })
    }

    companion object {
        const val INPUT = 320
        /** A box this much inside a bigger one is a partial (e.g. upper-body) duplicate. */
        const val CONTAINED_MIN = 0.8f

        /**
         * Drop partial duplicates: a detection lying almost entirely inside a bigger one of the same person.
         * Plain NMS keeps them (small IoU); left alone they become a second "person" that splits the gender
         * evidence. The smaller box survives only when the detector is clearly more confident about it.
         */
        fun suppressContained(dets: List<Detection>): List<Detection> {
            val keep = ArrayList<Detection>()
            for (d in dets.sortedByDescending { it.box.area }) {
                if (keep.any { k -> d.box.containedIn(k.box) > CONTAINED_MIN && d.score < k.score + 0.15f }) continue
                keep += d
            }
            return keep
        }

        /** EfficientDet anchors: levels 3–7, 3 octaves × 3 aspect ratios, laid out (cy, cx, h, w). */
        fun buildAnchors(): FloatArray {
            val out = ArrayList<Float>()
            for (lvl in 3..7) {
                val stride = 1 shl lvl
                val g = ceil(INPUT.toDouble() / stride).toInt()
                val cfg = ArrayList<Pair<Float, Float>>()
                for (octave in 0 until 3) for (ar in floatArrayOf(1f, 2f, 0.5f)) {
                    val base = 4f * stride * Math.pow(2.0, octave / 3.0).toFloat()
                    cfg += (base / sqrt(ar)) to (base * sqrt(ar))
                }
                for (y in 0 until g) for (x in 0 until g) for ((ah, aw) in cfg) {
                    out += (y + 0.5f) * stride
                    out += (x + 0.5f) * stride
                    out += ah
                    out += aw
                }
            }
            return out.toFloatArray()
        }
    }
}

/** BlazeFace (short range) face detector with eye keypoints. Coordinates in the input image's pixels. */
class FaceDetector(private val models: ModelStore) {
    class Face(val box: Box, val score: Float, val rightEye: Pair<Float, Float>, val leftEye: Pair<Float, Float>)

    private val anchors: FloatArray = run {
        val a = ArrayList<Float>()
        for ((stride, perCell) in listOf(8 to 2, 16 to 6)) {
            val g = INPUT / stride
            for (y in 0 until g) for (x in 0 until g) repeat(perCell) {
                a += (x + 0.5f) / g
                a += (y + 0.5f) / g
            }
        }
        a.toFloatArray()
    }

    fun detect(img: RgbImage, minScore: Float): List<Face> {
        val x = img.resize(INPUT, INPUT)
        val input = FloatArray(INPUT * INPUT * 3) { (x.data[it].toInt() and 0xFF) / 127.5f - 1f }
        val outs = models.session(ModelStore.FACES).runFloat(models.env, input, longArrayOf(1, INPUT.toLong(), INPUT.toLong(), 3))
        val reg = outs.first { it.first.last() == 16L }.second
        val cls = outs.first { it.first.last() == 1L }.second
        val faces = ArrayList<Face>()
        val w = img.width.toFloat()
        val h = img.height.toFloat()
        for (i in cls.indices) {
            val s = 1f / (1f + exp(-cls[i].coerceIn(-80f, 80f)))
            if (s < minScore) continue
            val ax = anchors[i * 2]
            val ay = anchors[i * 2 + 1]
            val r = { k: Int -> reg[i * 16 + k] / INPUT }
            val cx = r(0) + ax
            val cy = r(1) + ay
            val bw = r(2)
            val bh = r(3)
            faces += Face(
                Box((cx - bw / 2) * w, (cy - bh / 2) * h, (cx + bw / 2) * w, (cy + bh / 2) * h), s,
                (r(4) + ax) * w to (r(5) + ay) * h, (r(6) + ax) * w to (r(7) + ay) * h,
            )
        }
        return nms(faces.map { it.box }, faces.map { it.score }, 0.3f).map { faces[it] }
    }

    companion object {
        const val INPUT = 128
    }
}

/** Gender model (FaceRes MobileNet): eye-aligned 224×224 RGB face (0..255) -> P(male). */
/**
 * Second face model (face-api.js AgeGenderNet, TinyXception, trained on UTKFace — which includes children):
 * P(male) and an age estimate from the same eye-aligned 224 px face crop the FaceRes model sees.
 */
class AgeGenderModel(private val models: ModelStore) {
    class Result(val pMale: Float, val age: Float)

    fun predict(aligned224: RgbImage): Result {
        // the aligned crop is 1.4× the face box; this model was trained on tighter face crops
        val m = Math.round(aligned224.width * (1 - TIGHT) / 2)
        val face = aligned224.crop(m, m, aligned224.width - 2 * m, aligned224.height - 2 * m).resize(INPUT, INPUT)
        val input = FloatArray(INPUT * INPUT * 3) { (face.data[it].toInt() and 0xFF).toFloat() }
        val outs = models.session(ModelStore.AGE_GENDER).runFloat(models.env, input, longArrayOf(1, INPUT.toLong(), INPUT.toLong(), 3))
        val age = outs.first { it.second.size == 1 }.second[0]
        val gender = outs.first { it.second.size == 2 }.second
        return Result(gender[0], age)
    }

    companion object {
        const val INPUT = 112
        const val TIGHT = 0.85f
    }
}

class GenderModel(private val models: ModelStore) {
    fun pMale(face: RgbImage): Float {
        require(face.width == INPUT && face.height == INPUT)
        val input = FloatArray(INPUT * INPUT * 3) { (face.data[it].toInt() and 0xFF).toFloat() }
        return models.session(ModelStore.GENDER).runFloat(models.env, input, longArrayOf(1, INPUT.toLong(), INPUT.toLong(), 3)).first().second[0]
    }

    /** Rotate so the eyes are level, scale the face box (×1.4) to 224 px. Mirrors cv2.getRotationMatrix2D. */
    fun align(img: RgbImage, face: FaceDetector.Face): RgbImage {
        val (rx, ry) = face.rightEye
        val (lx, ly) = face.leftEye
        var angle = Math.toDegrees(kotlin.math.atan2((ly - ry).toDouble(), (lx - rx).toDouble()))
        if (kotlin.math.abs(angle) > 90) angle -= 180 * kotlin.math.sign(angle)
        val side = max(face.box.w, face.box.h) * 1.4f
        val scale = INPUT / side
        val a = Math.toRadians(angle)
        val alpha = (scale * kotlin.math.cos(a)).toFloat()
        val beta = (scale * kotlin.math.sin(a)).toFloat()
        val cx = face.box.cx
        val cy = face.box.cy
        // forward M = [[alpha, beta, (1-alpha)cx - beta cy + (224/2 - cx)], [-beta, alpha, beta cx + (1-alpha) cy + (224/2 - cy)]]
        val m02 = (1 - alpha) * cx - beta * cy + (INPUT / 2f - cx)
        val m12 = beta * cx + (1 - alpha) * cy + (INPUT / 2f - cy)
        val det = alpha * alpha + beta * beta
        val inv = floatArrayOf(
            alpha / det, -beta / det, -(alpha * m02 - beta * m12) / det,
            beta / det, alpha / det, -(beta * m02 + alpha * m12) / det,
        )
        return img.warpAffineInverse(inv, INPUT, INPUT)
    }

    companion object {
        const val INPUT = 224
    }
}

/** NudeNet v3 (YOLOv8) sensitive-region detector, batched. */
class NudeNet(private val models: ModelStore) {
    fun detect(frames: List<RgbImage>, minScore: Float): List<List<Detection>> {
        if (frames.isEmpty()) return emptyList()
        val b = frames.size
        val plane = INPUT * INPUT
        val input = FloatArray(b * 3 * plane)
        val scales = FloatArray(b)
        for ((k, f) in frames.withIndex()) {
            val sq = f.padToSquare()
            scales[k] = sq.width.toFloat() / INPUT
            val r = sq.resize(INPUT, INPUT)
            for (i in 0 until plane) for (c in 0 until 3) input[k * 3 * plane + c * plane + i] = (r.data[i * 3 + c].toInt() and 0xFF) / 255f
        }
        val (shape, out) = models.session(ModelStore.NUDENET).runFloat(models.env, input, longArrayOf(b.toLong(), 3, INPUT.toLong(), INPUT.toLong())).first()
        val ch = shape[1].toInt()
        val n = shape[2].toInt()
        return frames.indices.map { k ->
            val base = k * ch * n
            val boxes = ArrayList<Box>()
            val scores = ArrayList<Float>()
            val labels = ArrayList<String>()
            for (i in 0 until n) {
                var best = -1
                var bestS = minScore
                for (c in 4 until ch) {
                    val s = out[base + c * n + i]
                    if (s >= bestS) {
                        bestS = s
                        best = c - 4
                    }
                }
                if (best < 0) continue
                val cx = out[base + i]
                val cy = out[base + n + i]
                val w = out[base + 2 * n + i]
                val h = out[base + 3 * n + i]
                val s = scales[k]
                val f = frames[k]
                boxes += Box(
                    max(0f, (cx - w / 2) * s), max(0f, (cy - h / 2) * s),
                    min(f.width.toFloat(), (cx + w / 2) * s), min(f.height.toFloat(), (cy + h / 2) * s),
                )
                scores += bestS
                labels += LABELS[best]
            }
            nms(boxes, scores, 0.45f).map { Detection(labels[it], scores[it], boxes[it]) }
        }
    }

    companion object {
        const val INPUT = 320
        /** A box this much inside a bigger one is a partial (e.g. upper-body) duplicate. */
        const val CONTAINED_MIN = 0.8f

        /**
         * Drop partial duplicates: a detection lying almost entirely inside a bigger one of the same person.
         * Plain NMS keeps them (small IoU); left alone they become a second "person" that splits the gender
         * evidence. The smaller box survives only when the detector is clearly more confident about it.
         */
        fun suppressContained(dets: List<Detection>): List<Detection> {
            val keep = ArrayList<Detection>()
            for (d in dets.sortedByDescending { it.box.area }) {
                if (keep.any { k -> d.box.containedIn(k.box) > CONTAINED_MIN && d.score < k.score + 0.15f }) continue
                keep += d
            }
            return keep
        }
        val LABELS = listOf(
            "FEMALE_GENITALIA_COVERED", "FACE_FEMALE", "BUTTOCKS_EXPOSED", "FEMALE_BREAST_EXPOSED",
            "FEMALE_GENITALIA_EXPOSED", "MALE_BREAST_EXPOSED", "ANUS_EXPOSED", "FEET_EXPOSED",
            "BELLY_COVERED", "FEET_COVERED", "ARMPITS_COVERED", "ARMPITS_EXPOSED", "FACE_MALE",
            "BELLY_EXPOSED", "MALE_GENITALIA_EXPOSED", "ANUS_COVERED", "FEMALE_BREAST_COVERED",
            "BUTTOCKS_COVERED",
        )
    }
}

/** Colour-based skin likelihood (aggressive-mode backup), same ranges as the desktop app. */
object ColorSkin {
    /** 1 where the pixel colour could be skin, 0 for bluish or near-black pixels (soft-edged). Mirrors desktop `skin_color_plausible`. */
    fun plausible(img: RgbImage, maxBlueOverRed: Int, minLuma: Int): FloatMask {
        val w = img.width
        val h = img.height
        val ok = FloatArray(w * h)
        val src = img.data
        Par.rows(h, w) { y ->
            for (i in y * w until (y + 1) * w) {
                val r = src[i * 3].toInt() and 0xFF
                val g = src[i * 3 + 1].toInt() and 0xFF
                val b = src[i * 3 + 2].toInt() and 0xFF
                val luma = (299 * r + 587 * g + 114 * b) / 1000
                ok[i] = if (b - r <= maxBlueOverRed && luma >= minLuma) 1f else 0f
            }
        }
        // separable 5-tap gaussian, sigma 1
        val k = floatArrayOf(0.0545f, 0.2442f, 0.4026f, 0.2442f, 0.0545f)
        val tmp = FloatArray(w * h)
        Par.rows(h, w) { y ->
            for (x in 0 until w) {
                var s = 0f
                for (d in -2..2) s += k[d + 2] * ok[y * w + (x + d).coerceIn(0, w - 1)]
                tmp[y * w + x] = s
            }
        }
        val out = FloatMask(w, h)
        Par.rows(h, w) { y ->
            for (x in 0 until w) {
                var s = 0f
                for (d in -2..2) s += k[d + 2] * tmp[(y + d).coerceIn(0, h - 1) * w + x]
                out.data[y * w + x] = s
            }
        }
        return out
    }

    fun probability(img: RgbImage): FloatMask {
        val out = FloatMask(img.width, img.height)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val r = img.r(x, y)
            val g = img.g(x, y)
            val b = img.b(x, y)
            val yy = 0.299f * r + 0.587f * g + 0.114f * b
            val cr = (r - yy) * 0.713f + 128
            val cb = (b - yy) * 0.564f + 128
            val mx = maxOf(r, g, b)
            val mn = minOf(r, g, b)
            val d = (mx - mn).toFloat()
            var hue = when {
                d == 0f -> 0f
                mx == r -> 60f * (((g - b) / d) % 6f)
                mx == g -> 60f * ((b - r) / d + 2f)
                else -> 60f * ((r - g) / d + 4f)
            }
            if (hue < 0) hue += 360f
            val h = hue / 2f
            val s = if (mx == 0) 0f else d / mx * 255f
            val m1 = cr in 135f..180f && cb in 85f..135f && yy > 40
            val m2 = (h <= 25f || h >= 165f) && s in 30f..200f && mx >= 50
            out.data[y * img.width + x] = if (m1 && m2) 1f else 0f
        }
        return out
    }
}
