package com.blueshield.core

import com.blueshield.core.image.ByteMask
import com.blueshield.core.image.MaskOps
import com.blueshield.core.image.RgbImage
import com.blueshield.core.ml.ColorSkin
import com.blueshield.core.ml.ModelStore
import com.blueshield.core.ml.SkinSegmenter
import com.blueshield.core.pipeline.Analyzer
import com.blueshield.core.pipeline.Composer
import org.junit.Assume.assumeTrue
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test

/**
 * Opt-in quality measurement against a slow "oracle" (overlapping high-resolution tiles, every frame,
 * no temporal smoothing):
 *   gradle test --tests '*QualityCheck*' -Dblueshield.quality=in.mp4:outPrefix[:from:to[:settings-json]]
 * Prints recall (oracle skin that is censored), leak (censored pixels far from any oracle skin) and
 * flicker (frame-to-frame change of the censor mask), over frames [from, to). Writes outPrefix.mp4:
 * export | debug (raw skin probability, person boxes) | error map (green = missed, red = leak, blue = hit).
 */
class QualityCheck {
    @Test fun quality() {
        val arg = System.getProperty("blueshield.quality")
        assumeTrue("no quality job", !arg.isNullOrBlank())
        val parts = arg!!.split(":", limit = 5)
        val inFile = File(parts[0])
        val prefix = parts[1]
        val settings = (if (parts.size > 4) kotlinx.serialization.json.Json.decodeFromString(CensorSettings.serializer(), parts[4]) else CensorSettings()).validated()
        val repo = File(System.getProperty("blueshield.repo") ?: "../..")
        val models = ModelStore({ File(repo, "models/onnx/$it").readBytes() })
        // -Dblueshield.spec=file.json: measure a variant of shared/pipeline.json (ablations)
        val spec = System.getProperty("blueshield.spec")?.let { PipelineSpec.parse(File(it).readText()) } ?: PipelineSpec.bundled
        val (vw, vh, fps) = PipelineIntegrationTest.probe(inFile)
        val (aw, ah) = Analyzer.scaledSize(vw, vh, spec.analysisMaxSide)
        // -Dblueshield.nearest=true: sample frames exactly like the Android FrameExtractor (nearest pixel)
        var frames = if (System.getProperty("blueshield.nearest") == "true") {
            PipelineIntegrationTest.decode(inFile, vw, vh).map { full ->
                RgbImage(aw, ah).also { o ->
                    for (y in 0 until ah) for (x in 0 until aw) {
                        val sx = ((x + 0.5f) * vw / aw).toInt().coerceIn(0, vw - 1)
                        val sy = ((y + 0.5f) * vh / ah).toInt().coerceIn(0, vh - 1)
                        System.arraycopy(full.data, (sy * vw + sx) * 3, o.data, (y * aw + x) * 3, 3)
                    }
                }
            }
        } else PipelineIntegrationTest.decode(inFile, aw, ah)
        val from = parts.getOrNull(2)?.toIntOrNull() ?: 0
        val to = min(frames.size, parts.getOrNull(3)?.toIntOrNull() ?: frames.size)
        // only the measured range is analysed (keeps long clips quick)
        if (to < frames.size) frames = frames.subList(0, to)

        val oracle = oracle(models, frames, File("$prefix.oracle.bin"), spec)

        val analyzer = Analyzer(models, settings, spec, aw, ah, fps, aw, ah, File.createTempFile("masks", ".bin"))
        val probs = ArrayList<ByteArray>()
        val boxes = ArrayList<List<com.blueshield.core.image.Box>>()
        val t0 = System.nanoTime()
        for (c in frames.indices step analyzer.detStride * 4) {
            analyzer.process(frames.subList(c, min(frames.size, c + analyzer.detStride * 4))) {
                val p = analyzer.lastSkinProbability!!
                probs += ByteArray(aw * ah) { (p.data[it] * 255).toInt().coerceIn(0, 255).toByte() }
                boxes += analyzer.visiblePeople().map { it.box }
            }
        }
        val secs = (System.nanoTime() - t0) / 1e9
        val a = analyzer.finish()
        println("TIMINGS " + analyzer.timings.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${"%.1f".format(it.value / 1e9)}s" })
        val d = a.decisions(settings, emptyMap())
        var hit = 0L; var miss = 0L; var leak = 0L; var on = 0L; var flick = 0L; var union = 0L
        var prev: ByteMask? = null
        val rgb = CensorSettings.parseColor(settings.color)
        val p = ProcessBuilder("ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", "${aw * 3}x$ah", "-r", "$fps", "-i", "pipe:0",
            "-c:v", "libx264", "-crf", "18", "-pix_fmt", "yuv420p", "$prefix.mp4").redirectErrorStream(true).start()
        val out = p.outputStream.buffered(1 shl 20)
        var featherNs = 0L
        var composeNs = 0L
        for (i in 0 until a.frameCount) {
            val tc = System.nanoTime()
            val raw = a.maskFor(i, d, settings, a.lookahead())
            composeNs += System.nanoTime() - tc
            val f = frames[i]
            val o = oracle[i]
            val near = MaskOps.dilate(o, 4)
            if (i in from until to) {
                for (k in 0 until aw * ah) {
                    val m = (raw.data[k].toInt() and 0xFF) > 127
                    val g = o.data[k].toInt() != 0
                    if (m) on++
                    if (g && m) hit++
                    if (g && !m) miss++
                    if (m && near.data[k].toInt() == 0) leak++
                }
                prev?.let { pr ->
                    for (k in 0 until aw * ah) {
                        val a1 = (raw.data[k].toInt() and 0xFF) > 127
                        val a0 = (pr.data[k].toInt() and 0xFF) > 127
                        if (a1 != a0) flick++
                        if (a1 || a0) union++
                    }
                }
                prev = raw
            }
            val tf = System.nanoTime()
            val feathered = Composer.feather(raw, vw, vh, settings.softness, settings.aggressive).resize(aw, ah)
            featherNs += System.nanoTime() - tf
            val text = com.blueshield.core.image.TextGuard.mask(f) // what the GPU shader keeps visible
            val row = ByteArray(aw * 3 * ah * 3)
            for (y in 0 until ah) for (x in 0 until aw) {
                val k = y * aw + x
                val alpha = if (text[k]) 0f else (feathered.data[k].toInt() and 0xFF) / 255f
                val pr = (probs[i][k].toInt() and 0xFF) / 255f
                val m = (raw.data[k].toInt() and 0xFF) > 127
                val g = o.data[k].toInt() != 0
                for (ch in 0 until 3) {
                    val v = f.data[k * 3 + ch].toInt() and 0xFF
                    val c = (rgb shr (16 - 8 * ch)) and 0xFF
                    row[(y * aw * 3 + x) * 3 + ch] = (v + (c - v) * alpha).toInt().toByte()
                    val heat = if (ch == 0) 255 else 0
                    row[(y * aw * 3 + aw + x) * 3 + ch] = (v * 0.5f + (heat - v * 0.5f) * pr * 0.8f).toInt().coerceIn(0, 255).toByte()
                    val err = when {
                        g && m -> intArrayOf(40, 80, 255)
                        g -> intArrayOf(0, 230, 0)
                        m && near.data[k].toInt() == 0 -> intArrayOf(255, 0, 0)
                        m -> intArrayOf(40, 80, 255)
                        else -> null
                    }
                    row[(y * aw * 3 + 2 * aw + x) * 3 + ch] = (if (err != null) err[ch] else v / 3).toByte()
                }
            }
            // person boxes on the debug panel
            for (b in boxes[i]) {
                val x1 = b.x1.toInt().coerceIn(0, aw - 1); val x2 = b.x2.toInt().coerceIn(0, aw - 1)
                val y1 = b.y1.toInt().coerceIn(0, ah - 1); val y2 = b.y2.toInt().coerceIn(0, ah - 1)
                for (x in x1..x2) for (y in intArrayOf(y1, y2)) for (ch in 0 until 3) row[(y * aw * 3 + aw + x) * 3 + ch] = if (ch == 1) -1 else 0
                for (y in y1..y2) for (x in intArrayOf(x1, x2)) for (ch in 0 until 3) row[(y * aw * 3 + aw + x) * 3 + ch] = if (ch == 1) -1 else 0
            }
            out.write(row)
        }
        out.close()
        p.waitFor()
        println("RENDER compose=${"%.1f".format(composeNs / 1e9)}s feather=${"%.1f".format(featherNs / 1e9)}s")
        println("QUALITY ${inFile.name} frames $from..$to: recall ${"%.1f".format(100.0 * hit / max(1, hit + miss))} %, " +
            "leak ${"%.1f".format(100.0 * leak / max(1, on))} %, flicker ${"%.1f".format(100.0 * flick / max(1, union))} %, " +
            "analysis ${"%.1f".format(frames.size / secs)} fps; people " + a.summaries(settings, emptyMap()).joinToString {
                "#${it.id} ${it.gender} p=${"%.2f".format(it.pFemale)} v=${it.votes} age=${it.age?.let { a -> "%.0f".format(a) }} child=${it.child} ${it.frames}f censored=${it.censored}" } + " aliases=${a.aliases}")
        a.close()
        models.close()
    }

    /** Slow reference segmentation: overlapping square tiles (half the short side), max of body skin, colour-checked. */
    private fun oracle(models: ModelStore, frames: List<RgbImage>, cache: File, spec: PipelineSpec): List<ByteMask> {
        val w = frames[0].width
        val h = frames[0].height
        if (cache.exists() && cache.length() == frames.size.toLong() * w * h) {
            DataInputStream(cache.inputStream().buffered()).use { inp -> return frames.map { ByteMask(w, h, ByteArray(w * h).also { b -> inp.readFully(b) }) } }
        }
        val seg = SkinSegmenter(models, spec.thresholds.faceExclusion)
        val side = min(w, h) / 2
        val step = side / 2
        val out = frames.map { f ->
            val acc = FloatArray(w * h)
            var y0 = 0
            while (true) {
                var x0 = 0
                val yy = min(y0, h - side)
                while (true) {
                    val xx = min(x0, w - side)
                    val r = seg.segment(f.crop(xx, yy, side, side), false)
                    for (y in 0 until side) for (x in 0 until side) {
                        val k = (yy + y) * w + xx + x
                        acc[k] = max(acc[k], r.skin.data[y * side + x])
                    }
                    if (xx == w - side) break
                    x0 += step
                }
                if (yy == h - side) break
                y0 += step
            }
            val pl = ColorSkin.plausible(f, spec.thresholds.skinColor.maxBlueOverRed, spec.thresholds.skinColor.minLuma)
            ByteMask(w, h, ByteArray(w * h) { if (acc[it] * pl.data[it] > 0.5f) -1 else 0 })
        }
        DataOutputStream(cache.outputStream().buffered()).use { o -> out.forEach { o.write(it.data) } }
        return out
    }
}
