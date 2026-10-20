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
    /** The second model's gender vote counts not at all on a face it puts at [secondMinAge] or younger, fully from [secondFullAge]. */
    private val secondMinAge: Float = 14f, private val secondFullAge: Float = 18f,
) {
    /** One look at a face: combined P(male), vote weight, estimated age (NaN when unknown). */
    data class Observation(val pMale: Float, val weight: Float, val age: Float, val face: Box? = null)

    /**
     * Returns an [Observation] or null when there is no usable face. With the second model, P(male) is the
     * average of both models' log-odds: their mistakes are largely independent (e.g. older women, whom
     * FaceRes alone often calls male), so the ensemble is much steadier than either. On a face the second model
     * itself takes for a child or young teen its gender vote is faded out ([secondWeight]): it calls girls and
     * young-looking women male (UTKFace children; a smiling 20-year-old with her hair tied back: age 13.6,
     * P(male) 0.96, which outvoted FaceRes's unsure 0.68 and left her uncensored; with FaceRes's own preprocessing,
     * see [GenderModel.input], FaceRes puts her at 0.40, and at 0.25 from the full-size photo).
     *
     * [source]: the photo at its own resolution (photos only). The face is still found in the analysis frame, but
     * the models see it cut from the photo: a face 60 px wide in the frame may be twice that in the photo.
     */
    fun classify(frame: RgbImage, box: Box, faceMap: FloatMask?, others: List<Box> = emptyList(), source: FaceSource? = null): Observation? {
        val (crop, face) = locate(frame, box, faceMap, others) ?: return null
        val facePx = face.box.w * crop.scale
        val weight = face.score * min(1f, facePx / 48f)
        val inFrame = Box(crop.x + face.box.x1 * crop.scale, crop.y + face.box.y1 * crop.scale, crop.x + face.box.x2 * crop.scale, crop.y + face.box.y2 * crop.scale)
        val aligned = if (source == null) model.align(crop.image, face) else closer(source, crop, face).let { (img, f) -> model.align(img, f) }
        val p1 = model.pMale(aligned)
        val second = ageGender?.predict(aligned) ?: return Observation(p1, weight, Float.NaN, inFrame)
        return Observation(ensemble(p1, second.pMale, secondWeight(second.age, secondMinAge, secondFullAge)), weight, second.age, inFrame)
    }

    /**
     * [face] (found in [crop]) cut from the original photo: a square of twice the face's size around it, at the
     * photo's resolution but at most [CLOSE_PX] (what the aligned 224 px crop needs), with the face's box and eyes
     * in its pixels.
     */
    private fun closer(source: FaceSource, crop: Crop, face: FaceDetector.Face): Pair<RgbImage, FaceDetector.Face> {
        fun fx(v: Float) = crop.x + v * crop.scale
        fun fy(v: Float) = crop.y + v * crop.scale
        val b = face.box
        val side = 2f * max(b.w, b.h) * crop.scale
        val sq = source.square(fx(b.cx) - side / 2, fy(b.cy) - side / 2, side, CLOSE_PX)
        fun ix(v: Float) = (fx(v) - sq.x) * sq.scale
        fun iy(v: Float) = (fy(v) - sq.y) * sq.scale
        return sq.image to FaceDetector.Face(
            Box(ix(b.x1), iy(b.y1), ix(b.x2), iy(b.y2)), face.score,
            ix(face.rightEye.first) to iy(face.rightEye.second), ix(face.leftEye.first) to iy(face.leftEye.second),
        )
    }

    /**
     * This person's face (frame pixels) without classifying it: faces the sensitive-region detector misses (small,
     * turned, in the dark, in a crowd) must still stay uncovered, and need not be big enough to classify.
     */
    fun face(frame: RgbImage, box: Box, faceMap: FloatMask?, others: List<Box> = emptyList(), minPx: Float = 6f): Box? {
        val (crop, face) = locate(frame, box, faceMap, others, minPx) ?: return null
        return Box(crop.x + face.box.x1 * crop.scale, crop.y + face.box.y1 * crop.scale, crop.x + face.box.x2 * crop.scale, crop.y + face.box.y2 * crop.scale)
    }

    /** The crop and the face in it (crop pixels) for the person in [box], or null. */
    private fun locate(frame: RgbImage, box: Box, faceMap: FloatMask?, others: List<Box>, minPx: Float = minFacePx): Pair<Crop, FaceDetector.Face>? {
        val crops = ArrayList<Crop>()
        val head = if (faceMap != null) headCrop(frame, box, faceMap, others) else null
        head?.let { crops += it }
        topCrop(frame, box)?.let { crops += it }
        upperBodyCrop(frame, box)?.let { crops += it }
        for ((n, pair) in crops.withIndex()) {
            val (crop, scale, ox, oy) = pair
            fun inFrame(b: Box) = Box(ox + b.x1 * scale, oy + b.y1 * scale, ox + b.x2 * scale, oy + b.y2 * scale)
            // A face belongs to one person: skip faces that sit where another person's head is
            // (a child in front of her mother, the man next to the woman in a crowded photo).
            val found = faces.detect(crop, minFaceScore)
                .filter { it.box.cy < 0.75f * crop.height && ownsFace(inFrame(it.box), box, others) }
            val face = pickFace(found, crop.width.toFloat(), centred = n == 0 && head != null) ?: continue
            if (face.box.w * scale < minPx) continue
            return pair to face
        }
        return null
    }

    /** A square crop resized to [CROP]: crop pixel × [scale] + ([x], [y]) = frame pixel. */
    data class Crop(val image: RgbImage, val scale: Float, val x: Float, val y: Float)

    companion object {
        const val CROP = 256
        /** [closer]: the square around a face, at most this wide (twice the 160 px the face spans in the aligned crop). */
        const val CLOSE_PX = 320

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

        /** Mean of the two log-odds, [b]'s counting [wb] (0..1), back to a probability. */
        fun ensemble(a: Float, b: Float, wb: Float = 1f): Float {
            fun logit(p: Float): Double = p.toDouble().coerceIn(0.02, 0.98).let { ln(it / (1 - it)) }
            return (1.0 / (1.0 + exp(-(logit(a) + wb * logit(b)) / (1 + wb)))).toFloat()
        }

        /** Weight of the second model's gender vote at its own age estimate: 0 up to [minAge], 1 from [fullAge]. */
        fun secondWeight(age: Float, minAge: Float, fullAge: Float): Float =
            if (!age.isFinite()) 1f else ((age - minAge) / max(1e-3f, fullAge - minAge)).coerceIn(0f, 1f)

        /** Square crop around the largest facial-skin blob in the top 60 % of the person box. */
        fun headCrop(frame: RgbImage, box: Box, faceMap: FloatMask, others: List<Box> = emptyList()): Crop? {
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
            // Of the sizeable facial-skin blobs, the one that sits best where this person's head should be and is
            // not a better fit for someone else's head (a neighbour's face inside a loose box, a child in front).
            val biggest = (1 until count).maxOfOrNull { area[it] } ?: return null
            if (biggest < 30) return null
            fun blobBox(l: Int) = Box((x0 + minX[l]).toFloat(), (y0 + minY[l]).toFloat(), (x0 + maxX[l] + 1).toFloat(), (y0 + maxY[l] + 1).toFloat())
            val k = (1 until count).filter { area[it] >= max(30f, 0.2f * biggest) && ownsFace(blobBox(it), box, others) }
                .minByOrNull { headScore(blobBox(it), box) } ?: return null
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
