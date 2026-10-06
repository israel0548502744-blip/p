package com.blueshield.core.gender

import com.blueshield.core.image.Box
import com.blueshield.core.image.FloatMask
import com.blueshield.core.image.MaskOps
import com.blueshield.core.image.RgbImage
import com.blueshield.core.ml.AgeGenderModel
import com.blueshield.core.ml.FaceDetector
import com.blueshield.core.ml.GenderModel
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Stage 2 — classify one person: locate the head (facial-skin blob from segmentation,
 * else an upper-body crop), detect the face, align it by the eyes, run the gender model.
 * Returns (P(male), vote weight) or null when there is no usable face.
 */
class GenderClassifier(
    private val faces: FaceDetector, private val model: GenderModel, private val minFaceScore: Float, private val minFacePx: Float,
    private val ageGender: AgeGenderModel? = null,
) {
    /** One look at a face: combined P(male), vote weight, estimated age (NaN when unknown). */
    data class Observation(val pMale: Float, val weight: Float, val age: Float, val face: Box? = null)

    /**
     * Returns an [Observation] or null when there is no usable face. With the second model, P(male) is the
     * average of both models' log-odds: their mistakes are largely independent (e.g. older women, whom
     * FaceRes alone often calls male), so the ensemble is much steadier than either.
     */
    fun classify(frame: RgbImage, box: Box, faceMap: FloatMask?): Observation? {
        val crops = ArrayList<Crop>()
        val head = if (faceMap != null) headCrop(frame, box, faceMap) else null
        head?.let { crops += it }
        topCrop(frame, box)?.let { crops += it }
        upperBodyCrop(frame, box)?.let { crops += it }
        for ((n, pair) in crops.withIndex()) {
            val (crop, scale, ox, oy) = pair
            val found = faces.detect(crop, minFaceScore).filter { it.box.cy < 0.75f * crop.height }
            val face = pickFace(found, crop.width.toFloat(), centred = n == 0 && head != null) ?: continue
            val facePx = face.box.w * scale
            if (facePx < minFacePx) continue
            val weight = face.score * min(1f, facePx / 48f)
            val inFrame = Box(ox + face.box.x1 * scale, oy + face.box.y1 * scale, ox + face.box.x2 * scale, oy + face.box.y2 * scale)
            val aligned = model.align(crop, face)
            val p1 = model.pMale(aligned)
            val second = ageGender?.predict(aligned) ?: return Observation(p1, weight, Float.NaN, inFrame)
            return Observation(ensemble(p1, second.pMale), weight, second.age, inFrame)
        }
        return null
    }

    /** A square crop resized to [CROP]: crop pixel × [scale] + ([x], [y]) = frame pixel. */
    data class Crop(val image: RgbImage, val scale: Float, val x: Float, val y: Float)

    companion object {
        const val CROP = 256

        /**
         * How much [face] looks like the head of [box]: 0 at the top centre, growing downwards and sideways.
         * A face that is someone else's (a child in front of an adult) scores better in its owner's box.
         */
        fun headScore(face: Box, box: Box): Float =
            (face.cy - box.y1) / max(1f, box.h) + 0.5f * abs(face.cx - box.cx) / max(1f, box.w)

        /** True unless the face centre lies in another box that it fits clearly better as a head. */
        fun ownsFace(face: Box, own: Box, others: List<Box>): Boolean {
            val mine = headScore(face, own)
            return others.none { o ->
                face.cx in o.x1..o.x2 && face.cy in o.y1..o.y2 && headScore(face, o) + 0.08f < mine
            }
        }

        /**
         * The face that belongs to the person: a person's own head is at the top of their box, so among the
         * clearly-sized faces take the topmost (a child standing in front of an adult must not lend the adult
         * its face). In a head crop the face is the one nearest the centre.
         */
        fun pickFace(found: List<FaceDetector.Face>, side: Float, centred: Boolean): FaceDetector.Face? {
            val biggest = found.maxOfOrNull { it.box.w } ?: return null
            val sized = found.filter { it.box.w >= 0.6f * biggest }
            return if (centred) sized.minByOrNull { abs(it.box.cx - side / 2) + abs(it.box.cy - side / 2) }
            else sized.minByOrNull { it.box.y1 }
        }

        /** Mean of the two log-odds, back to a probability. */
        fun ensemble(a: Float, b: Float): Float {
            fun logit(p: Float): Double = p.toDouble().coerceIn(0.02, 0.98).let { ln(it / (1 - it)) }
            return (1.0 / (1.0 + exp(-(logit(a) + logit(b)) / 2))).toFloat()
        }

        /** Square crop around the largest facial-skin blob in the top 60 % of the person box. */
        fun headCrop(frame: RgbImage, box: Box, faceMap: FloatMask): Crop? {
            val x0 = max(0, box.x1.roundToInt())
            val y0 = max(0, box.y1.roundToInt())
            val x1 = min(frame.width, box.x2.roundToInt())
            val y1 = min(frame.height, y0 + max(8, ((box.y2 - box.y1) * 0.6f).toInt()))
            val w = x1 - x0
            val h = y1 - y0
            if (w < 8 || h < 8) return null
            val on = BooleanArray(w * h) { faceMap[x0 + it % w, y0 + it / w] > 0.4f }
            val (labels, count) = MaskOps.connectedComponents(on, w, h)
            if (count <= 1) return null
            val area = IntArray(count)
            val minX = IntArray(count) { Int.MAX_VALUE }
            val minY = IntArray(count) { Int.MAX_VALUE }
            val maxX = IntArray(count) { -1 }
            val maxY = IntArray(count) { -1 }
            for (i in labels.indices) {
                val l = labels[i]
                if (l == 0) continue
                area[l]++
                val x = i % w
                val y = i / w
                minX[l] = min(minX[l], x); maxX[l] = max(maxX[l], x)
                minY[l] = min(minY[l], y); maxY[l] = max(maxY[l], y)
            }
            // The topmost sizeable blob is this person's face; a larger one lower down is someone in front.
            val biggest = (1 until count).maxOfOrNull { area[it] } ?: return null
            if (biggest < 30) return null
            val k = (1 until count).filter { area[it] >= 0.35f * biggest }.minByOrNull { minY[it] } ?: return null
            val fw = maxX[k] - minX[k] + 1
            val fh = maxY[k] - minY[k] + 1
            val cx = x0 + minX[k] + fw / 2f
            val cy = y0 + minY[k] + fh / 2f
            val side = max(fw, fh) * 2.4f
            val x = (cx - side / 2).roundToInt()
            val y = (cy - side / 2).roundToInt()
            val crop = frame.crop(x, y, side.roundToInt(), side.roundToInt())
            return Crop(crop.resize(CROP, CROP), side.roundToInt().toFloat() / CROP, x.toFloat(), y.toFloat())
        }

        /** Head-sized square at the top centre of a tall (full-body) box — faces of distant, standing people. */
        fun topCrop(frame: RgbImage, box: Box): Crop? {
            if (box.h < 2.2f * box.w * 0.6f || box.h < 40f) return null
            val side = min(box.w, 0.36f * box.h)
            if (side < 12f) return null
            val cy = box.y1 + 0.42f * side
            val x = (box.cx - side / 2).roundToInt()
            val y = (cy - side / 2).roundToInt()
            val crop = frame.crop(x, y, side.roundToInt(), side.roundToInt())
            return Crop(crop.resize(CROP, CROP), side.roundToInt().toFloat() / CROP, x.toFloat(), y.toFloat())
        }

        /** Square head/torso crop (padded with edge pixels), for when no facial-skin blob was found. */
        fun upperBodyCrop(frame: RgbImage, box: Box): Crop? {
            if (box.w < 12 || box.h < 24) return null
            val side = max(box.w * 1.1f, min(box.h, box.w * 1.6f) * 0.75f)
            val top = box.y1 - 0.05f * box.h
            val a = max(0f, box.cx - side / 2).toInt()
            val b = max(0f, top).toInt()
            val c = min(frame.width.toFloat(), box.cx + side / 2).toInt()
            val d = min(frame.height.toFloat(), top + side).toInt()
            if (c - a < 12 || d - b < 12) return null
            val s = max(c - a, d - b)
            return Crop(frame.crop(a, b, s, s).resize(CROP, CROP), s.toFloat() / CROP, a.toFloat(), b.toFloat())
        }
    }
}
