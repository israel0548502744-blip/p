package com.blueshield.core.pipeline

import com.blueshield.core.PipelineSpec
import com.blueshield.core.image.Box
import com.blueshield.core.image.MaskOps
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Face, neck and neckline rules on a frame's binary skin mask (same algorithm as the desktop `apply_neckline`).
 *
 * * the face itself is never censored (forehead to chin);
 * * nor is the throat: bare skin is censored from the end of the throat (a little over half a face below the
 *   chin) downwards — a low neckline, a V-neck. The chin is where the segmenter's facial skin ends ([faceChin]):
 *   face boxes end anywhere from the lower lip to well under the chin.
 *
 * All sizes are relative to the face box (NudeNet face detection, motion-tracked), so it works at any zoom.
 * (The throat used to be cleared deeper, 0.7 face heights, unless a low neckline was seen below it: that decision
 * flipped with a pixel's shift of the picture, and a narrow V-neck was never "seen" — its skin stayed uncovered.
 * Now both depths are the same; the neckline probe only feeds the sticky flag.)
 */
object Neckline {
    /** Inner part of the face ellipse that is always cleared; the rim only where the segmenter sees face skin. */
    private const val INNER = 0.75f
    private const val FACE_EDGE_PROB = 0.2f
    private const val JAW = 0.25f

    /** Half-width of the throat's own column, in face widths: it and the skin joined to it in the band are always cleared. */
    private const val THROAT_HALF = 0.25f

    /** Skin beside the throat band, at its height, that marks a region as a hand or an arm (share of a face's area). */
    private const val HAND_SIDE = 0.02f

