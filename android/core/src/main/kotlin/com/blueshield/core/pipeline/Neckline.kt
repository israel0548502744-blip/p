package com.blueshield.core.pipeline

import com.blueshield.core.PipelineSpec
import com.blueshield.core.image.Box
import com.blueshield.core.image.MaskOps
import kotlin.math.max
import kotlin.math.min

/**
 * Face, neck and neckline rules on a frame's binary skin mask (same algorithm as the desktop `apply_neckline`).
 *
 * * the face itself is never censored (forehead to chin);
 * * the neck is not censored either…
 * * …unless the bare skin continues down into the chest (a low neckline): then everything from
 *   about one centimetre below the chin downwards is censored.
 *
 * All sizes are relative to the face box (NudeNet face detection, motion-tracked), so it works at any zoom.
 */
object Neckline {
    /** Inner part of the face ellipse that is always cleared; the rim only where the segmenter sees face skin. */
    private const val INNER = 0.75f
    private const val FACE_EDGE_PROB = 0.2f
    private const val JAW = 0.25f

    /** [faceProb]: the segmenter's facial-skin probability (frame-sized), so a hand held at the cheek stays censored. */
    /**
     * Returns whether a low neckline was seen in this frame. [knownCleavage]: it was already seen on this face
     * earlier — the decision sticks, so the censoring doesn't flicker when the head turns or tilts.
     */
    fun apply(
        skin: BooleanArray, w: Int, h: Int, face: Box, n: PipelineSpec.Neckline, faceProb: FloatArray? = null,
        knownCleavage: Boolean = false,
    ): Boolean {
        val fw = face.w
        val fh = face.h
        if (fw < 2f || fh < 2f) return false
        // face: ellipse from the forehead to just under the chin (a hand next to the face stays censored)
        val cx = face.cx
        val cy = face.cy - 0.12f * fh
        val ax = 0.65f * fw
        val ay = 0.72f * fh
        fill(skin, w, h, (cx - ax).toInt(), (cy - ay).toInt(), (cx + ax).toInt() + 1, (cy + ay).toInt() + 1) { x, y ->
            val dx = (x + 0.5f - cx) / ax
            val dy = (y + 0.5f - cy) / ay
            val r2 = dx * dx + dy * dy
            r2 <= INNER * INNER || (r2 <= 1f && (faceProb == null || faceProb[y * w + x] >= FACE_EDGE_PROB))
        }
        val chin = face.y2
        val bandBottom = chin + n.bandHeight * fh
        // is there bare skin straight below the neck (a neckline that shows the chest)?
        val px0 = max(0, (cx - n.probeHalfWidth * fw).toInt())
        val px1 = min(w, (cx + n.probeHalfWidth * fw).toInt() + 1)
        val py0 = max(0, bandBottom.toInt())
        val py1 = min(h, (bandBottom + n.probeHeight * fh).toInt() + 1)
        var on = 0
        var total = 0
        for (y in py0 until py1) for (x in px0 until px1) {
            total++
            if (skin[y * w + x]) on++
        }
        val seen = total > 0 && on >= n.cleavageMinFill * total
        val clearTo = if (seen || knownCleavage) chin + n.cleavageStart * fh else bandBottom
        val bx0 = (cx - n.bandHalfWidth * fw).toInt()
        val bx1 = (cx + n.bandHalfWidth * fw).toInt() + 1
        // the band starts a little above the chin line, so the jaw corners outside the face ellipse are covered too
        fill(skin, w, h, bx0, (chin - JAW * fh).toInt(), bx1, clearTo.toInt() + 1) { _, _ -> true }
        return seen
    }

    private inline fun fill(skin: BooleanArray, w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int, inside: (Int, Int) -> Boolean) {
        for (y in max(0, y0) until min(h, y1)) for (x in max(0, x0) until min(w, x1)) if (inside(x, y)) skin[y * w + x] = false
    }

    /**
     * Drops tiny isolated skin specks (patterned or pink clothes, reflections): a component smaller than
     * [personFrac] of the person box it sits in — or [frameFrac] of the frame outside every box — is noise.
     */
    fun removeSpecks(skin: BooleanArray, w: Int, h: Int, boxes: List<Box>, personFrac: Float, frameFrac: Float) {
        val (labels, count) = MaskOps.connectedComponents(skin, w, h)
        if (count <= 1) return
        val area = IntArray(count)
        val sx = DoubleArray(count)
        val sy = DoubleArray(count)
        for (i in labels.indices) {
            val l = labels[i]
            if (l == 0) continue
            area[l]++
            sx[l] += (i % w).toDouble()
            sy[l] += (i / w).toDouble()
        }
        val drop = BooleanArray(count)
        for (l in 1 until count) {
            val mx = (sx[l] / area[l]).toFloat()
            val my = (sy[l] / area[l]).toFloat()
            val owner = boxes.filter { it.contains(mx, my) }.minByOrNull { it.area }
            val minArea = if (owner != null) min(personFrac * owner.area, frameFrac * w * h * 4) else frameFrac * w * h
            drop[l] = area[l] < minArea
        }
        for (i in labels.indices) if (labels[i] != 0 && drop[labels[i]]) skin[i] = false
    }
}
