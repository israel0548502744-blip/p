package com.blueshield.core

import com.blueshield.core.gender.GenderEstimate
import com.blueshield.core.gender.Override
import com.blueshield.core.image.RgbImage
import com.blueshield.core.ml.ModelStore
import com.blueshield.core.pipeline.Analysis
import com.blueshield.core.pipeline.Analyzer
import com.blueshield.core.pipeline.Composer
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the full Kotlin pipeline (the code the Android app uses) with the real ONNX
 * models on the committed fixture clips. Frames are decoded with the ffmpeg CLI
 * (skipped if ffmpeg is unavailable); on the phone they come from MediaCodec.
 */
class PipelineIntegrationTest {
    companion object {
        private val repo = File(System.getProperty("blueshield.repo") ?: "../..")
        private lateinit var models: ModelStore
        private var womanAndMan: Analysis? = null
        private var wmFrames: List<RgbImage> = emptyList()

        private fun ffmpegOk() = runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false)

        fun decode(file: File, w: Int, h: Int): List<RgbImage> {
            // the frames the file really holds (no duplicates for variable-frame-rate phone videos), upright
            val p = ProcessBuilder("ffmpeg", "-v", "error", "-i", file.path, "-fps_mode", "passthrough", "-vf", "scale=$w:$h:flags=area",
                "-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1")
                .redirectError(ProcessBuilder.Redirect.INHERIT).start()
            val bytes = p.inputStream.readBytes()
            p.waitFor()
            val n = bytes.size / (w * h * 3)
            return (0 until n).map { RgbImage(w, h, bytes.copyOfRange(it * w * h * 3, (it + 1) * w * h * 3)) }
        }

