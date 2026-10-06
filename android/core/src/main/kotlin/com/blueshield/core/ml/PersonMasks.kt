package com.blueshield.core.ml

import ai.onnxruntime.OnnxTensor
import com.blueshield.core.image.Box
import com.blueshield.core.image.FloatMask
import com.blueshield.core.image.Resample
import com.blueshield.core.image.RgbImage
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One outline per person: MobileSAM (Apache-2.0) prompted with each person's box. Where people overlap
 * (a man standing behind a woman, a child in front of her mother) the outlines say whose arm a skin pixel
 * is — person boxes can't — and their sharp silhouette keeps the censor from spilling onto the background.
 *
 * [encode] runs the image encoder once per frame ([size] × [size] letterboxed input); [masks] then decodes
 * any number of boxes from that embedding (cheap). Masks are mask logits at the image size (> 0 = person).
 */
class PersonMasks(private val models: ModelStore, val size: Int) {
    class Embedding internal constructor(
        internal val emb: FloatArray, internal val pe: FloatArray, internal val gh: Int, internal val gw: Int,
        /** input pixels per image pixel */
        internal val scale: Float, val width: Int, val height: Int,
    )

    private val encoder = if (size >= 1024) ModelStore.SAM_ENCODER_1024 else ModelStore.SAM_ENCODER_512

    fun encode(img: RgbImage): Embedding {
        val s = size.toFloat() / max(img.width, img.height)
        val nw = max(1, (img.width * s).roundToInt())
        val nh = max(1, (img.height * s).roundToInt())
        val small = img.resize(nw, nh)
        val plane = size * size
        // letterboxed at the top-left; the padding is SAM's pixel mean (= 0 after its normalisation)
        val input = FloatArray(3 * plane)
        java.util.Arrays.fill(input, 0, plane, 123.675f)
        java.util.Arrays.fill(input, plane, 2 * plane, 116.28f)
        java.util.Arrays.fill(input, 2 * plane, 3 * plane, 103.53f)
        for (y in 0 until nh) for (x in 0 until nw) {
            val i = (y * nw + x) * 3
            val o = y * size + x
            input[o] = (small.data[i].toInt() and 0xFF).toFloat()
            input[plane + o] = (small.data[i + 1].toInt() and 0xFF).toFloat()
            input[2 * plane + o] = (small.data[i + 2].toInt() and 0xFF).toFloat()
        }
        val outs = models.session(encoder).runFloat(models.env, input, longArrayOf(1, 3, size.toLong(), size.toLong()))
        val (shape, emb) = outs[0]
        return Embedding(emb, outs[1].second, shape[2].toInt(), shape[3].toInt(), s, img.width, img.height)
    }

    /** Mask logits (image size) for each box (image coordinates), in the same order. */
    fun masks(e: Embedding, boxes: List<Box>): List<FloatMask> {
        if (boxes.isEmpty()) return emptyList()
        val sess = models.session(ModelStore.SAM_DECODER)
        val b = FloatArray(boxes.size * 4)
        for ((i, box) in boxes.withIndex()) {
            b[i * 4] = (box.x1 * e.scale / size).coerceIn(0f, 1f)
            b[i * 4 + 1] = (box.y1 * e.scale / size).coerceIn(0f, 1f)
            b[i * 4 + 2] = (box.x2 * e.scale / size).coerceIn(0f, 1f)
            b[i * 4 + 3] = (box.y2 * e.scale / size).coerceIn(0f, 1f)
        }
        val gridShape = longArrayOf(1, 256, e.gh.toLong(), e.gw.toLong())
        val env = models.env
        OnnxTensor.createTensor(env, FloatBuffer.wrap(e.emb), gridShape).use { emb ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(e.pe), gridShape).use { pe ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(b), longArrayOf(boxes.size.toLong(), 4)).use { bt ->
                    sess.run(mapOf("embeddings" to emb, "image_pe" to pe, "boxes" to bt)).use { res ->
                        val t = res.get("logits").get() as OnnxTensor
                        val shp = t.info.shape // n, 1, mh, mw
                        val mh = shp[2].toInt()
                        val mw = shp[3].toInt()
                        val all = FloatArray(boxes.size * mh * mw).also { t.floatBuffer.get(it) }
                        // low-res logits cover the whole size × size input; the image is its top-left part
                        val k = size.toFloat() / mw / e.scale // low-res cells per image pixel, inverted
                        return boxes.indices.map { n ->
                            val src = all.copyOfRange(n * mh * mw, (n + 1) * mh * mw)
                            val out = FloatMask(e.width, e.height)
                            Resample.bilinear(src, mw, mh, 1f / k, 1f / k, 0f, 0f, out.data, e.width, 0, 0, e.width, e.height, max = false)
                            out
                        }
                    }
                }
            }
        }
    }

    companion object {
        /**
         * Per-pixel owner from the people's mask logits: index into [logits] + 1, 0 = nobody clearly
         * ([minLogit]), and -1 = clearly outside every outline (all logits < [clipLogit]).
         */
        fun owners(logits: List<FloatMask?>, i: Int, minLogit: Float, clipLogit: Float): Int {
            var best = -1
            var bestV = Float.NEGATIVE_INFINITY
            var known = 0
            for ((k, m) in logits.withIndex()) {
                if (m == null) continue
                known++
                val v = m.data[i]
                if (v > bestV) { bestV = v; best = k }
            }
            if (known == 0) return 0
            if (bestV > minLogit) return best + 1
            return if (known == logits.size && bestV < clipLogit) -1 else 0
        }

        fun clampBox(b: Box, w: Int, h: Int) = Box(max(0f, b.x1), max(0f, b.y1), min(w.toFloat(), b.x2), min(h.toFloat(), b.y2))
    }
}
