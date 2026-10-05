package com.blueshield.core

import com.blueshield.core.image.RgbImage
import com.blueshield.core.ml.ModelStore
import com.blueshield.core.pipeline.Analyzer
import com.blueshield.core.pipeline.Composer
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opt-in soak test on long / high-resolution videos (streamed frame by frame like on the phone):
 *   gradle test --tests '*LongVideoCheck*' -Dblueshield.longVideos=/path/a.mp4,/path/b.mp4
 */
class LongVideoCheck {
    @Test fun streamLongVideos() {
        val list = System.getProperty("blueshield.longVideos")?.split(",")?.filter { it.isNotBlank() }
        assumeTrue("no long videos given", !list.isNullOrEmpty())
        val repo = File(System.getProperty("blueshield.repo") ?: "../..")
        val models = ModelStore({ File(repo, "models/onnx/$it").readBytes() })
        val spec = PipelineSpec.bundled
        for (path in list!!) {
            val file = File(path)
            val (vw, vh, fps) = PipelineIntegrationTest.probe(file)
            val (aw, ah) = Analyzer.scaledSize(vw, vh, spec.analysisMaxSide)
            val (mw, mh) = Analyzer.scaledSize(vw, vh, spec.maskMaxSide)
            val store = File.createTempFile("masks", ".bin")
            val analyzer = Analyzer(models, CensorSettings(), spec, aw, ah, fps, mw, mh, store)
            // stream frames from ffmpeg in small chunks — never the whole video in memory
            val p = ProcessBuilder("ffmpeg", "-v", "error", "-i", file.path, "-vf", "scale=$aw:$ah:flags=area", "-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1").start()
            val input = p.inputStream.buffered(1 shl 20)
            val frameBytes = aw * ah * 3
            val chunk = ArrayList<RgbImage>()
            val rt = Runtime.getRuntime()
            var peakMb = 0L
            val t0 = System.nanoTime()
            while (true) {
                val buf = input.readNBytes(frameBytes)
                if (buf.size < frameBytes) break
                chunk += RgbImage(aw, ah, buf)
                if (chunk.size >= analyzer.detStride * 4) {
                    analyzer.process(chunk); chunk.clear()
                    peakMb = maxOf(peakMb, (rt.totalMemory() - rt.freeMemory()) / (1 shl 20))
                }
            }
            if (chunk.isNotEmpty()) analyzer.process(chunk)
            p.waitFor()
            val a = analyzer.finish()
            val secs = (System.nanoTime() - t0) / 1e9
            val s = CensorSettings()
            val d = a.decisions(s, emptyMap())
            var censored = 0
            for (i in 0 until a.frameCount) if (Composer.feather(a.maskFor(i, d, s, a.lookahead()), vw, vh, s.softness, false).any()) censored++
            println("${file.name}: ${vw}x$vh @ ${"%.1f".format(fps)} fps, ${a.frameCount} frames analysed at ${aw}x$ah in ${"%.1f".format(secs)} s " +
                "(${"%.1f".format(a.frameCount / secs)} fps), heap peak ≈ $peakMb MB, mask file ${store.length() / 1024} KB, censored frames $censored")
            a.summaries(s, emptyMap()).filter { it.frames > a.frameCount / 10 }.forEach {
                println("   #${it.id} ${it.gender} pFemale=${"%.3f".format(it.pFemale)} votes=${it.votes} frames=${it.frames} censored=${it.censored}")
            }
            assertTrue(a.frameCount > 100)
            a.close()
        }
        models.close()
    }
}
