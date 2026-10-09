package com.blueshield.core.ml

import com.blueshield.core.image.FloatMask
import com.blueshield.core.image.Resample
import com.blueshield.core.image.RgbImage
import kotlin.math.max
import kotlin.math.min

/**
 * Skin / clothes / hair segmentation (Kazuhito Takahashi's DeepLabV3+ on MobileNetV3-small, MIT): a second
 * opinion on what is clothing. The selfie model now and then calls a shirt, a belt or a purple-lit dress
 * "body skin"; this model, trained on exactly skin vs. clothes, vetoes those pixels (see the Analyzer's
 * clothes veto, `clothes_veto` in shared/pipeline.json).
 *
 * It sees the same square per-person crops as [SkinSegmenter.segmentRois], each at [SIZE] × [SIZE]
 * (RGB / 255, ImageNet mean and std, NCHW; outputs are already probabilities: skin, clothes, hair).
 */
class ClothesSegmenter(private val models: ModelStore) {
    /** Frame-sized clothes probability, 0 outside every crop (max where crops overlap); [covered]: inside some crop. */
    class Result(val clothes: FloatMask, val covered: BooleanArray)

    /** Model runs so far (profiling). */
    var runs = 0
        private set

    /** Clothes probability over [img] from square crops (left, top, side); out-of-frame parts are edge-replicated. */
    fun segment(img: RgbImage, crops: List<Triple<Int, Int, Int>>): Result {
        val w = img.width
        val h = img.height
        val out = Result(FloatMask(w, h), BooleanArray(w * h))
        if (crops.isEmpty()) return out
        // side by side on single-threaded sessions on the plain CPU engine (as the skin crops); one at a time otherwise
        val outs = if (crops.size < 2 || models.engineOf(ModelStore.CLOTHES) != ModelStore.CPU) crops.map { run(img, it) }
        else {
            val single = models.singleThreaded(ModelStore.CLOTHES)
            crops.map { c -> SkinSegmenter.pool.submit<FloatArray> { run(img, c, single) } }.map { it.get() }
        }
        val plane = SIZE * SIZE
        for ((c, o) in crops.zip(outs)) {
            val (a, b, side) = c
            val x0 = max(0, a)
            val y0 = max(0, b)
            val x1 = min(w, a + side)
            val y1 = min(h, b + side)
            if (x1 <= x0 || y1 <= y0) continue
            val k = SIZE.toFloat() / side
            // destination pixel X ↔ crop pixel X − a ↔ model position ((X − a + 0.5) k − 0.5)
            Resample.bilinear(o.copyOfRange(plane, 2 * plane), SIZE, SIZE, k, k, -a.toFloat(), -b.toFloat(), out.clothes.data, w, x0, y0, x1, y1, max = true)
            for (y in y0 until y1) java.util.Arrays.fill(out.covered, y * w + x0, y * w + x1, true)
        }
        return out
    }

    /** The model on one square crop: [3, SIZE, SIZE] probabilities (skin, clothes, hair; only clothes is used). */
    private fun run(img: RgbImage, c: Triple<Int, Int, Int>, session: ai.onnxruntime.OrtSession = models.session(ModelStore.CLOTHES)): FloatArray {
        val (a, b, side) = c
        val x = img.crop(a, b, side, side).resize(SIZE, SIZE)
        synchronized(this) { runs++ }
        val plane = SIZE * SIZE
        val input = FloatArray(3 * plane)
        for (i in 0 until plane) for (ch in 0 until 3) {
            input[ch * plane + i] = ((x.data[i * 3 + ch].toInt() and 0xFF) / 255f - MEAN[ch]) / STD[ch]
        }
        return session.runFloat(models.env, input, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).first().second
    }

    companion object {
        const val SIZE = 512
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        /**
         * The clothes veto, in place on [skin]: 0 where the clothes probability [clothes] is at least [clothesMin] and
         * the selfie model's own skin probability [selfie] is below [skinMax] (a very sure selfie model wins).
         */
        fun veto(skin: FloatArray, selfie: FloatArray, clothes: FloatArray, clothesMin: Float, skinMax: Float) {
            for (i in skin.indices) if (clothes[i] >= clothesMin && selfie[i] < skinMax) skin[i] = 0f
        }
    }
}