    /** How far below the chin (face heights) skin beside the band still counts as a raised hand. */
    private const val HAND_ROWS = 0.2f

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
        val chin = if (faceProb != null) faceChin(faceProb, w, h, face) else face.y2
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
        val by0 = (chin - JAW * fh).toInt()
        val by1 = clearTo.toInt() + 1
        val hand = handInBand(skin, w, h, bx0, by0, bx1, by1, (chin + HAND_ROWS * fh).toInt(), fw * fh)
        val neck = hand?.let { neckInBand(skin, w, h, bx0, by0, bx1, by1, cx, THROAT_HALF * fw) }
        // a hand reaching into the band from the side keeps its fingers censored there (a hand held in front of the
        // face had its fingertips cut off) — unless that skin joins the throat inside the band: the sides of a neck
        fill(skin, w, h, bx0, by0, bx1, by1) { x, y -> hand == null || !hand[y * w + x] || neck!![y * w + x] }
        return seen
    }

    /**
     * Skin of regions that reach into the throat band (x0..x1, y0..y1) from the side: level with the chin (rows y0 until
     * [sideEnd]) at least [HAND_SIDE] of a face's area of them lies left or right of the band — a hand or an arm held
     * up by the face, not a neck (bare shoulders join the neck lower down). Null when there is none.
     */
    private fun handInBand(skin: BooleanArray, w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int, sideEnd: Int, faceArea: Float): BooleanArray? {
        val ya = max(0, y0)
        val yb = min(h, y1)
        if (yb <= ya) return null
        val (labels, count) = MaskOps.connectedComponents(skin, w, h)
        if (count <= 1) return null
        val inBand = BooleanArray(count)
        val side = IntArray(count)
        for (y in ya until yb) for (x in 0 until w) {
            val l = labels[y * w + x]
            if (l == 0) continue
            if (x >= x0 && x < x1) inBand[l] = true else if (y < sideEnd) side[l]++
        }
        val keep = BooleanArray(count) { it != 0 && inBand[it] && side[it] >= HAND_SIDE * faceArea }
        if (keep.none { it }) return null
        return BooleanArray(w * h) { keep[labels[it]] }
    }

    /**
     * Skin inside the band (x0..x1, y0..y1) connected, within the band, to the throat's own column (|x − cx| ≤ [half]):
     * the throat and the sides of the neck. A fingertip resting in the band over a phone is not.
     */
    private fun neckInBand(skin: BooleanArray, w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int, cx: Float, half: Float): BooleanArray {
        val out = BooleanArray(w * h)
        val xa = max(0, x0)
        val xb = min(w, x1)
        val ya = max(0, y0)
        val yb = min(h, y1)
        val bw = xb - xa
        val bh = yb - ya
        if (bw <= 0 || bh <= 0) return out
        val sub = BooleanArray(bw * bh) { skin[(ya + it / bw) * w + xa + it % bw] }
        val (labels, count) = MaskOps.connectedComponents(sub, bw, bh)
        val throat = BooleanArray(count)
        for (i in sub.indices) if (labels[i] != 0 && abs(xa + i % bw + 0.5f - cx) <= half) throat[labels[i]] = true
        for (i in sub.indices) {
            val x = xa + i % bw
            if (labels[i] != 0 && throat[labels[i]] || abs(x + 0.5f - cx) <= half) out[(ya + i / bw) * w + x] = true
        }
        return out
    }

    /**
     * The chin: the lowest row between the face centre and a little under the box that is mostly facial skin (the
     * segmenter's facial-skin class, central 40 % of the face width), kept within [CHIN_UP] face heights above and
     * [CHIN_DOWN] below the box's bottom edge; the box's edge where no row is.
     */
    fun faceChin(faceProb: FloatArray, w: Int, h: Int, face: Box): Float {
        val fh = face.h
        val x0 = max(0, (face.cx - 0.2f * face.w).toInt())
        val x1 = min(w, (face.cx + 0.2f * face.w).toInt() + 1)
        if (x1 <= x0) return face.y2
        val lo = face.y2 - CHIN_UP * fh
        val hi = face.y2 + CHIN_DOWN * fh
        var last = -1
        for (y in max(0, face.cy.toInt())..min(h - 1, hi.toInt())) {
            var on = 0
            for (x in x0 until x1) if (faceProb[y * w + x] >= 0.5f) on++
            if (on >= 0.5f * (x1 - x0)) last = y
        }
        if (last < 0) return face.y2
        return (last + 1f).coerceIn(lo, hi)
    }

    private const val CHIN_UP = 0.35f
    private const val CHIN_DOWN = 0.1f

    /**
     * The head of an upright person whose face no detector found (seen from behind, turned away, in the dark): the
     * hair or the back of the head lit warm reads as skin. A skin region lying mostly ([HEAD_SHARE]) inside the
     * head ellipse at the top of the box is that head and is cleared; an arm raised over the head continues
     * well outside it and stays.
     */
    fun clearHeads(skin: BooleanArray, w: Int, h: Int, boxes: List<Box>) {
        if (boxes.isEmpty()) return
        val (labels, count) = MaskOps.connectedComponents(skin, w, h)
        if (count <= 1) return
        val total = IntArray(count)
        for (l in labels) if (l != 0) total[l]++
        for (b in boxes) {
            val cx = b.cx
            val cy = b.y1 + HEAD_CY * b.h
            val ax = min(0.5f * b.w, HEAD_RX * b.h)
            val ay = HEAD_RY * b.h
            if (ax < 2f || ay < 2f) continue
            val x0 = max(0, (cx - ax).toInt()); val x1 = min(w, (cx + ax).toInt() + 1)
            val y0 = max(0, (cy - ay).toInt()); val y1 = min(h, (cy + ay).toInt() + 1)
            val inside = HashMap<Int, Int>()
            for (y in y0 until y1) for (x in x0 until x1) {
                val l = labels[y * w + x]
                if (l == 0) continue
                val dx = (x + 0.5f - cx) / ax
                val dy = (y + 0.5f - cy) / ay
                if (dx * dx + dy * dy <= 1f) inside[l] = (inside[l] ?: 0) + 1
            }
            // head-shaped only: centred in the ellipse and not elongated (a hand raised beside the head, or holding
            // something up, is off-centre or long, and stays covered)
            val heads = inside.filter { (l, n) -> n >= HEAD_SHARE * total[l] }.keys.filter { l ->
                var sx = 0f; var sy = 0f; var x0b = w; var x1b = 0; var y0b = h; var y1b = 0; var c = 0
                for (y in y0 until y1) for (x in x0 until x1) if (labels[y * w + x] == l) {
                    sx += x; sy += y; c++
                    if (x < x0b) x0b = x; if (x > x1b) x1b = x; if (y < y0b) y0b = y; if (y > y1b) y1b = y
                }
                if (c == 0) return@filter false
                val mx = (sx / c + 0.5f - cx) / ax
                val my = (sy / c + 0.5f - cy) / ay
                val bw = (x1b - x0b + 1).toFloat()
                val bh = (y1b - y0b + 1).toFloat()
                mx * mx + my * my <= 0.25f && bh <= 1.6f * bw && bw <= 1.6f * bh && c >= 0.25f * (Math.PI * ax * ay).toFloat()
            }.toSet()
            if (heads.isEmpty()) continue
            for (i in skin.indices) if (labels[i] in heads) skin[i] = false
        }
    }

    /** Head ellipse of an upright person box: centre and radii as fractions of the box height. */
    private const val HEAD_CY = 0.075f
    private const val HEAD_RX = 0.06f
    private const val HEAD_RY = 0.08f
    private const val HEAD_SHARE = 0.7f

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
