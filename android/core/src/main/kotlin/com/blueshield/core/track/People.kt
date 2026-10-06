package com.blueshield.core.track

import com.blueshield.core.gender.GenderEstimate
import com.blueshield.core.image.Box
import com.blueshield.core.image.RgbImage
import com.blueshield.core.ml.PersonDetector.Companion.CONTAINED_MIN

/** A person with a stable identity across frames (stage 3: tracking). */
class PersonTrack(val id: Int, var box: Box, var score: Float, var appearance: FloatArray?, val gender: GenderEstimate) {
    var misses = 0
    var firstFrame = -1
    var lastFrame = -1
    var frames = 0
    var lostAt = -1
    var lastClassified = Int.MIN_VALUE / 2
    /** Best thumbnail so far (RGB crop) and its quality score. */
    var thumbnail: RgbImage? = null
    var thumbnailQuality = 0f
    /** Other track id -> consecutive detection rounds this track looked like a duplicate of it. */
    val dupRounds = HashMap<Int, Int>()
}

/**
 * IoU + appearance tracker with optical-flow prediction and a short-term gallery for
 * re-identification when a person re-enters the frame or after a scene cut.
 */
class PersonTracker(
    private val maxMisses: Int,
    private val galleryFrames: Int,
    private val reidMinSimilarity: Float,
    private val newEstimate: () -> GenderEstimate,
) {
    val active = ArrayList<PersonTrack>()
    private val gallery = ArrayList<PersonTrack>()
    val all = LinkedHashMap<Int, PersonTrack>()
    /** Merged-away track id -> surviving id (frames recorded before the merge still carry the old id). */
    val aliases = HashMap<Int, Int>()
    private var nextId = 1

    fun reset(frameIndex: Int) {
        for (t in active) {
            t.lostAt = frameIndex
            gallery += t
        }
        active.clear()
    }

    fun predict(flow: OpticalFlow, aw: Int, ah: Int) {
        for (t in active) {
            val (dx, dy) = flow.boxShift(t.box, aw, ah)
            t.box = t.box.shift(dx, dy)
        }
    }

    fun update(frame: RgbImage, dets: List<Detection>, frameIndex: Int) {
        val hists = dets.map { Appearance.torso(frame, it.box) }
        data class Pair3(val score: Float, val t: Int, val d: Int)
        val pairs = ArrayList<Pair3>()
        for ((ti, t) in active.withIndex()) for ((di, d) in dets.withIndex()) {
            val iou = t.box.iou(d.box)
            if (iou < 0.15f) continue
            pairs += Pair3(iou * 0.7f + 0.3f * Appearance.similarity(t.appearance, hists[di]), ti, di)
        }
        pairs.sortByDescending { it.score }
        val usedT = HashSet<Int>()
        val usedD = HashSet<Int>()
        for (p in pairs) {
            if (p.t in usedT || p.d in usedD) continue
            usedT += p.t
            usedD += p.d
            val t = active[p.t]
            val d = dets[p.d]
            t.box = t.box.lerp(d.box, 0.6f)
            t.score = d.score
            val h = hists[p.d]
            if (h != null) t.appearance = t.appearance?.let { a -> FloatArray(a.size) { 0.85f * a[it] + 0.15f * h[it] } } ?: h
            t.misses = 0
        }
        for ((ti, t) in active.withIndex()) if (ti !in usedT) t.misses++
        val lost = active.filter { it.misses > maxMisses }
        for (t in lost) {
            t.lostAt = frameIndex
            gallery += t
        }
        active.removeAll(lost.toSet())
        gallery.removeAll { frameIndex - it.lostAt > galleryFrames }
        for ((di, d) in dets.withIndex()) {
            if (di in usedD) continue
            val revived = gallery.maxByOrNull { Appearance.similarity(it.appearance, hists[di]) }
                ?.takeIf { Appearance.similarity(it.appearance, hists[di]) > reidMinSimilarity }
            if (revived != null) {
                gallery.remove(revived)
                revived.box = d.box
                revived.score = d.score
                revived.misses = 0
                active += revived
                continue
            }
            // a partial box of someone already tracked (e.g. their upper body) is not a new person
            if (active.any { it.misses == 0 && d.box.containedIn(it.box) > CONTAINED_MIN && Appearance.similarity(it.appearance, hists[di]) > 0.6f }) continue
            val t = PersonTrack(nextId++, d.box, d.score, hists[di], newEstimate())
            active += t
            all[t.id] = t
        }
        mergeDuplicates()
    }

    /**
     * Two tracks on the same person (one box inside the other, same look, for several detection
     * rounds) become one identity, so the gender evidence isn't split between them.
     */
    private fun mergeDuplicates() {
        val pending = ArrayList<Pair<PersonTrack, PersonTrack>>()
        for (small in active) for (big in active) {
            if (small === big || small.misses != 0 || big.misses != 0) continue
            if (small.box.area > big.box.area || (small.box.area == big.box.area && small.id >= big.id)) continue
            val same = small.box.containedIn(big.box) > CONTAINED_MIN && Appearance.similarity(small.appearance, big.appearance) > 0.6f
            val n = if (same) (small.dupRounds[big.id] ?: 0) + 1 else 0
            small.dupRounds[big.id] = n
            if (n >= 3) pending += small to big
        }
        for ((small, big) in pending) {
            if (small !in active || big !in active) continue
            val keepSmall = small.frames > big.frames || (small.frames == big.frames && small.id < big.id)
            val keep = if (keepSmall) small else big
            val drop = if (keepSmall) big else small
            keep.box = big.box
            keep.gender.absorb(drop.gender)
            active.remove(drop)
            all.remove(drop.id)
            aliases[drop.id] = keep.id
            for ((k, v) in aliases.entries.toList()) if (v == drop.id) aliases[k] = keep.id
        }
    }

    fun visible(): List<PersonTrack> = active.filter { it.misses <= maxMisses }

    fun markFrame(frame: RgbImage, frameIndex: Int) {
        for (t in visible()) {
            if (t.frames == 0) t.firstFrame = frameIndex
            t.frames++
            t.lastFrame = frameIndex
            if (t.misses == 0 && frameIndex % 6 == 0) {
                val q = t.box.area * t.score
                if (q > t.thumbnailQuality * 1.15f) {
                    val side = maxOf(t.box.w, minOf(t.box.h, t.box.w * 1.4f) * 0.8f)
                    val x0 = (t.box.cx - side / 2).toInt()
                    val y0 = (t.box.y1 - 0.04f * t.box.h).toInt()
                    if (side >= 8) {
                        t.thumbnail = frame.crop(x0, y0, side.toInt(), side.toInt()).resize(160, 160)
                        t.thumbnailQuality = q
                    }
                }
            }
        }
    }
}
