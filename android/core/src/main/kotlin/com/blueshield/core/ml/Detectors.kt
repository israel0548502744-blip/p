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
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Skin segmentation (MediaPipe Selfie Multiclass): body skin, face skin and person probability maps. */
class SkinSegmenter(private val models: ModelStore, private val faceExclusion: Float = 0.35f) {
    /** Per-pixel probabilities: body skin, any person, face skin, clothes (what "the rest of her shirt" is). */
    class Result(val skin: FloatMask, val person: FloatMask, val face: FloatMask, val clothes: FloatMask = FloatMask(skin.width, skin.height))

    /** Model runs so far (profiling). */
    var runs = 0
        private set

    /** The model's input: the image letterboxed (edge-replicated) to [side] px, scaled to SIZE, -1..1; [left]/[top] = padding. */
    class Input(val data: FloatArray, val side: Int, val left: Int, val top: Int)

    /** Model output for one (letterboxed) input: SIZE × SIZE probability maps + where the input sits in them. */
    private class Raw(val skin: FloatArray, val person: FloatArray, val face: FloatArray, val clothes: FloatArray, val side: Int, val left: Int, val top: Int) {
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
            Resample.bilinear(clothes, SIZE, SIZE, k, k, offX, offY, out.clothes.data, w, x0, y0, x1, y1, max)
        }
    }

    fun segment(img: RgbImage, includeFace: Boolean, tiled: Boolean = false): Result {
        val out = Result(FloatMask(img.width, img.height), FloatMask(img.width, img.height), FloatMask(img.width, img.height), FloatMask(img.width, img.height))
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
    /**
     * What the close-up crops found on earlier frames, carried along with the motion: with [PipelineSpec.Roi.refresh]
     * "alternate" a person's whole-body crop and their detail tiles are refreshed on alternate frames, each kind
     * carried from the frame before in between (half the model runs; the max of both, as when both are fresh).
     */
    class RoiCarry {
        internal var whole: Result? = null
        internal var tiles: Result? = null
        internal val lastWhole = HashMap<Int, Int>()
        internal val lastTiles = HashMap<Int, Int>()

        fun warp(f: (FloatMask) -> FloatMask) {
            whole = whole?.let { Result(f(it.skin), f(it.person), f(it.face), f(it.clothes)) }
            tiles = tiles?.let { Result(f(it.skin), f(it.person), f(it.face), f(it.clothes)) }
        }

        fun reset() {
            whole = null
            tiles = null
            lastWhole.clear()
            lastTiles.clear()
        }
    }

    fun segmentRois(
        img: RgbImage, boxes: List<Box>, base: Result, includeFace: Boolean, roi: PipelineSpec.Roi, baseIsFresh: Boolean = false,
        /** Skin probability from which a blob of the (fresh) base outside every crop gets a close-up of its own (0 = never). */
        orphanSkin: Float = 0f,
        /** With [carry]: the people's track ids (parallel to [boxes]) and the frame number, for refreshing crops in turns. */
        ids: List<Int>? = null, carry: RoiCarry? = null, frameNo: Int = 0,
    ): Result {
        val w = img.width
        val h = img.height
        val roiOut = Result(FloatMask(w, h), FloatMask(w, h), FloatMask(w, h), FloatMask(w, h))
        val cover = BooleanArray(w * h)
        // crops first, then the model on all of them (side by side when there are several), then the pasting
        class Crop(val a: Int, val b: Int, val side: Int, val rx1: Int, val ry1: Int, val rx2: Int, val ry2: Int, val person: Int, val tile: Boolean) {
            var fresh = true
        }
        val crops = ArrayList<Crop>()
        for ((pi, box) in boxes.withIndex()) {
            val px = roi.pastePad * box.w
            val py = roi.pastePad * box.h
            for ((k, c) in roiCrops(box, roi.sideScale).withIndex()) {
                val (a, b, side) = c
                if (side < roi.minSidePx) continue
                // the whole-person crop would be the whole frame again: a fresh full pass already is exactly that
                if (k == 0 && baseIsFresh && side >= roi.maxFrameRatio * max(w, h)) continue
                val rx1 = max(max(0, (box.x1 - px).toInt()), a)
                val ry1 = max(max(0, (box.y1 - py).toInt()), b)
                val rx2 = min(min(w, (box.x2 + px).toInt() + 1), a + side)
                val ry2 = min(min(h, (box.y2 + py).toInt() + 1), b + side)
                if (rx2 <= rx1 || ry2 <= ry1) continue
                crops += Crop(a, b, side, rx1, ry1, rx2, ry2, pi, k > 0)
            }
        }
        val turns = carry != null && ids != null && roi.refresh == "alternate"
        if (turns) {
            carry!!
            for (pi in boxes.indices) {
                val id = ids!![pi]
                val mine = crops.filter { it.person == pi }
                if (mine.none { it.tile } || mine.none { !it.tile }) continue // one kind only: always fresh
                val lw = carry.lastWhole[id]
                val lt = carry.lastTiles[id]
                // new (or not seen in the last 2 frames): everything fresh; otherwise the kind whose turn it is
                if (lw == null || lt == null || frameNo - lw > 2 || frameNo - lt > 2 || carry.whole == null || carry.tiles == null) continue
                val tilesTurn = (frameNo + id) % 2 == 1
                for (c in mine) c.fresh = c.tile == tilesTurn
            }
        }
        // (side by side only on the plain CPU engine: an accelerator runs one input at a time anyway)
        val todo = crops.filter { it.fresh }
        val raws = if (todo.size < 2 || models.engineOf(ModelStore.SEGMENTER) != ModelStore.CPU) todo.map { run(img.crop(it.a, it.b, it.side, it.side), includeFace) }
        else {
            val single = models.singleThreaded(ModelStore.SEGMENTER)
            todo.map { c -> pool.submit<Raw> { run(img.crop(c.a, c.b, c.side, c.side), includeFace, single) } }.map { it.get() }
        }
        if (!turns) {
            for ((c, raw) in todo.zip(raws)) {
                raw.into(roiOut, c.a, c.b, c.rx1, c.ry1, c.rx2, c.ry2, max = true)
                for (y in c.ry1 until c.ry2) java.util.Arrays.fill(cover, y * w + c.rx1, y * w + c.rx2, true)
            }
        } else {
            carry!!
            // each kind on its own map: fresh crops pasted, the others' areas taken from the carried map
            val whole = Result(FloatMask(w, h), FloatMask(w, h), FloatMask(w, h), FloatMask(w, h))
            val tiles = Result(FloatMask(w, h), FloatMask(w, h), FloatMask(w, h), FloatMask(w, h))
            for ((c, raw) in todo.zip(raws)) raw.into(if (c.tile) tiles else whole, c.a, c.b, c.rx1, c.ry1, c.rx2, c.ry2, max = true)
            for (c in crops) {
                for (y in c.ry1 until c.ry2) java.util.Arrays.fill(cover, y * w + c.rx1, y * w + c.rx2, true)
                if (c.fresh) continue
                val from = (if (c.tile) carry.tiles else carry.whole)!!
                val to = if (c.tile) tiles else whole
                for (y in c.ry1 until c.ry2) for (x in c.rx1 until c.rx2) {
                    val i = y * w + x
                    to.skin.data[i] = max(to.skin.data[i], from.skin.data[i])
                    to.person.data[i] = max(to.person.data[i], from.person.data[i])
                    to.face.data[i] = max(to.face.data[i], from.face.data[i])
                    to.clothes.data[i] = max(to.clothes.data[i], from.clothes.data[i])
                }
            }
            for (i in 0 until w * h) if (cover[i]) {
                roiOut.skin.data[i] = max(whole.skin.data[i], tiles.skin.data[i])
                roiOut.person.data[i] = max(whole.person.data[i], tiles.person.data[i])
                roiOut.face.data[i] = max(whole.face.data[i], tiles.face.data[i])
                roiOut.clothes.data[i] = max(whole.clothes.data[i], tiles.clothes.data[i])
            }
            carry.whole = whole
            carry.tiles = tiles
            for (c in crops) if (c.fresh) {
                val id = ids!![c.person]
                if (c.tile) carry.lastTiles[id] = frameNo else carry.lastWhole[id] = frameNo
            }
        }
        if (crops.isEmpty()) return base
        if (orphanSkin > 0f && baseIsFresh) closeUpOrphans(img, base, roiOut, cover, orphanSkin, includeFace, roi)
        val skin = base.skin.copy()
        val person = base.person.copy()
        val face = base.face.copy()
        val clothes = base.clothes.copy()
        for (i in 0 until w * h) if (cover[i]) {
            skin.data[i] = roiOut.skin.data[i]
            person.data[i] = roiOut.person.data[i]
            face.data[i] = roiOut.face.data[i]
            clothes.data[i] = roiOut.clothes.data[i]
        }
        return Result(skin, person, face, clothes)
    }

    /**
     * Skin the whole-frame pass found away from every person's crop was seen at a few pixels per finger — and that is
     * where it calls a car's red paint or a wooden door skin. Each such blob (the [ORPHAN_MAX] largest of at least
     * [ORPHAN_MIN_AREA] of the frame) gets a close-up of its own, a square [ORPHAN_SCALE] times its size, whose result
     * replaces the coarse one around it ([out], marked in [cover]): real skin (a person the detector missed, an arm
     * reaching far out) stays skin, the false ones go.
     */
    private fun closeUpOrphans(img: RgbImage, base: Result, out: Result, cover: BooleanArray, thr: Float, includeFace: Boolean, roi: PipelineSpec.Roi) {
        val w = img.width
        val h = img.height
        val on = BooleanArray(w * h) { !cover[it] && base.skin.data[it] >= thr }
        val (labels, count) = com.blueshield.core.image.MaskOps.connectedComponents(on, w, h)
        if (count <= 1) return
        val area = IntArray(count)
        val bx0 = IntArray(count) { Int.MAX_VALUE }
        val by0 = IntArray(count) { Int.MAX_VALUE }
        val bx1 = IntArray(count) { -1 }
        val by1 = IntArray(count) { -1 }
        for (i in labels.indices) {
            val l = labels[i]
            if (l == 0) continue
            area[l]++
            val x = i % w
            val y = i / w
            if (x < bx0[l]) bx0[l] = x
            if (x > bx1[l]) bx1[l] = x
            if (y < by0[l]) by0[l] = y
            if (y > by1[l]) by1[l] = y
        }
        val minArea = ORPHAN_MIN_AREA * w * h
        val tmp = Result(FloatMask(w, h), FloatMask(w, h), FloatMask(w, h), FloatMask(w, h))
        for (l in (1 until count).filter { area[it] >= minArea }.sortedByDescending { area[it] }.take(ORPHAN_MAX)) {
            val bw = bx1[l] - bx0[l] + 1
            val bh = by1[l] - by0[l] + 1
            val side = max(roi.minSidePx, Math.round(max(bw, bh) * ORPHAN_SCALE))
            val a = Math.round((bx0[l] + bx1[l] + 1) / 2f - side / 2f)
            val b = Math.round((by0[l] + by1[l] + 1) / 2f - side / 2f)
            // replaced: the blob's box with a margin — never what a person's crop has already decided
            val m = max(2, max(bw, bh) / 4)
            val rx1 = max(0, bx0[l] - m)
            val ry1 = max(0, by0[l] - m)
            val rx2 = min(w, bx1[l] + m + 1)
            val ry2 = min(h, by1[l] + m + 1)
            run(img.crop(a, b, side, side), includeFace).into(tmp, a, b, rx1, ry1, rx2, ry2, max = false)
            for (y in ry1 until ry2) for (x in rx1 until rx2) {
                val i = y * w + x
                if (cover[i]) continue
                out.skin.data[i] = tmp.skin.data[i]
                out.person.data[i] = tmp.person.data[i]
                out.face.data[i] = tmp.face.data[i]
                out.clothes.data[i] = tmp.clothes.data[i]
                cover[i] = true
            }
        }
    }

    /** Runs the model on [img] letterboxed (edge-replicated) to a square, so portrait video keeps its proportions. */
    private fun run(img: RgbImage, includeFace: Boolean, session: ai.onnxruntime.OrtSession = models.session(ModelStore.SEGMENTER)): Raw {
        val x = input(img)
        synchronized(this) { runs++ }
        val out = session.runFloat(models.env, x.data, SHAPE).first().second
        val side = x.side
        val left = x.left
        val top = x.top
        val skin = FloatArray(SIZE * SIZE)
        val person = FloatArray(SIZE * SIZE)
        val face = FloatArray(SIZE * SIZE)
        val clothes = FloatArray(SIZE * SIZE)
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
            clothes[i] = p[4] / s
        }
        return Raw(skin, person, face, clothes, side, left, top)
    }

    companion object {
        const val SIZE = 256
        val SHAPE = longArrayOf(1, SIZE.toLong(), SIZE.toLong(), 3)

        /** The model's input for [img] (see [Input]). */
        fun input(img: RgbImage): Input {
            val side = max(img.width, img.height)
            val left = (side - img.width) / 2
            val top = (side - img.height) / 2
            val sq = if (side == img.width && side == img.height) img else img.crop(-left, -top, side, side)
            val x = sq.resize(SIZE, SIZE)
            return Input(FloatArray(SIZE * SIZE * 3) { (x.data[it].toInt() and 0xFF) / 127.5f - 1f }, side, left, top)
        }

        /** Output classes per pixel: background, hair, body skin, face skin, clothes, other. */
        const val CLASSES = 6

        /** Close-up crops run side by side on this many threads (one model thread each). */
        internal val pool: java.util.concurrent.ExecutorService by lazy {
            val n = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            java.util.concurrent.Executors.newFixedThreadPool(n) { r -> Thread(r, "blueshield-seg").apply { isDaemon = true } }
        }
        /** Close-ups of skin found away from every person ([closeUpOrphans]): at most this many per frame… */
        const val ORPHAN_MAX = 4
        /** …of blobs covering at least this fraction of the frame, each seen in a square this many times its size. */
        const val ORPHAN_MIN_AREA = 0.0005f
        const val ORPHAN_SCALE = 2f

        /** Extra crops along a tall (standing) or wide (lying / arms out) person. */
        const val MAX_TILES = 3
        /**
         * Tiles only for a person whose whole-body crop is shrunk at least 1.5x to the model's 256 px: a smaller
         * (farther) person is already seen at nearly full detail by the one crop, and tiles would cost 3 more
         * model runs each for nothing (a hall full of bystanders took minutes per second of video).
         */
        const val TILE_MIN_SIDE = 384

        /**
         * Square crops (left, top, side) that segment one person: the whole person, plus — for a tall or wide box —
         * up to [MAX_TILES] overlapping squares along the long side. A standing person's whole-body crop shrinks
         * them to 256 px; the tiles see torso and arms about twice as large (a belly-dance costume's bare midriff
         * was missed otherwise). Same as the desktop `roi_crops`.
         */
        fun roiCrops(box: Box, sideScale: Float): List<Triple<Int, Int, Int>> {
            val side = Math.round(max(box.w, box.h) * sideScale)
            val out = arrayListOf(Triple(Math.round(box.cx - side / 2f), Math.round(box.cy - side / 2f), side))
            val long = max(box.w, box.h)
            val short = min(box.w, box.h)
            if (short > 0f && long > 1.3f * short && side >= TILE_MIN_SIDE) {
                val n = min(MAX_TILES, max(2, ceil(long / (short * 1.25f)).toInt()))
                val t = Math.round(max(short * 1.25f, long / n * 1.2f))
                for (i in 0 until n) {
                    val f = (i + 0.5f) / n
                    val tx = if (box.h >= box.w) box.cx else box.x1 + f * box.w
                    val ty = if (box.h >= box.w) box.y1 + f * box.h else box.cy
                    out += Triple(Math.round(tx - t / 2f), Math.round(ty - t / 2f), t)
                }
            }
            return out
        }
    }
}

