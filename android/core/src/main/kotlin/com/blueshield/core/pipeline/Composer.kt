package com.blueshield.core.pipeline

import com.blueshield.core.image.ByteMask
import com.blueshield.core.image.MaskOps
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Per-frame analysis output. Boxes are normalised to 0..1. */
class FrameRecord(
    /** tid, x1, y1, x2, y2 per visible person. */
    val persons: FloatArray,
    /** owner tid (-1 = none), x1, y1, x2, y2, strength per tracked sensitive region. */
    val regions: FloatArray,
) {
    val personCount get() = persons.size / 5
    val regionCount get() = regions.size / 6

    companion object {
        val EMPTY = FrameRecord(FloatArray(0), FloatArray(0))
    }
}

/**
 * Builds the censor mask for a frame: only skin pixels / sensitive regions *owned by
 * people who are to be censored*. Same algorithm as the desktop `compose_mask`:
 *
 * every skin pixel belongs to the person whose padded box contains it; where boxes
 * overlap, or for pixels outside every box (an outstretched arm), the pixel adopts the
 * owner of the majority of its connected skin component, falling back to the nearest
 * box centre. Unattributable skin follows `censorUnassigned`.
 */
object Composer {
    /** A skin blob whose nearest edge is within this many box sizes of a person's box belongs to them (arms). */
    const val OWNER_REACH = 0.12f
    /** With people detected, an unattributed skin blob must cover at least this fraction of the frame. */
    const val UNASSIGNED_MIN_AREA_WITH_PEOPLE = 0.004f

    fun compose(
        skin: ByteMask, rec: FrameRecord, decisions: Map<Int, Boolean>, censorUnassigned: Boolean, boxPad: Float,
        unassignedMinArea: Float = 0.001f,
    ): ByteMask {
        val w = skin.width
        val h = skin.height
        var out = ByteMask(w, h)
        val n = rec.personCount
        val minArea = unassignedMinArea * w * h
        if (skin.any()) {
            if (n == 0) {
                if (censorUnassigned) out = ownedSkin(skin, rec, BooleanArray(0), true, boxPad, minArea)
            } else {
                val flags = BooleanArray(n) { decisions[rec.persons[it * 5].toInt()] ?: censorUnassigned }
                // with people in view, a lone unattributed blob must be bigger to count (objects near people)
                val orphanMin = max(minArea, UNASSIGNED_MIN_AREA_WITH_PEOPLE * w * h)
                if (flags.any { it } || censorUnassigned) out = ownedSkin(skin, rec, flags, censorUnassigned, boxPad, orphanMin)
            }
        }
        for (k in 0 until rec.regionCount) {
            val o = k * 6
            val owner = rec.regions[o].toInt()
            val allowed = if (owner >= 0) decisions[owner] ?: censorUnassigned else censorUnassigned
            if (!allowed) continue
            val x1 = rec.regions[o + 1] * w
            val y1 = rec.regions[o + 2] * h
            val x2 = rec.regions[o + 3] * w
            val y2 = rec.regions[o + 4] * h
            MaskOps.fillEllipse(out, (x1 + x2) / 2, (y1 + y2) / 2, (x2 - x1) * 0.65f, (y2 - y1) * 0.65f, (255 * rec.regions[o + 5]).roundToInt())
        }
        return out
    }

