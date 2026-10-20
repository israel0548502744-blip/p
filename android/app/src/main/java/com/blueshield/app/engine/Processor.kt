package com.blueshield.app.engine

import com.blueshield.app.ui.tr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import kotlin.math.min
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

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
    /** The finished video's entry in the recent results. */
    val entryId: Long? = null,
    /** A queue of videos: which one is running (1-based) of how many, and the finished ones' entries. */
    val queueIndex: Int = 0,
    val queueTotal: Int = 0,
    val queueIds: List<Long> = emptyList(),
    val queueFailed: List<String> = emptyList(),
    /** Saving to the gallery failed (the result is still in the recent results). */
    val saveError: String? = null,
) {
    /** CHOOSING: the people are found and the user picks whom to censor before anything is drawn. */
    enum class Stage { IDLE, ANALYZING, DETECTING, APPLYING, ENCODING, CHOOSING, COMPLETE, ERROR, CANCELLED }
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
    /** Presentation time (µs) of every analysed frame, in order — the render pass matches masks by time, not by count. */
    private var analysedPts = LongArray(0)
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

    private var modelsMode: String? = null

    /** People censored only within a time span (seconds of the video); set before a re-render. */
    @Volatile var ranges: Map<Int, ClosedFloatingPointRange<Double>> = emptyMap()

    /**
     * Held while a video or a photo is analysed: the two use separate model stores and opening one closes the other,
     * so a video started while a photo is still being analysed waits instead of closing the photo's models under it.
     */
    private val modelLock = java.util.concurrent.locks.ReentrantLock()

    /** The models, on the processor chosen in the settings ([Accelerators]); rebuilt when that setting changes. */
    private fun models(): ModelStore {
        photoModels?.close()
        photoModels = null
        val mode = Accelerators.mode(context)
        models?.let { if (modelsMode == mode) return it; it.close(); models = null }
        val (engines, fastest) = Accelerators.engines(mode)
        return ModelStore(
            load = ::loadModel,
            options = { Accelerators.base() },
            onEvent = Breadcrumbs::mark,
            engines = engines,
            pickFastest = fastest,
            memory = EngineChoices(context, mode, Accelerators.signature(context)),
            probeImage = Accelerators::probeImage,
        ).also { models = it; modelsMode = mode }
    }

    private var photoModels: ModelStore? = null

    /**
     * The models for photos: always the plain CPU engine, whatever the processor setting — the path the photo tests
     * measure, so a photo never depends on what an AI chip or GPU computes (a photo is one frame: they would save a
     * second at most). Only one set of models is open at a time.
     */
    private fun photoModels(): ModelStore {
        models?.close()
        models = null
        return photoModels ?: ModelStore(load = ::loadModel, options = { Accelerators.base() }, onEvent = Breadcrumbs::mark)
            .also { photoModels = it }
    }

    private fun loadModel(name: String): ByteArray = context.assets.open("models/$name").use { it.readBytes() }

    fun releaseAnalysis() {
        analysis?.close()
        analysis = null
    }

    /** Full run: analyse then render. [analyseOnly]: stop once the people are found ([JobState.Stage.CHOOSING]). */
    fun run(meta: VideoMeta, settings: CensorSettings, analyseOnly: Boolean = false, update: (JobState) -> Unit): JobState {
        cancelFlag.set(false)
        paused = false
        releaseAnalysis()
        Breadcrumbs.reset("run: ${meta.name} ${meta.width}x${meta.height}@${"%.2f".format(meta.fps)} ${meta.frameCount} frames ${meta.videoMime} rot=${meta.rotation}")
        this.meta = meta
        this.settings = settings.validated()
        var state = JobState(stage = JobState.Stage.ANALYZING, totalFrames = meta.frameCount)
        update(state)
        modelLock.lock()
        var locked = true
        try {
            val (aw, ah) = Analyzer.scaledSize(meta.width, meta.height, spec.analysisMaxSide)
            val (mw, mh) = Analyzer.scaledSize(meta.width, meta.height, spec.maskMaxSide)
            val store = File(context.cacheDir, "masks_${System.currentTimeMillis()}.bin")
            Breadcrumbs.mark("analysis: creating analyzer (${aw}x$ah, masks ${mw}x$mh)")
            val store0 = models()
            val analyzer = Analyzer(store0, this.settings, spec, aw, ah, meta.fps, mw, mh, store)
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
            val pts = ArrayList<Long>()
            // Decoding (MediaCodec + YUV→RGB) runs on its own thread, a few chunks ahead of the analysis,
            // so the two overlap instead of taking turns.
            val queue = ArrayBlockingQueue<Any>(analyzer.detStride * 4 * 3)
            val stop = AtomicBoolean(false)
            var decodeError: Throwable? = null
            val decoder = thread(name = "blueshield-decode") {
                try {
                    FrameExtractor(context, meta, aw, ah).run { _, ptsUs, frame ->
                        val item = DecodedFrame(ptsUs, frame)
                        while (!stop.get()) if (queue.offer(item, 100, TimeUnit.MILLISECONDS)) return@run true
                        false
                    }
                } catch (t: Throwable) {
                    decodeError = t
                } finally {
                    while (!stop.get() && !queue.offer(END_OF_FRAMES, 100, TimeUnit.MILLISECONDS)) Unit
                }
            }
            try {
                while (true) {
                    val item = queue.take()
                    if (item === END_OF_FRAMES) break
                    item as DecodedFrame
                    chunk += item.frame
                    pts += item.ptsUs
                    if (chunk.size >= analyzer.detStride * 4) flush()
                    if (!checkpoint(meter)) {
                        cancelled = true
                        break
                    }
                }
            } finally {
                stop.set(true)
                queue.clear()
                decoder.join()
            }
            decodeError?.let { throw it }
            if (!cancelled && chunk.isNotEmpty()) flush()
            if (cancelled) {
                analyzer.store.close()
                return JobState(stage = JobState.Stage.CANCELLED).also(update)
            }
            if (analyzer.processed == 0) error(tr("לא ניתן לפענח אף פריים מהסרטון."))
            analysis = analyzer.finish()
            // decoders deliver frames in presentation order; if this one didn't, fall back to counting
            analysedPts = pts.toLongArray().takeIf { arr -> (1 until arr.size).all { arr[it] > arr[it - 1] } } ?: LongArray(0)
            Breadcrumbs.mark("analysis: done, ${analysis?.people?.size ?: 0} people")
            Breadcrumbs.mark("timings: " + analyzer.timings.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${"%.1f".format(it.value / 1e9)}s" } +
                "; engines: " + store0.used.entries.joinToString { "${it.key.substringBefore('.')}=${it.value}" })
            Accelerators.saveReport(context, store0)
            modelLock.unlock()
            locked = false
            if (analyseOnly) {
                val a = analysis!!
                return state.copy(stage = JobState.Stage.CHOOSING, percent = 100f, people = a.summaries(this.settings, emptyMap()), preview = null)
                    .also(update)
            }
            return renderInternal(emptyMap(), state, ANALYSIS_SHARE, update)
        } catch (e: Renderer.CancelledException) {
            return JobState(stage = JobState.Stage.CANCELLED).also(update)
        } catch (e: Throwable) {
            Breadcrumbs.mark("run failed: ${Errors.describe(e)}")
            return JobState(stage = JobState.Stage.ERROR, error = Errors.describe(e)).also(update)
        } finally {
            if (locked) modelLock.unlock()
        }
    }

    /** Re-render only (after manual overrides). Keeps the previous result if cancelled. */
    fun rerender(overrides: Map<Int, Override>, previous: JobState, update: (JobState) -> Unit): JobState {
        cancelFlag.set(false)
        paused = false
        return try {
            renderInternal(overrides, previous.copy(stage = JobState.Stage.APPLYING, percent = 0f, error = null), 0f, update)
        } catch (e: Renderer.CancelledException) {
            previous.copy(stage = if (previous.output == null) JobState.Stage.CHOOSING else JobState.Stage.COMPLETE).also(update)
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
        val ptsIndex = analysedPts
        // the mask of analysed frame i, ready to upload (pure: the same i always gives the same mask)
        fun build(i: Int): Renderer.Mask? {
            val t = i / m.fps
            val d = if (ranges.isEmpty()) decisions else decisions + ranges.mapValues { (_, r) -> t in r }
            val raw = a.maskFor(i, d, s, lookahead)
            if (!raw.any()) return null
            val e = Composer.edgeMasks(raw, m.width, m.height, s.aggressive) ?: return null
            val cloth = if (s.fill == "clothing") a.clothFor(i) else null
            return Renderer.Mask(e.soft.width, e.soft.height, e.soft.data, e.band.data, cloth, a.clothWidth, a.clothHeight)
        }
        // Masks are built a few frames ahead on other cores while the GPU draws and the encoder encodes: on the
        // render thread alone, composing and feathering each mask took longer than decoding + drawing + encoding.
        val ahead = java.util.concurrent.Executors.newFixedThreadPool(MASK_THREADS) { r -> Thread(r, "blueshield-masks").apply { isDaemon = true } }
        val pending = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.Future<Renderer.Mask?>>()
        val source = object : Renderer.FrameSource {
            override fun mask(index: Int, ptsUs: Long): Renderer.Mask? {
                val i = analysisIndex(ptsIndex, index, ptsUs, total)
                if (i < 0) return null
                for (j in i + 1..min(total - 1, i + MASK_AHEAD)) pending.computeIfAbsent(j) { ahead.submit<Renderer.Mask?> { build(j) } }
                pending.keys.removeIf { it < i - 2 }
                val mask = (pending.remove(i)?.get() ?: build(i)) ?: return null
                censored++
                return mask
            }
        }
        // the models aren't needed to render: free them (their memory, and any GPU / AI-chip buffers) for the
        // video decoder and encoder
        models?.close()
        models = null
        val options = Renderer.Options(CensorSettingsColor.rgb(s), s.animated, s.keepAudio, s.quality, softness = s.softness, clothing = s.fill == "clothing")
        fun render(o: Renderer.Options) = Renderer(context, m).render(
            out, source, o, total,
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
        try {
            try {
                render(options)
            } catch (e: Renderer.IncompleteException) {
                pending.clear()
                // a hardware encoder that loses a frame leaves every later frame undecodable: never hand that out
                Breadcrumbs.mark("render: ${e.message}; again with a software encoder")
                out.delete()
                censored = 0
                try {
                    render(Renderer.Options(options.rgb, options.animated, options.keepAudio, options.quality, softwareEncoder = true, softness = options.softness, clothing = options.clothing))
                } catch (e2: Renderer.IncompleteException) {
                    out.delete()
                    error(tr("קידוד הסרטון נכשל: המקודד איבד פריימים ({0} מתוך {1}). נסו שוב, או בחרו איכות ייצוא אחרת.", e2.written, e2.sent))
                }
            }
        } finally {
            ahead.shutdownNow()
        }
        Breadcrumbs.mark("render: done")
        start.output?.takeIf { it != out }?.delete()
        state = state.copy(stage = JobState.Stage.COMPLETE, percent = 100f, etaSeconds = 0.0, output = out, censoredFrames = censored, version = version)
        update(state)
        return state
    }

    /**
     * The analysed frame shown at [ptsUs]. Matching by timestamp keeps every mask on its own frame even
     * when the two decoding passes don't deliver exactly the same frames (a dropped or extra frame would
     * otherwise shift every following mask). Falls back to the running index when no timestamps exist.
     */
    private fun analysisIndex(pts: LongArray, index: Int, ptsUs: Long, total: Int): Int {
        if (pts.size != total || total == 0) return if (index < total) index else -1
        var lo = 0
        var hi = pts.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (pts[mid] < ptsUs) lo = mid + 1 else hi = mid
        }
        val best = if (lo > 0 && kotlin.math.abs(pts[lo - 1] - ptsUs) <= kotlin.math.abs(pts[lo] - ptsUs)) lo - 1 else lo
        return best
    }

    // ── photos ──────────────────────────────────────────────────────────────────────────────────

    /** A censored photo, plus what the people list needs for overrides. */
    class PhotoResult(val original: Bitmap, val censored: Bitmap, val people: List<PersonSummary>, val file: File, val overrides: Map<Int, Override>)

    /** How censored photos are written: "jpg" or "png", and the JPEG quality. */
    @Volatile var photoFormat = "jpg"
    @Volatile var photoQuality = 94

    private var photo: Analysis? = null
    private var photoOriginal: Bitmap? = null
    private var photoSettings: CensorSettings = CensorSettings()

    /** Analyse and censor one photo (same rules as video: people, women only, neckline, text stays visible). */
    fun processPhoto(uri: android.net.Uri, settings: CensorSettings): PhotoResult {
        val s = settings.validated()
        Breadcrumbs.reset("photo: $uri")
        // what the diagnostics report needs to replay this photo on a computer (PhotoCheck): version, settings, sizes
        Breadcrumbs.mark("photo: BlueShield ${com.blueshield.app.BuildInfo.version}, settings ${kotlinx.serialization.json.Json.encodeToString(CensorSettings.serializer(), s)}")
        var fileSize = ""
        val bmp = PhotoLoader.load(context, uri) { w, h -> fileSize = "${w}x$h" }
        val (aw, ah) = Analyzer.scaledSize(bmp.width, bmp.height, spec.analysisMaxSide)
        val (mw, mh) = Analyzer.scaledSize(bmp.width, bmp.height, spec.maskMaxSide)
        Breadcrumbs.mark("photo: file $fileSize, upright ${bmp.width}x${bmp.height}, analysis ${aw}x$ah, masks ${mw}x$mh")
        photo?.close()
        val store = File(context.cacheDir, "photo_masks_${System.currentTimeMillis()}.bin")
        val t0 = System.nanoTime()
        modelLock.lock()
        val m: ModelStore
        val analysis = try {
            m = photoModels()
            // faces are classified from the photo itself, not from its analysis-size copy
            val faces = com.blueshield.core.gender.FaceSource(bmp.width, bmp.height, (bmp.width.toFloat() / aw + bmp.height.toFloat() / ah) / 2) { y, x, n, out ->
                bmp.getPixels(out, 0, n, x, y, n, 1)
            }
            // (no Accelerators.saveReport: photos always run on the plain CPU; the settings screen keeps the video's engines)
            com.blueshield.core.pipeline.StillImage.analyze(m, s, spec, PhotoLoader.toRgb(bmp, aw, ah), mw, mh, store, faces)
        } finally {
            // the 1024 px outline encoder holds a few hundred MB while loaded; the photo still has to be painted
            photoModels?.release(ModelStore.SAM_ENCODER_1024)
            photoModels?.release(ModelStore.CLOTHES)
            modelLock.unlock()
        }
        photo = analysis
        photoOriginal = bmp
        photoSettings = s
        Breadcrumbs.mark("photo: analysed in ${"%.1f".format((System.nanoTime() - t0) / 1e9)} s, ${analysis.people.size} people; engines: " +
            m.used.entries.joinToString { "${it.key.substringBefore('.')}=${it.value}" })
        val decisions = analysis.decisions(s, emptyMap())
        for (p in analysis.summaries(s, emptyMap())) {
            Breadcrumbs.mark("photo: person #${p.id} ${p.gender} pFemale=${"%.2f".format(p.pFemale)} votes=${p.votes} age=${p.age?.let { "%.0f".format(it) }} " +
                "child=${p.child} censored=${decisions[p.id]} box=${analysis.people[p.id]?.box}")
        }
        return renderPhoto(emptyMap())
    }

    /**
     * Re-paint the analysed photo with manual per-person overrides (no re-analysis) and the strokes painted by hand
     * ([BrushStroke]: add cover where something was missed, or erase cover that isn't needed).
     */
    fun renderPhoto(overrides: Map<Int, Override>, strokes: List<BrushStroke> = emptyList()): PhotoResult {
        val a = photo ?: error("No photo analysed")
        val original = photoOriginal ?: error("No photo")
        val s = photoSettings
        val decisions = a.decisions(s, overrides)
        val w = original.width
        val h = original.height
        val out = original.copy(Bitmap.Config.ARGB_8888, true)
        val px = IntArray(w * h)
        out.getPixels(px, 0, w, 0, 0, w, h)
        com.blueshield.core.pipeline.StillImage.alpha(a, s, decisions, w, h, px)?.let { alpha ->
            val cloth = if (s.fill == "clothing") a.clothFor(0) else null
            com.blueshield.core.pipeline.StillImage.paint(px, w, h, alpha, CensorSettingsColor.rgb(s), cloth, a.clothWidth, a.clothHeight)
            out.setPixels(px, 0, w, 0, 0, w, h)
        }
        if (strokes.isNotEmpty()) {
            val orig = IntArray(w * h)
            original.getPixels(orig, 0, w, 0, 0, w, h)
            BrushStroke.apply(strokes, px, orig, w, h, CensorSettingsColor.rgb(s))
            out.setPixels(px, 0, w, 0, 0, w, h)
        }
        val png = photoFormat == "png"
        val file = File(context.filesDir, "outputs/blueshield_photo_${System.currentTimeMillis()}." + if (png) "png" else "jpg").also { it.parentFile?.mkdirs() }
        file.outputStream().use { out.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (png) 100 else photoQuality, it) }
        return PhotoResult(original, out, a.summaries(s, overrides), file, overrides)
    }

    /** People summaries for a new set of overrides (no re-render). */
    fun summaries(overrides: Map<Int, Override>): List<PersonSummary> = analysis?.summaries(settings, overrides) ?: emptyList()

    /** One picture with the people found on it ([boxes]: fractions of the picture), for marking whom to censor. */
    class Overview(val image: Bitmap, val timeSec: Double, val durationSec: Double, val boxes: Map<Int, android.graphics.RectF>)

    private fun boxesOf(a: Analysis, frame: Int, aw: Int, ah: Int): Map<Int, android.graphics.RectF> {
        val r = a.records.getOrNull(frame) ?: return emptyMap()
        val out = LinkedHashMap<Int, android.graphics.RectF>()
        for (k in 0 until r.personCount) {
            val raw = r.persons[k * 5].toInt()
            val id = a.aliases[raw] ?: raw
            if (id !in a.people) continue
            out[id] = android.graphics.RectF(
                (r.persons[k * 5 + 1] / aw).coerceIn(0f, 1f), (r.persons[k * 5 + 2] / ah).coerceIn(0f, 1f),
                (r.persons[k * 5 + 3] / aw).coerceIn(0f, 1f), (r.persons[k * 5 + 4] / ah).coerceIn(0f, 1f),
            )
        }
        return out
    }

    /** The analysed photo with its people. */
    fun photoOverview(): Overview? {
        val a = photo ?: return null
        val img = photoOriginal ?: return null
        val (aw, ah) = Analyzer.scaledSize(img.width, img.height, spec.analysisMaxSide)
        return Overview(img, 0.0, 0.0, boxesOf(a, 0, aw, ah))
    }

    /** The analysed frame where the most people are on screen (nearest the middle on a tie), read again from the video. */
    fun videoOverview(): Overview? {
        val a = analysis ?: return null
        val m = meta ?: return null
        if (a.records.isEmpty()) return null
        val mid = a.records.size / 2
        val best = a.records.indices.maxWithOrNull(compareBy<Int>({ i -> a.records[i].personCount }, { i -> -kotlin.math.abs(i - mid) })) ?: return null
        val (aw, ah) = Analyzer.scaledSize(m.width, m.height, spec.analysisMaxSide)
        val us = analysedPts.getOrNull(best) ?: (best * 1e6 / m.fps).toLong()
        val mmr = android.media.MediaMetadataRetriever()
        val frame = try {
            mmr.setDataSource(context, m.uri)
            mmr.getFrameAtTime(us, android.media.MediaMetadataRetriever.OPTION_CLOSEST)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { mmr.release() }
        } ?: return null
        // some phones hand the frame over as stored, not turned upright
        var img = frame
        if ((img.width > img.height) != (m.width > m.height) && m.rotation % 180 != 0) {
            val mx = android.graphics.Matrix().apply { postRotate(m.rotation.toFloat()) }
            img = Bitmap.createBitmap(img, 0, 0, img.width, img.height, mx, true)
        }
        val side = maxOf(img.width, img.height)
        if (side > 1280) img = Bitmap.createScaledBitmap(img, img.width * 1280 / side, img.height * 1280 / side, true)
        return Overview(img, us / 1e6, m.durationUs / 1e6, boxesOf(a, best, aw, ah))
    }

    fun personThumbnail(id: Int): Bitmap? = (analysis?.people?.get(id) ?: photo?.people?.get(id))?.thumbnail?.let { toBitmap(it) }

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
                    com.blueshield.core.gender.GenderEstimate.Label.FEMALE -> tr("אישה") + " ${(t.gender.confidence * 100).toInt()}%"
                    com.blueshield.core.gender.GenderEstimate.Label.MALE -> tr("גבר") + " ${(t.gender.confidence * 100).toInt()}%"
                    else -> tr("לא בטוח")
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
        photo?.close()
        photo = null
        models?.close()
        models = null
        photoModels?.close()
        photoModels = null
    }

    companion object {
        const val ANALYSIS_SHARE = 0.7f
        /** Masks built ahead of the render thread, on this many threads. */
        private val MASK_THREADS = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
        private const val MASK_AHEAD = 6

        fun toBitmap(img: RgbImage): Bitmap {
            val px = IntArray(img.width * img.height) { i ->
                Color.rgb(img.data[i * 3].toInt() and 0xFF, img.data[i * 3 + 1].toInt() and 0xFF, img.data[i * 3 + 2].toInt() and 0xFF)
            }
            // createBitmap(int[]…) returns an *immutable* bitmap; the live preview draws on it, so make it mutable
            return Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888).also { it.setPixels(px, 0, img.width, 0, 0, img.width, img.height) }
        }
    }
}

private class DecodedFrame(val ptsUs: Long, val frame: RgbImage)

private val END_OF_FRAMES = Any()

object CensorSettingsColor {
    fun rgb(s: CensorSettings) = CensorSettings.parseColor(s.color)
}