/**
 * Person boxes: YOLOX-tiny (Megvii, Apache-2.0), COCO class 0. Tighter boxes and many more people in crowds
 * than the previous EfficientDet-Lite0 (a woman and the man behind her get one box each, not one loose box
 * spanning both). Input 416 × 416 BGR 0..255, letterboxed top-left with 114 padding; raw outputs decoded on
 * the 8/16/32 grids.
 */
class PersonDetector(private val models: ModelStore) {
    fun detect(img: RgbImage, minScore: Float): List<Detection> {
        val (input, r) = input(img)
        val out = models.session(ModelStore.PERSONS).runFloat(models.env, input, SHAPE)[0].second
        return decode(out, r, img.width, img.height, minScore)
    }

    companion object {
        const val INPUT = 416
        val SHAPE = longArrayOf(1, 3, INPUT.toLong(), INPUT.toLong())
        private val grid: FloatArray by lazy { buildGrid() }

        /** The model's input for [img] (BGR 0..255, letterboxed top-left with 114) and the scale it was shrunk by. */
        fun input(img: RgbImage): Pair<FloatArray, Float> {
            val r = min(INPUT.toFloat() / img.width, INPUT.toFloat() / img.height)
            val nw = max(1, (img.width * r).toInt())
            val nh = max(1, (img.height * r).toInt())
            val x = img.resize(nw, nh)
            val plane = INPUT * INPUT
            val input = FloatArray(3 * plane) { 114f }
            for (y in 0 until nh) for (xx in 0 until nw) {
                val i = (y * nw + xx) * 3
                val o = y * INPUT + xx
                input[o] = (x.data[i + 2].toInt() and 0xFF).toFloat() // B
                input[plane + o] = (x.data[i + 1].toInt() and 0xFF).toFloat() // G
                input[2 * plane + o] = (x.data[i].toInt() and 0xFF).toFloat() // R
            }
            return input to r
        }

        /** Person boxes (image pixels) from the raw output for an image of [w] × [h] shrunk by [r]. */
        fun decode(out: FloatArray, r: Float, w: Int, h: Int, minScore: Float): List<Detection> {
            val stride = out.size / (grid.size / 3)
            val boxes = ArrayList<Box>()
            val sc = ArrayList<Float>()
            for (i in 0 until grid.size / 3) {
                val o = i * stride
                val s = out[o + 4] * out[o + 5] // objectness × person
                if (s < minScore) continue
                val st = grid[i * 3 + 2]
                val cx = (out[o] + grid[i * 3]) * st / r
                val cy = (out[o + 1] + grid[i * 3 + 1]) * st / r
                val bw = exp(out[o + 2]) * st / r
                val bh = exp(out[o + 3]) * st / r
                val b = Box(
                    (cx - bw / 2).coerceIn(0f, w.toFloat()), (cy - bh / 2).coerceIn(0f, h.toFloat()),
                    (cx + bw / 2).coerceIn(0f, w.toFloat()), (cy + bh / 2).coerceIn(0f, h.toFloat()),
                )
                if (b.w > 4 && b.h > 8) {
                    boxes += b
                    sc += s
                }
            }
            return suppressContained(nms(boxes, sc, 0.45f).map { Detection("person", sc[it], boxes[it]) })
        }

        /** (grid x, grid y, stride) for every output row: strides 8, 16, 32. */
        fun buildGrid(): FloatArray {
            val a = ArrayList<Float>()
            for (st in intArrayOf(8, 16, 32)) {
                val g = INPUT / st
                for (y in 0 until g) for (x in 0 until g) { a += x.toFloat(); a += y.toFloat(); a += st.toFloat() }
            }
            return a.toFloatArray()
        }

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
    }
}

