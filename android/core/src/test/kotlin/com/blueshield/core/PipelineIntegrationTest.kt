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
            val p = ProcessBuilder("ffmpeg", "-v", "error", "-i", file.path, "-vf", "scale=$w:$h:flags=area", "-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1")
                .redirectError(ProcessBuilder.Redirect.INHERIT).start()
            val bytes = p.inputStream.readBytes()
            p.waitFor()
            val n = bytes.size / (w * h * 3)
            return (0 until n).map { RgbImage(w, h, bytes.copyOfRange(it * w * h * 3, (it + 1) * w * h * 3)) }
        }

        fun probe(file: File): Triple<Int, Int, Double> {
            val p = ProcessBuilder("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height,r_frame_rate", "-of", "csv=p=0", file.path).start()
            val (w, h, r) = p.inputStream.bufferedReader().readText().trim().split(",")
            val (a, b) = r.split("/").map { it.toDouble() }
            return Triple(w.toInt(), h.toInt(), a / b)
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
            for (y in 0 until m.height) for (x in 0 until m.width) if (m[x, y] > 128) { if (x < m.width / 2) left++ else right++ }
        }
        println("masked pixels left=$left right=$right")
        assertTrue(left > 5000)
        assertTrue(right < left * 0.02)
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
