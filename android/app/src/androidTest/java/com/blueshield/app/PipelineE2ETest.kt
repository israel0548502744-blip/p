package com.blueshield.app

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blueshield.app.engine.JobState
import com.blueshield.app.engine.Processor
import com.blueshield.app.engine.VideoMeta
import com.blueshield.app.service.ProcessingRepository
import com.blueshield.app.service.ProcessingService
import com.blueshield.core.CensorSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the real pipeline (MediaCodec decode → ONNX analysis → OpenGL render → H.264 encode → MP4)
 * on an Android runtime, with the committed woman + man clip. Run by CI on an emulator.
 */
@RunWith(AndroidJUnit4::class)
class PipelineE2ETest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext

    private fun fixture(name: String): Uri {
        val f = File(ctx.cacheDir, name)
        inst.context.assets.open(name).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        return Uri.fromFile(f)
    }

    private fun checkOutput(file: File?, w: Int, h: Int, seconds: Double, audio: Boolean = true) {
        assertTrue("output missing", file != null && file.exists() && file.length() > (if (audio) 20_000 else 1_000))
        val r = MediaMetadataRetriever()
        r.setDataSource(file!!.path)
        assertEquals(w, r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt())
        assertEquals(h, r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt())
        val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() / 1000.0
        assertTrue("duration $dur vs $seconds", Math.abs(dur - seconds) < 0.6)
        if (audio) assertTrue("audio track missing", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes")
        r.release()
    }

    @Test fun processorEndToEnd() {
        val meta = VideoMeta.probe(ctx, fixture("woman_and_man.mp4"))
        assertEquals(768, meta.width); assertEquals(432, meta.height)
        val p = Processor(ctx)
        val result = p.run(meta, CensorSettings(speed = "fast")) { }
        assertEquals("error: ${result.error}", JobState.Stage.COMPLETE, result.stage)
        checkOutput(result.output, 768, 432, meta.durationSec)
        val people = p.analysis!!.summaries(CensorSettings(), emptyMap()).filter { it.frames > p.analysis!!.frameCount / 2 }
        assertEquals("people: $people", setOf("female", "male"), people.map { it.gender }.toSet())
        p.close()
    }

    /** A tiny portrait phone clip (200×112 coded, rotated, 17 fps, no audio): used to crash in MediaCodec.start(). */
    @Test fun tinyVideoEndToEnd() {
        val meta = VideoMeta.probe(ctx, fixture("tiny_portrait.mp4"))
        assertEquals(112, meta.width); assertEquals(200, meta.height)
        val p = Processor(ctx)
        val result = p.run(meta, CensorSettings(speed = "fast")) { }
        assertEquals("error: ${result.error}", JobState.Stage.COMPLETE, result.stage)
        val r = MediaMetadataRetriever()
        r.setDataSource(result.output!!.path)
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)!!.toInt()
        val cw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
        val ch = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
        r.release()
        // The encoder may pad or scale the size, but the shape and orientation must be kept.
        val (dw, dh) = if (rot % 180 != 0) ch to cw else cw to ch
        assertTrue("size ${dw}x$dh (rotation $rot)", dh > dw && Math.abs(dw.toDouble() / dh - 112.0 / 200) < 0.06)
        assertTrue("output too small", result.output!!.length() > 1_000)
        p.close()
    }

    @Test fun serviceEndToEnd() {
        val meta = VideoMeta.probe(ctx, fixture("woman_and_man.mp4"))
        ProcessingRepository.reset()
        ProcessingService.start(ctx, ProcessingService.Job.Full(meta, CensorSettings(speed = "fast")))
        val deadline = System.currentTimeMillis() + 10 * 60_000
        val terminal = setOf(JobState.Stage.COMPLETE, JobState.Stage.ERROR, JobState.Stage.CANCELLED)
        while (ProcessingRepository.state.value.stage !in terminal && System.currentTimeMillis() < deadline) Thread.sleep(500)
        val s = ProcessingRepository.state.value
        assertEquals("error: ${s.error}", JobState.Stage.COMPLETE, s.stage)
        checkOutput(s.output, 768, 432, meta.durationSec)
    }
}
