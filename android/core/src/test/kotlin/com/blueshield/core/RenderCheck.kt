package com.blueshield.core

import com.blueshield.core.image.ByteMask
import com.blueshield.core.image.RgbImage
import com.blueshield.core.ml.ModelStore
import com.blueshield.core.pipeline.Analyzer
import com.blueshield.core.pipeline.Composer
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test

/**
 * Opt-in visual check of the *final* render (what the phone exports), next to the live analysis preview:
 *   gradle test --tests '*RenderCheck*' -Dblueshield.render=/in.mp4:/out.mp4[:settings-json]
 * Left: analysis preview (fused skin, owner decision). Right: exported frame (compose + feather + blend).
 */
class RenderCheck {
    @Test fun render() {
        val arg = System.getProperty("blueshield.render")
        assumeTrue("no render job", !arg.isNullOrBlank())
        val parts = arg!!.split(":", limit = 3)
        val inFile = File(parts[0])
        val outFile = File(parts[1])
        val settings = (if (parts.size > 2) kotlinx.serialization.json.Json.decodeFromString(CensorSettings.serializer(), parts[2]) else CensorSettings()).validated()
        val repo = File(System.getProperty("blueshield.repo") ?: "../..")
        val models = ModelStore({ File(repo, "models/onnx/$it").readBytes() })
        val spec = PipelineSpec.bundled
        val (vw, vh, fps) = PipelineIntegrationTest.probe(inFile)
        val (aw, ah) = Analyzer.scaledSize(vw, vh, spec.analysisMaxSide)
        val (mw, mh) = Analyzer.scaledSize(vw, vh, spec.maskMaxSide)
        val analyzer = Analyzer(models, settings, spec, aw, ah, fps, mw, mh, File.createTempFile("masks", ".bin"))
        val frames = PipelineIntegrationTest.decode(inFile, aw, ah)
        val previews = ArrayList<ByteArray>()
        val t0 = System.nanoTime()
        for (c in frames.indices step analyzer.detStride * 4) {
            val chunk = frames.subList(c, minOf(frames.size, c + analyzer.detStride * 4))
            analyzer.process(chunk) { idx ->
                val skin = analyzer.lastSkinBinary!!
                val vis = analyzer.visiblePeople()
                val m = ByteArray(aw * ah)
                for (i in skin.indices) if (skin[i]) {
                    val x = (i % aw).toFloat(); val y = (i / aw).toFloat()
                    val owner = vis.filter { it.box.contains(x, y) }.minByOrNull { it.box.area }
                    if (owner?.let { analyzer.currentDecision(it) } ?: settings.censorUnassigned) m[i] = -1
                }
                previews += m
            }
        }
        val a = analyzer.finish()
        println("analysis ${a.frameCount} frames in ${"%.1f".format((System.nanoTime() - t0) / 1e9)} s; people " +
            a.summaries(settings, emptyMap()).joinToString { "#${it.id} ${it.gender} ${it.frames}f" })
        val d = a.decisions(settings, emptyMap())
        val rgb = CensorSettings.parseColor(settings.color)
        val p = ProcessBuilder("ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", "${aw * 2}x$ah", "-r", "$fps", "-i", "pipe:0",
            "-c:v", "libx264", "-crf", "18", "-pix_fmt", "yuv420p", outFile.path).redirectErrorStream(true).start()
        val out = p.outputStream.buffered(1 shl 20)
        var onPx = 0L
        var impossible = 0L
        for (i in 0 until a.frameCount) {
            val f = frames[i]
            val raw = a.maskFor(i, d, settings, a.lookahead())
            // pixels whose colour can't be skin (sky, blue/black clothes) = certain mistakes
            val pl = com.blueshield.core.ml.ColorSkin.plausible(f, 6, 28)
            val rawA = raw.resize(aw, ah)
            for (k in 0 until aw * ah) if ((rawA.data[k].toInt() and 0xFF) > 127) {
                onPx++
                if (pl.data[k] < 0.05f) impossible++
            }
            val feathered = Composer.feather(raw, vw, vh, settings.softness, settings.aggressive).resize(aw, ah)
            val row = ByteArray(aw * 2 * ah * 3)
            for (y in 0 until ah) for (x in 0 until aw) {
                val s = (y * aw + x) * 3
                val alphaL = if (previews[i][y * aw + x].toInt() != 0) 0.7f else 0f
                val alphaR = (feathered.data[y * aw + x].toInt() and 0xFF) / 255f
                for (ch in 0 until 3) {
                    val v = f.data[s + ch].toInt() and 0xFF
                    val c = (rgb shr (16 - 8 * ch)) and 0xFF
                    row[(y * aw * 2 + x) * 3 + ch] = (v + (c - v) * alphaL).toInt().toByte()
                    row[(y * aw * 2 + aw + x) * 3 + ch] = (v + (c - v) * alphaR).toInt().toByte()
                }
            }
            out.write(row)
        }
        out.close()
        p.waitFor()
        println("mask pixels $onPx, impossible-colour pixels $impossible (${"%.2f".format(100.0 * impossible / maxOf(1L, onPx))} %)")
        a.close()
        models.close()
    }
}