        /** Display size (rotation applied, as ffmpeg decodes) and the real average frame rate. */
        fun probe(file: File): Triple<Int, Int, Double> {
            val p = ProcessBuilder("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                "stream=width,height,r_frame_rate,nb_frames,duration:stream_side_data=rotation", "-of", "json", file.path).start()
            val js = kotlinx.serialization.json.Json.parseToJsonElement(p.inputStream.bufferedReader().readText())
            val st = (js as kotlinx.serialization.json.JsonObject)["streams"]!!.let { it as kotlinx.serialization.json.JsonArray }[0] as kotlinx.serialization.json.JsonObject
            fun str(k: String) = (st[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
            var w = str("width")!!.toInt()
            var h = str("height")!!.toInt()
            val rot = (st["side_data_list"] as? kotlinx.serialization.json.JsonArray)?.firstNotNullOfOrNull {
                ((it as kotlinx.serialization.json.JsonObject)["rotation"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
            } ?: 0.0
            if (Math.abs(rot.toInt()) % 180 == 90) { val t = w; w = h; h = t }
            val (a, b) = str("r_frame_rate")!!.split("/").map { it.toDouble() }
            var fps = a / b
            val frames = str("nb_frames")?.toDoubleOrNull() ?: 0.0
            val dur = str("duration")?.toDoubleOrNull() ?: 0.0
            if (frames > 0 && dur > 0) {
                val measured = frames / dur
                if (fps > measured * 1.25 || fps < measured * 0.8) fps = measured
            }
            return Triple(w, h, fps)
        }

        fun analyze(file: File, settings: CensorSettings = CensorSettings()): Pair<Analysis, List<RgbImage>> {
            val (vw, vh, fps) = probe(file)
            val (aw, ah) = Analyzer.scaledSize(vw, vh, PipelineSpec.bundled.analysisMaxSide)
            val (mw, mh) = Analyzer.scaledSize(vw, vh, PipelineSpec.bundled.maskMaxSide)
            val frames = decode(file, aw, ah)
            val analyzer = Analyzer(models, settings, PipelineSpec.bundled, aw, ah, fps, mw, mh, File.createTempFile("masks", ".bin"))
            val t0 = System.nanoTime()
            frames.chunked(analyzer.detStride * 4).forEach { analyzer.process(it) }
            val sec = (System.nanoTime() - t0) / 1e9
            println("analysed ${frames.size} frames of ${file.name} at ${aw}x$ah in ${"%.1f".format(sec)} s (${"%.1f".format(frames.size / sec)} fps)")
            return analyzer.finish() to frames
        }

        @BeforeClass @JvmStatic fun setup() {
            assumeTrue("ffmpeg not available", ffmpegOk())
            val dir = File(repo, "models/onnx")
            models = ModelStore({ File(dir, it).readBytes() })
            val (a, f) = analyze(File(repo, "tests/fixtures/woman_and_man.mp4"))
            womanAndMan = a
            wmFrames = f
        }

        @AfterClass @JvmStatic fun teardown() {
            womanAndMan?.close()
            if (::models.isInitialized) models.close()
        }
    }

    private fun bySide(a: Analysis) = a.people.values.filter { it.frames > a.frameCount / 2 }
        .associateBy { if (it.box.cx / wmFrames[0].width < 0.5f) "left" else "right" }

    @Test fun womanIsFemaleManIsMale() {
        val a = womanAndMan!!
        val sides = bySide(a)
        assertEquals(setOf("left", "right"), sides.keys)
        sides.values.forEach { println("person #${it.id}: pFemale=${"%.3f".format(it.gender.pFemale)} votes=${it.gender.votes}") }
        assertEquals(GenderEstimate.Label.FEMALE, sides.getValue("left").gender.label(0.7))
        assertEquals(GenderEstimate.Label.MALE, sides.getValue("right").gender.label(0.7))
    }

    @Test fun onlyTheWomanIsMasked() {
        val a = womanAndMan!!
        val s = CensorSettings()
        val d = a.decisions(s, emptyMap())
        var left = 0
        var right = 0
        for (i in 0 until a.frameCount) {
            val m = Composer.feather(a.maskFor(i, d, s, a.lookahead()), 768, 432, s.softness, false)
            // the man stands right of x ≈ 0.6 (the woman's arm may reach a little past the middle)
            for (y in 0 until m.height) for (x in 0 until m.width) if (m[x, y] > 128) { if (x < m.width / 2) left++ else if (x > m.width * 0.62) right++ }
        }
        println("masked pixels left=$left right=$right")
        assertTrue(left > 5000)
        assertTrue(right < left * 0.01)
    }

    @Test fun stillImageCensorsTheWomanOnly() {
        val img = wmFrames[30]
        val a = com.blueshield.core.pipeline.StillImage.analyze(models, CensorSettings(), PipelineSpec.bundled, img, img.width, img.height,
            File.createTempFile("still", ".bin"))
        val s = CensorSettings()
        val sides = a.people.values.associateBy { if (it.box.cx / img.width < 0.5f) "left" else "right" }
        println("still: " + a.summaries(s, emptyMap()).joinToString { "#${it.id} ${it.gender} p=${"%.2f".format(it.pFemale)} v=${it.votes}" })
        val d = a.decisions(s, emptyMap())
        assertEquals(true, d[sides.getValue("left").id])
        assertEquals(false, d[sides.getValue("right").id])
        val alpha = com.blueshield.core.pipeline.StillImage.alpha(a, s, d, img.width, img.height)!!
        var left = 0; var right = 0
        for (y in 0 until img.height) for (x in 0 until img.width) if (alpha[x, y] > 128) { if (x < img.width / 2) left++ else if (x > img.width * 0.62) right++ }
        assertTrue(left > 1000 && right < left * 0.01, "left=$left right=$right")
        a.close()
    }

    @Test fun overrideInvertsDecisions() {
        val a = womanAndMan!!
        val sides = bySide(a)
        val d = a.decisions(CensorSettings(), mapOf(sides.getValue("left").id to Override.KEEP, sides.getValue("right").id to Override.CENSOR))
        assertEquals(false, d[sides.getValue("left").id])
        assertEquals(true, d[sides.getValue("right").id])
        assertTrue(a.decisions(CensorSettings(target = "everyone"), emptyMap()).values.all { it })
    }

    @Test fun menOnlyClipIsNotCensored() {
        val (a, _) = analyze(File(repo, "tests/fixtures/men_classroom.mp4"))
        a.use {
            val s = CensorSettings()
            val d = it.decisions(s, emptyMap())
            it.summaries(s, emptyMap()).forEach { p -> println("men clip: #${p.id} ${p.gender} pFemale=${"%.3f".format(p.pFemale)} votes=${p.votes}") }
            assertTrue(it.people.values.count { p -> p.frames > it.frameCount / 2 } >= 3)
            val censoredFrames = (0 until it.frameCount).count { i -> it.maskFor(i, d, s, it.lookahead()).any() }
            assertTrue(censoredFrames <= it.frameCount / 10, "censored frames: $censoredFrames / ${it.frameCount}")
        }
    }
}
