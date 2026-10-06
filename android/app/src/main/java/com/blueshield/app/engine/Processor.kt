package com.blueshield.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import ai.onnxruntime.OrtSession
import com.blueshield.core.CensorSettings
import com.blueshield.core.PipelineSpec
import com.blueshield.core.gender.Override
import com.blueshield.core.image.RgbImage
import com.blueshield.core.ml.ModelStore
import com.blueshield.core.pipeline.Analysis
import com.blueshield.core.pipeline.Analyzer
import com.blueshield.core.pipeline.Composer
import com.blueshield.core.pipeline.PersonSummary
import com.blueshield.core.pipeline.ProgressMeter
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** UI-facing job state. */
data class JobState(
    val stage: Stage = Stage.IDLE,
    val percent: Float = 0f,
    val frame: Int = 0,
    val totalFrames: Int = 0,
    val etaSeconds: Double? = null,
    val fps: Double = 0.0,
    val elapsed: Double = 0.0,
    val paused: Boolean = false,
    val passIndex: Int = 0,
    val error: String? = null,
    val output: File? = null,
    val people: List<PersonSummary> = emptyList(),
    val overrides: Map<Int, Override> = emptyMap(),
    val censoredFrames: Int = 0,
    val preview: Bitmap? = null,
    val version: Int = 0,
) {
    enum class Stage { IDLE, ANALYZING, DETECTING, APPLYING, ENCODING, COMPLETE, ERROR, CANCELLED }
    val running get() = stage in setOf(Stage.ANALYZING, Stage.DETECTING, Stage.APPLYING, Stage.ENCODING)
}

/**
 * Runs analysis + rendering on-device. Holds the [Analysis] afterwards so manual
 * per-person overrides only need a (much faster) re-render.
 */
class Processor(private val context: Context) {
    private val spec by lazy { PipelineSpec.bundled }
    private var models: ModelStore? = null
    var analysis: Analysis? = null
        private set
    private var meta: VideoMeta? = null
    private var settings: CensorSettings = CensorSettings()
    private val cancelFlag = AtomicBoolean(false)
    private val pauseLock = Object()
    @Volatile private var paused = false

    fun pause() { paused = true }
    fun resume() { synchronized(pauseLock) { paused = false; pauseLock.notifyAll() } }
    fun cancel() { cancelFlag.set(true); resume() }

    /** Blocks while paused; false once cancelled. Tracks paused time so ETA stays honest. */
    private fun checkpoint(meter: ProgressMeter?): Boolean {
        if (paused) {
            val t0 = System.nanoTime()
            synchronized(pauseLock) { while (paused && !cancelFlag.get()) pauseLock.wait(250) }
            meter?.let { it.pausedNanos += System.nanoTime() - t0 }
        }
        return !cancelFlag.get()
    }

    private fun models(): ModelStore = models ?: ModelStore(
        load = { name -> context.assets.open("models/$name").use { it.readBytes() } },
        options = { sessionOptions() },
        onEvent = Breadcrumbs::mark,
    ).also { models = it }