/** BlazeFace (short range) face detector with eye keypoints. Coordinates in the input image's pixels. */
class FaceDetector(private val models: ModelStore) {
    class Face(val box: Box, val score: Float, val rightEye: Pair<Float, Float>, val leftEye: Pair<Float, Float>)

    fun detect(img: RgbImage, minScore: Float): List<Face> =
        decode(models.session(ModelStore.FACES).runFloat(models.env, input(img), SHAPE), img.width, img.height, minScore)

    companion object {
        const val INPUT = 128
        val SHAPE = longArrayOf(1, INPUT.toLong(), INPUT.toLong(), 3)

        private val anchors: FloatArray by lazy {
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

        /** The model's input for [img]: scaled to 128 × 128, -1..1. */
        fun input(img: RgbImage): FloatArray {
            val x = img.resize(INPUT, INPUT)
            return FloatArray(INPUT * INPUT * 3) { (x.data[it].toInt() and 0xFF) / 127.5f - 1f }
        }

        /** Faces (image pixels, for an input image of [width] × [height]) from the raw outputs. */
        fun decode(outs: List<Pair<LongArray, FloatArray>>, width: Int, height: Int, minScore: Float): List<Face> {
            val reg = outs.first { it.first.last() == 16L }.second
            val cls = outs.first { it.first.last() == 1L }.second
            val faces = ArrayList<Face>()
            val w = width.toFloat()
            val h = height.toFloat()
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
    }
}

/** Gender model (FaceRes MobileNet): eye-aligned 224×224 RGB face (0..255) -> P(male). */
/**
 * Second face model (face-api.js AgeGenderNet, TinyXception, trained on UTKFace — which includes children):
 * P(male) and an age estimate from the same eye-aligned 224 px face crop the FaceRes model sees.
 */
class AgeGenderModel(private val models: ModelStore) {
    class Result(val pMale: Float, val age: Float)

    fun predict(aligned224: RgbImage): Result = decode(models.session(ModelStore.AGE_GENDER).runFloat(models.env, input(aligned224), SHAPE))

    companion object {
        const val INPUT = 112
        const val TIGHT = 0.85f
        val SHAPE = longArrayOf(1, INPUT.toLong(), INPUT.toLong(), 3)

        /** The model's input: the aligned crop is 1.4× the face box; this model was trained on tighter face crops. */
        fun input(aligned224: RgbImage): FloatArray {
            val m = Math.round(aligned224.width * (1 - TIGHT) / 2)
            val face = aligned224.crop(m, m, aligned224.width - 2 * m, aligned224.height - 2 * m).resize(INPUT, INPUT)
            return FloatArray(INPUT * INPUT * 3) { (face.data[it].toInt() and 0xFF).toFloat() }
        }

        fun decode(outs: List<Pair<LongArray, FloatArray>>): Result {
            val age = outs.first { it.second.size == 1 }.second[0]
            val gender = outs.first { it.second.size == 2 }.second
            return Result(gender[0], age)
        }
    }
}

class GenderModel(private val models: ModelStore) {
    fun pMale(face: RgbImage): Float = models.session(ModelStore.GENDER).runFloat(models.env, input(face), SHAPE).first().second[0]

    fun align(img: RgbImage, face: FaceDetector.Face): RgbImage = alignFace(img, face)

    companion object {
        const val INPUT = 224
        val SHAPE = longArrayOf(1, INPUT.toLong(), INPUT.toLong(), 3)

        /** The model's input: the aligned 224 px face, RGB 0..255. */
        fun input(face: RgbImage): FloatArray {
            require(face.width == INPUT && face.height == INPUT)
            return FloatArray(INPUT * INPUT * 3) { (face.data[it].toInt() and 0xFF).toFloat() }
        }

        /** Rotate so the eyes are level, scale the face box (×1.4) to 224 px. Mirrors cv2.getRotationMatrix2D. */
        fun alignFace(img: RgbImage, face: FaceDetector.Face): RgbImage {
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
    }
}

/**
 * Every face in a frame: YuNet (OpenCV Zoo, MIT, 2023mar, 640 × 640). It sees faces of about 10 to 300 px, so
 * each frame goes through twice — scaled to fit the input, and at [SMALL] of that for close-up faces (a selfie).
 * Boxes come out in the face-box convention the pipeline was tuned on (the earlier detector's): a little wider than
 * YuNet's and starting below the hairline ([toPipeline]).
 */
class FullFaceDetector(private val models: ModelStore) {
    fun detect(frames: List<RgbImage>, minScore: Float): List<List<Detection>> = frames.map { f ->
        val all = ArrayList<Detection>()
        val fit = min(1f, INPUT.toFloat() / max(f.width, f.height))
        for (scale in floatArrayOf(fit, fit * SMALL)) {
            val (x, s) = input(f, scale)
            all += decode(models.session(ModelStore.FULL_FACES).runFloat(models.env, x, SHAPE), s, f.width, f.height, minScore)
        }
        nms(all.map { it.box }, all.map { it.score }, 0.3f).map { all[it] }
    }

    companion object {
        const val INPUT = 640
        /** Second pass: the frame at this share of the first pass's size (faces up to ~750 px of the first pass). */
        const val SMALL = 0.4f
        val SHAPE = longArrayOf(1, 3, INPUT.toLong(), INPUT.toLong())
        private val STRIDES = intArrayOf(8, 16, 32)

        /** The model input (BGR 0..255 planes, the frame scaled by [scale] at the top left, zeros elsewhere) and the scale used. */
        fun input(f: RgbImage, scale: Float): Pair<FloatArray, Float> {
            val w = max(1, min(INPUT, (f.width * scale).roundToInt()))
            val h = max(1, min(INPUT, (f.height * scale).roundToInt()))
            val r = f.resize(w, h)
            val plane = INPUT * INPUT
            val x = FloatArray(3 * plane)
            for (y in 0 until h) for (xx in 0 until w) {
                val i = y * w + xx
                val o = y * INPUT + xx
                x[o] = (r.data[i * 3 + 2].toInt() and 0xFF).toFloat()
                x[plane + o] = (r.data[i * 3 + 1].toInt() and 0xFF).toFloat()
                x[2 * plane + o] = (r.data[i * 3].toInt() and 0xFF).toFloat()
            }
            return x to w.toFloat() / f.width
        }

        /**
         * Faces (frame pixels, [toPipeline] convention) from the model's outputs (cls, obj, bbox, kps per stride, in
         * that order): score √(cls·obj), box centre (cell + offset)·stride, size e^(·)·stride.
         */
        fun decode(out: List<Pair<LongArray, FloatArray>>, scale: Float, width: Int, height: Int, minScore: Float): List<Detection> {
            val dets = ArrayList<Detection>()
            for ((k, st) in STRIDES.withIndex()) {
                val cls = out[k].second
                val obj = out[3 + k].second
                val bb = out[6 + k].second
                val cols = INPUT / st
                for (i in cls.indices) {
                    val sc = sqrt(cls[i].coerceIn(0f, 1f) * obj[i].coerceIn(0f, 1f))
                    if (sc < minScore) continue
                    val cx = (i % cols + bb[i * 4]) * st
                    val cy = (i / cols + bb[i * 4 + 1]) * st
                    val bw = exp(bb[i * 4 + 2]) * st
                    val bh = exp(bb[i * 4 + 3]) * st
                    val box = toPipeline((cx - bw / 2) / scale, (cy - bh / 2) / scale, bw / scale, bh / scale)
                    dets += Detection("FACE", sc, Box(max(0f, box.x1), max(0f, box.y1), min(width.toFloat(), box.x2), min(height.toFloat(), box.y2)))
                }
            }
            return dets
        }

        /** YuNet's box (x, y, w, h) in the pipeline's face-box convention: measured on the GT images, mean of 14 faces. */
        fun toPipeline(x: Float, y: Float, w: Float, h: Float) = Box(x - 0.08f * w, y + 0.13f * h, x + 1.08f * w, y + 1.02f * h)
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