    private fun ownedSkin(skin: ByteMask, rec: FrameRecord, flags: BooleanArray, censorUnassigned: Boolean, pad: Float, minArea: Float): ByteMask {
        val w = skin.width
        val h = skin.height
        val n = rec.personCount
        val bestD = FloatArray(w * h) { Float.POSITIVE_INFINITY }
        val nearest = IntArray(w * h) // 1..n, 0 = outside every box
        val cover = IntArray(w * h)
        for (i in 0 until n) {
            val o = i * 5
            val x1 = rec.persons[o + 1]
            val y1 = rec.persons[o + 2]
            val x2 = rec.persons[o + 3]
            val y2 = rec.persons[o + 4]
            val bw = (x2 - x1) * w
            val bh = (y2 - y1) * h
            val px1 = max(0, (x1 * w - pad * bw).toInt())
            val py1 = max(0, (y1 * h - pad * bh).toInt())
            val px2 = min(w, (x2 * w + pad * bw + 1).toInt())
            val py2 = min(h, (y2 * h + pad * bh + 1).toInt())
            val cx = (x1 + x2) / 2 * w
            val cy = (y1 + y2) / 2 * h
            for (y in py1 until py2) for (x in px1 until px2) {
                val dx = (x - cx) / max(bw / 2, 1f)
                val dy = (y - cy) / max(bh / 2, 1f)
                val d = dx * dx + dy * dy
                val idx = y * w + x
                if (d < bestD[idx]) {
                    bestD[idx] = d
                    nearest[idx] = i + 1
                }
                cover[idx]++
            }
        }
        val on = BooleanArray(w * h) { skin.data[it].toInt() != 0 }
        val (comp, count) = MaskOps.connectedComponents(on, w, h)
        val compArea = IntArray(count)
        for (i in 0 until w * h) if (on[i]) compArea[comp[i]]++
        val votes = IntArray(count * (n + 1))
        for (i in 0 until w * h) if (on[i] && cover[i] == 1) votes[comp[i] * (n + 1) + nearest[i]]++
        val compOwner = IntArray(count) { c ->
            var best = 0
            var bestV = 0
            for (k in 1..n) if (votes[c * (n + 1) + k] > bestV) {
                bestV = votes[c * (n + 1) + k]
                best = k
            }
            best
        }
        // a blob entirely outside every box (a forearm stretched beyond the detector's box) belongs to the person
        // whose box its nearest edge almost touches — so it follows that person's decision. A blob separated by a
        // real gap (a mug on the desk) stays unattributed.
        val bx0 = IntArray(count) { Int.MAX_VALUE }
        val by0 = IntArray(count) { Int.MAX_VALUE }
        val bx1 = IntArray(count) { -1 }
        val by1 = IntArray(count) { -1 }
        for (i in 0 until w * h) if (on[i]) {
            val c = comp[i]
            val x = i % w
            val y = i / w
            if (x < bx0[c]) bx0[c] = x
            if (x > bx1[c]) bx1[c] = x
            if (y < by0[c]) by0[c] = y
            if (y > by1[c]) by1[c] = y
        }
        for (c in 1 until count) {
            if (compOwner[c] != 0 || compArea[c] == 0) continue
            var hasVote = false
            for (k in 1..n) if (votes[c * (n + 1) + k] > 0) hasVote = true
            if (hasVote) continue
            var bestR = OWNER_REACH
            for (k in 0 until n) {
                val o = k * 5
                val x1 = rec.persons[o + 1] * w
                val y1 = rec.persons[o + 2] * h
                val x2 = rec.persons[o + 3] * w
                val y2 = rec.persons[o + 4] * h
                val bw = max(x2 - x1, 1f)
                val bh = max(y2 - y1, 1f)
                // gap between the blob's bounding box and the person box, relative to the box size
                val gx = max(0f, max(x1 - bx1[c], bx0[c] - x2)) / bw
                val gy = max(0f, max(y1 - by1[c], by0[c] - y2)) / bh
                val r = max(gx, gy)
                if (r <= bestR) {
                    bestR = r
                    compOwner[c] = k + 1
                }
            }
        }
        val out = ByteMask(w, h)
        for (i in 0 until w * h) {
            if (!on[i]) continue
            var owner = if (cover[i] == 1) nearest[i] else compOwner[comp[i]]
            if (owner == 0 && cover[i] > 0) owner = nearest[i]
            // unattributed pixels only count if their whole blob is big enough to be part of a person
            val censor = if (owner == 0) censorUnassigned && compArea[comp[i]] >= minArea else flags[owner - 1]
            if (censor) out.data[i] = -1
        }
        return out
    }

    /**
     * Final alpha for rendering: grow the mask a little (so feathering never exposes
     * the original edge) and feather it. Sizes are relative to the frame diagonal,
     * exactly like the desktop `BlueCensor`.
     */
    fun feather(mask: ByteMask, frameW: Int, frameH: Int, softness: Int, aggressive: Boolean): ByteMask {
        if (!mask.any()) return mask
        val diag = hypot(frameW.toFloat(), frameH.toFloat())
        val toMask = mask.width.toFloat() / frameW
        val featherPx = softness / 100f * 0.012f * diag
        val grow = featherPx * 0.6f + (if (aggressive) 0.006f else 0.002f) * diag
        val bin = ByteMask(mask.width, mask.height, ByteArray(mask.data.size) { if ((mask.data[it].toInt() and 0xFF) > 96) -1 else 0 })
        val grown = MaskOps.dilate(bin, (grow * toMask).roundToInt())
        return MaskOps.gaussianApprox(grown, featherPx * toMask)
    }
}