    /**
     * CPU execution provider only. Hardware delegates (NNAPI/XNNPACK) are faster on paper, but NNAPI is
     * deprecated and can crash natively on some drivers; a flagship CPU is fast enough, and stability first.
     */
    private fun sessionOptions(): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 6))
        setInterOpNumThreads(1)
    }

    fun releaseAnalysis() {
        analysis?.close()
        analysis = null
    }

    /** Full run: analyse then render. */
    fun run(meta: VideoMeta, settings: CensorSettings, update: (JobState) -> Unit): JobState {
        cancelFlag.set(false)
        paused = false
        releaseAnalysis()
        Breadcrumbs.reset("run: ${meta.name} ${meta.width}x${meta.height}@${"%.2f".format(meta.fps)} ${meta.frameCount} frames ${meta.videoMime} rot=${meta.rotation}")
        this.meta = meta
        this.settings = settings.validated()
        var state = JobState(stage = JobState.Stage.ANALYZING, totalFrames = meta.frameCount)
        update(state)
        try {
            val (aw, ah) = Analyzer.scaledSize(meta.width, meta.height, spec.analysisMaxSide)
            val (mw, mh) = Analyzer.scaledSize(meta.width, meta.height, spec.maskMaxSide)
            val store = File(context.cacheDir, "masks_${System.currentTimeMillis()}.bin")
            Breadcrumbs.mark("analysis: creating analyzer (${aw}x$ah, masks ${mw}x$mh)")
            val analyzer = Analyzer(models(), this.settings, spec, aw, ah, meta.fps, mw, mh, store)
            Breadcrumbs.mark("analysis: analyzer ready, decoding frames")
            val meter = ProgressMeter(meta.frameCount, 0f, ANALYSIS_SHARE)
            state = state.copy(stage = JobState.Stage.DETECTING, passIndex = 1)
            update(state)
            val chunk = ArrayList<RgbImage>()
            var lastPreview = 0L
            var cancelled = false
            fun flush() {
                val chunkStart = analyzer.processed
                analyzer.process(chunk) { idx ->
                    if (idx % 30 == 0) Breadcrumbs.mark("analysis: frame $idx")
                    val snap = meter.update(idx + 1)
                    val now = System.nanoTime()
                    var preview = state.preview
                    if (now - lastPreview > 700_000_000L) {
                        lastPreview = now
                        preview = analysisPreview(chunk[idx - chunkStart], analyzer)
                    }
                    state = state.copy(percent = snap.percent, frame = snap.frame, etaSeconds = snap.etaSeconds, fps = snap.fps, elapsed = snap.elapsed, preview = preview, paused = paused)
                    update(state)
                }
                chunk.clear()
            }
            FrameExtractor(context, meta, aw, ah).run { _, _, frame ->
                chunk += frame
                if (chunk.size >= analyzer.detStride * 4) flush()
                val ok = checkpoint(meter)
                if (!ok) cancelled = true
                ok
            }
            if (!cancelled && chunk.isNotEmpty()) flush()
            if (cancelled) {
                analyzer.store.close()
                return JobState(stage = JobState.Stage.CANCELLED).also(update)
            }
            if (analyzer.processed == 0) error("Could not decode any frames from this video.")
            analysis = analyzer.finish()
            Breadcrumbs.mark("analysis: done, ${analysis?.people?.size ?: 0} people")
            return renderInternal(emptyMap(), state, ANALYSIS_SHARE, update)
        } catch (e: Renderer.CancelledException) {
            return JobState(stage = JobState.Stage.CANCELLED).also(update)
        } catch (e: Throwable) {
            Breadcrumbs.mark("run failed: ${Errors.describe(e)}")
            return JobState(stage = JobState.Stage.ERROR, error = Errors.describe(e)).also(update)
        }
    }

    /** Re-render only (after manual overrides). Keeps the previous result if cancelled. */
    fun rerender(overrides: Map<Int, Override>, previous: JobState, update: (JobState) -> Unit): JobState {
        cancelFlag.set(false)
        paused = false
        return try {
            renderInternal(overrides, previous.copy(stage = JobState.Stage.APPLYING, percent = 0f, error = null), 0f, update)
        } catch (e: Renderer.CancelledException) {
            previous.copy(stage = JobState.Stage.COMPLETE).also(update)
        } catch (e: Throwable) {
            Breadcrumbs.mark("re-render failed: ${Errors.describe(e)}")
            JobState(stage = JobState.Stage.ERROR, error = Errors.describe(e)).also(update)
        }
    }

    private fun renderInternal(overrides: Map<Int, Override>, start: JobState, lo: Float, update: (JobState) -> Unit): JobState {
        val a = analysis ?: error("No analysis available")
        val m = meta ?: error("No video")
        val s = settings
        val decisions = a.decisions(s, overrides)
        val lookahead = a.lookahead()
        val total = a.frameCount
        val meter = ProgressMeter(total, lo, 1f)
        var state = start.copy(stage = JobState.Stage.APPLYING, passIndex = 2, totalFrames = total, people = a.summaries(s, overrides), overrides = overrides)
        update(state)
        var censored = 0
        Breadcrumbs.mark("render: start (${m.width}x${m.height}, $total frames)")
        val version = start.version + 1
        val out = File(context.filesDir, "outputs/blueshield_${System.currentTimeMillis()}_v$version.mp4").also { it.parentFile?.mkdirs() }
        val source = object : Renderer.FrameSource {
            override fun mask(index: Int): Renderer.Mask? {
                if (index >= total) return null
                val raw = a.maskFor(index, decisions, s, lookahead)
                if (!raw.any()) return null
                censored++
                val f = Composer.feather(raw, m.width, m.height, s.softness, s.aggressive)
                return Renderer.Mask(f.width, f.height, f.data)
            }
        }
        Renderer(context, m).render(
            out, source, Renderer.Options(CensorSettingsColor.rgb(s), s.animated, s.keepAudio, s.quality), total,
            shouldContinue = { checkpoint(meter) },
            onProgress = { i ->
                if (i % 60 == 0) Breadcrumbs.mark("render: frame $i")
                val snap = meter.update(i)
                state = state.copy(percent = snap.percent, frame = snap.frame, etaSeconds = snap.etaSeconds, fps = snap.fps, elapsed = snap.elapsed, paused = paused,
                    stage = if (i >= total) JobState.Stage.ENCODING else JobState.Stage.APPLYING)
                update(state)
            },
            onPreview = { bmp -> state = state.copy(preview = bmp); update(state) },
        )
        Breadcrumbs.mark("render: done")
        start.output?.takeIf { it != out }?.delete()
        state = state.copy(stage = JobState.Stage.COMPLETE, percent = 100f, etaSeconds = 0.0, output = out, censoredFrames = censored, version = version)
        update(state)
        return state
    }

    /** People summaries for a new set of overrides (no re-render). */
    fun summaries(overrides: Map<Int, Override>): List<PersonSummary> = analysis?.summaries(settings, overrides) ?: emptyList()

    fun personThumbnail(id: Int): Bitmap? = analysis?.people?.get(id)?.thumbnail?.let { toBitmap(it) }

    private fun analysisPreview(frame: RgbImage, analyzer: Analyzer): Bitmap {
        val bmp = toBitmap(frame)
        val skin = analyzer.lastSkinBinary
        val rgb = CensorSettingsColor.rgb(settings)
        if (skin != null && skin.size == frame.width * frame.height) {
            // tint what the *current* decisions would censor
            val vis = analyzer.visiblePeople()
            val pixels = IntArray(frame.width * frame.height)
            bmp.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
            for (i in pixels.indices) if (skin[i]) {
                val x = (i % frame.width).toFloat()
                val y = (i / frame.width).toFloat()
                val owner = vis.filter { it.box.contains(x, y) }.minByOrNull { it.box.area }
                val censor = owner?.let { analyzer.currentDecision(it) } ?: settings.censorUnassigned
                if (censor) pixels[i] = blend(pixels[i], rgb)
            }
            bmp.setPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
            val canvas = Canvas(bmp)
            val stroke = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true }
            val text = Paint().apply { color = Color.WHITE; textSize = 22f; isAntiAlias = true }
            for (t in vis) {
                val censor = analyzer.currentDecision(t)
                stroke.color = if (censor) Color.rgb(40, 120, 255) else Color.GRAY
                canvas.drawRect(t.box.x1, t.box.y1, t.box.x2, t.box.y2, stroke)
                val label = when (t.gender.label(settings.threshold01)) {
                    com.blueshield.core.gender.GenderEstimate.Label.FEMALE -> "Female ${(t.gender.confidence * 100).toInt()}%"
                    com.blueshield.core.gender.GenderEstimate.Label.MALE -> "Male ${(t.gender.confidence * 100).toInt()}%"
                    else -> "Unsure"
                }
                canvas.drawText("#${t.id} $label", t.box.x1 + 6, maxOf(24f, t.box.y1 + 24f), text)
            }
        }
        return bmp
    }

    private fun blend(argb: Int, rgb: Int): Int {
        val r = ((argb shr 16 and 0xFF) * 0.3f + (rgb shr 16 and 0xFF) * 0.7f).toInt()
        val g = ((argb shr 8 and 0xFF) * 0.3f + (rgb shr 8 and 0xFF) * 0.7f).toInt()
        val b = ((argb and 0xFF) * 0.3f + (rgb and 0xFF) * 0.7f).toInt()
        return Color.rgb(r, g, b)
    }

    fun close() {
        releaseAnalysis()
        models?.close()
        models = null
    }

    companion object {
        const val ANALYSIS_SHARE = 0.7f

        fun toBitmap(img: RgbImage): Bitmap {
            val px = IntArray(img.width * img.height) { i ->
                Color.rgb(img.data[i * 3].toInt() and 0xFF, img.data[i * 3 + 1].toInt() and 0xFF, img.data[i * 3 + 2].toInt() and 0xFF)
            }
            // createBitmap(int[]…) returns an *immutable* bitmap; the live preview draws on it, so make it mutable
            return Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888).also { it.setPixels(px, 0, img.width, 0, 0, img.width, img.height) }
        }
    }
}

object CensorSettingsColor {
    fun rgb(s: CensorSettings) = CensorSettings.parseColor(s.color)
}
