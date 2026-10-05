package com.blueshield.core.pipeline

import com.blueshield.core.CensorSettings
import com.blueshield.core.PipelineSpec
import com.blueshield.core.gender.GenderClassifier
import com.blueshield.core.gender.GenderEstimate
import com.blueshield.core.gender.Override
import com.blueshield.core.gender.censorDecision
import com.blueshield.core.image.ByteMask
import com.blueshield.core.image.FloatMask
import com.blueshield.core.image.MaskOps
import com.blueshield.core.image.RgbImage
import com.blueshield.core.ml.ColorSkin
import com.blueshield.core.ml.FaceDetector
import com.blueshield.core.ml.GenderModel
import com.blueshield.core.ml.ModelStore
import com.blueshield.core.ml.NudeNet
import com.blueshield.core.ml.PersonDetector
import com.blueshield.core.ml.SkinSegmenter
import com.blueshield.core.track.OpticalFlow
import com.blueshield.core.track.PersonTrack
import com.blueshield.core.track.PersonTracker
import com.blueshield.core.track.RegionTracker
import com.blueshield.core.track.SceneCutDetector
import com.blueshield.core.track.TemporalFuser
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Summary of one detected person, for the review UI. */
data class PersonSummary(
    val id: Int,
    val gender: String,
    val pFemale: Double,
    val confidence: Double,
    val votes: Int,
    val censored: Boolean,
    val override: Override,
    val frames: Int,
    val startSec: Double,
    val endSec: Double,
)

/** Everything the render stage needs; kept so overrides only require re-rendering. */
class Analysis(
    val settings: CensorSettings,
    val spec: PipelineSpec,
    val fps: Double,
    val store: MaskStore,
    val records: List<FrameRecord>,
    val people: Map<Int, PersonTrack>,
) : AutoCloseable {
    val frameCount get() = records.size

    fun decisions(settings: CensorSettings, overrides: Map<Int, Override>): Map<Int, Boolean> =
        people.mapValues { (id, t) ->
            censorDecision(t.gender.label(settings.threshold01), settings.target, settings.uncertainPolicy, overrides[id] ?: Override.AUTO)
        }

    fun summaries(settings: CensorSettings, overrides: Map<Int, Override>): List<PersonSummary> =
        people.values.sortedWith(compareBy({ -it.frames }, { it.id })).map { t ->
            val label = t.gender.label(settings.threshold01)
            val ov = overrides[t.id] ?: Override.AUTO
            PersonSummary(
                t.id, label.key, t.gender.pFemale, t.gender.confidence, t.gender.votes,
                censorDecision(label, settings.target, settings.uncertainPolicy, ov), ov, t.frames,
                t.firstFrame / fps, t.lastFrame / fps,
            )
        }

    /** Censor mask for frame i at mask resolution (before feathering), including look-ahead. */
    fun maskFor(i: Int, decisions: Map<Int, Boolean>, settings: CensorSettings, lookahead: Int): ByteMask {
        var m = Composer.compose(store[i], records[i], decisions, settings.censorUnassigned, spec.ownershipBoxPad, spec.unassignedMinArea)
        for (j in 1..lookahead) {
            if (i + j >= records.size) break
            val next = Composer.compose(store[i + j], records[i + j], decisions, settings.censorUnassigned, spec.ownershipBoxPad, spec.unassignedMinArea)
            if (next.any()) {
                if (m.any()) m.maxWith(next) else m = next
            }
        }
        return m
    }

    fun lookahead(): Int {
        val preset = spec.speedPresets.getValue(settings.speed)
        val segStride = if (settings.aggressive) 1 else preset.segStride
        return max(0, segStride - 1) + if (settings.aggressive) 1 else 0
    }

    override fun close() = store.close()
}

/**
 * Stateful, streaming analysis (stages 1–3 + skin + sensitive regions). Feed frames in
 * order via [process] (in chunks so NudeNet keyframes can be batched), then [finish].
 * Mirrors desktop `pipeline.analyze`.
 */
class Analyzer(
    private val models: ModelStore,
    val settings: CensorSettings,
    val spec: PipelineSpec,
    val width: Int,
    val height: Int,
    val fps: Double,
    maskWidth: Int,
    maskHeight: Int,
    storeFile: File,
) {
    private val preset = spec.speedPresets.getValue(settings.speed)
    private val segStride = if (settings.aggressive) 1 else preset.segStride
    val detStride = max(1, preset.detStride - if (settings.aggressive) 1 else 0)
    private val s01 = settings.sensitivity01
    private val skinOn = spec.thresholds.skinOn.let { it.strict + (it.sensitive - it.strict) * s01 - if (settings.aggressive) it.aggressiveBonus else 0f }
    private val nudeMin = spec.thresholds.nudenetMinScore.at(s01)
    private val personMin = spec.thresholds.personMinScore.at(s01)
    private val labels = spec.sensitiveLabels.toSet() + if (settings.aggressive) spec.aggressiveExtraLabels else emptyList()
    private val reclassifyEvery = max(detStride, (spec.gender.reclassifySeconds * fps).roundToInt())

    private val segmenter = SkinSegmenter(models)
    private val personDetector = PersonDetector(models)
    private val nudeNet = NudeNet(models)
    private val classifier = GenderClassifier(FaceDetector(models), GenderModel(models), spec.thresholds.faceMinScore, spec.thresholds.minFacePx)

    private val flow = OpticalFlow(width, height)
    private val sceneCut = SceneCutDetector(spec.tracking.sceneCutThreshold)
    private val regions = RegionTracker(if (settings.aggressive) 5 else spec.tracking.regionMaxMisses)
    private val people = PersonTracker(
        maxMisses = max(3, (spec.tracking.personLostSeconds * fps / detStride).roundToInt()),
        galleryFrames = (spec.tracking.reidGallerySeconds * fps).roundToInt(),
        reidMinSimilarity = spec.tracking.reidMinSimilarity,
    ) { GenderEstimate(spec.gender.voteFactor, spec.gender.maxLogit, spec.gender.minVotes) }
    private val fuser = TemporalFuser(
        if (settings.aggressive) spec.tracking.fuserReleaseAggressive else spec.tracking.fuserRelease,
        skinOn, spec.tracking.hysteresisOffRatio,
    )
    val store = MaskStore(storeFile, maskWidth, maskHeight)
    private val records = ArrayList<FrameRecord>()
    private var lastSkin: FloatMask? = null
    private var faceMap: FloatMask? = null
    private var sinceSeg = Int.MAX_VALUE / 2
    var processed = 0
        private set

    /** Most recent frame's state, for live previews. */
    var lastSkinBinary: BooleanArray? = null
        private set
    fun visiblePeople(): List<PersonTrack> = people.visible()
    fun currentDecision(t: PersonTrack) = censorDecision(t.gender.label(settings.threshold01), settings.target, settings.uncertainPolicy)

    /** Analyse a chunk of consecutive frames (all at width × height). */
    fun process(frames: List<RgbImage>, onFrame: (Int) -> Unit = {}) {
        val start = processed
        val detIdx = frames.indices.filter { (start + it) % detStride == 0 }
        val nude = detIdx.zip(nudeNet.detect(detIdx.map { frames[it] }, nudeMin)).toMap()
        for ((k, frame) in frames.withIndex()) {
            val idx = start + k
            val cut = sceneCut.isCut(frame)
            if (cut) {
                flow.reset()
                fuser.reset()
                regions.reset()
                people.reset(idx)
            }
            flow.update(frame)

            // skin segmentation on keyframes, optical-flow propagation in between
            val fastMotion = sinceSeg >= 1 && flow.meanMotion() > 0.012f * max(width, height)
            val fresh = lastSkin == null || cut || sinceSeg + 1 >= segStride || fastMotion
            if (fresh) {
                val seg = segmenter.segment(frame, settings.includeFace, tiled = settings.aggressive)
                var skin = seg.skin
                if (settings.aggressive) {
                    val color = ColorSkin.probability(frame)
                    val personPx = MaskOps.dilate(ByteMask(width, height, ByteArray(width * height) { if (seg.person.data[it] > 0.4f) -1 else 0 }), 4)
                    val facePx = if (!settings.includeFace) MaskOps.dilate(ByteMask(width, height, ByteArray(width * height) { if (seg.face.data[it] > 0.3f) -1 else 0 }), 7) else null
                    skin = FloatMask(width, height, FloatArray(width * height) { i ->
                        var b = color.data[i] * (if (personPx.data[i].toInt() != 0) 0.9f else 0f)
                        if (facePx != null && facePx.data[i].toInt() != 0) b = 0f
                        max(seg.skin.data[i], b)
                    })
                }
                lastSkin = skin
                faceMap = seg.face
                sinceSeg = 0
            } else {
                lastSkin = flow.warp(lastSkin!!)
                sinceSeg++
            }
            val skinBin = fuser.update(lastSkin!!, flow, fresh)

            people.predict(flow, width, height)
            regions.predict(flow, width, height)
            nude[k]?.let { dets ->
                people.update(frame, personDetector.detect(frame, personMin), idx)
                regions.update(dets.filter { it.label in labels })
                for (t in people.visible()) {
                    if (t.misses != 0) continue
                    val need = t.gender.votes < spec.gender.votesBeforeSlowdown || idx - t.lastClassified >= reclassifyEvery
                    if (!need) continue
                    t.lastClassified = idx
                    classifier.classify(frame, t.box, faceMap)?.let { (pMale, weight) -> t.gender.add(pMale, weight) }
                }
            }
            people.markFrame(frame, idx)

            val vis = people.visible()
            val p = FloatArray(vis.size * 5)
            for ((i, t) in vis.withIndex()) {
                p[i * 5] = t.id.toFloat()
                p[i * 5 + 1] = t.box.x1 / width
                p[i * 5 + 2] = t.box.y1 / height
                p[i * 5 + 3] = t.box.x2 / width
                p[i * 5 + 4] = t.box.y2 / height
            }
            val r = FloatArray(regions.tracks.size * 6)
            for ((i, rt) in regions.tracks.withIndex()) {
                var owner = -1
                var bestArea = Float.POSITIVE_INFINITY
                for (t in vis) if (t.box.contains(rt.box.cx, rt.box.cy) && t.box.area < bestArea) {
                    owner = t.id
                    bestArea = t.box.area
                }
                r[i * 6] = owner.toFloat()
                r[i * 6 + 1] = rt.box.x1 / width
                r[i * 6 + 2] = rt.box.y1 / height
                r[i * 6 + 3] = rt.box.x2 / width
                r[i * 6 + 4] = rt.box.y2 / height
                r[i * 6 + 5] = if (rt.misses == 0) 1f else max(0.6f, 1f - 0.1f * rt.misses)
            }
            records += FrameRecord(p, r)
            store.append(ByteMask(width, height, ByteArray(width * height) { if (skinBin[it]) -1 else 0 }).resize(store.width, store.height))
            lastSkinBinary = skinBin
            processed = idx + 1
            onFrame(idx)
        }
    }

    fun finish(): Analysis = Analysis(settings, spec, fps, store, records.toList(), people.all.filterValues { it.frames > 0 })

    companion object {
        /** Size with long side ≤ maxSide and even dimensions (same as desktop `scaled_size`). */
        fun scaledSize(w: Int, h: Int, maxSide: Int): Pair<Int, Int> {
            val s = minOf(1f, maxSide.toFloat() / max(w, h))
            return max(2, (w * s / 2).roundToInt() * 2) to max(2, (h * s / 2).roundToInt() * 2)
        }
    }
}

/** Progress / ETA bookkeeping (same approach as desktop `_Meter`). */
class ProgressMeter(private val total: Int, private val lo: Float, private val hi: Float, private val clock: () -> Long = System::nanoTime) {
    private val samples = ArrayDeque<Pair<Double, Float>>()
    private val t0 = clock()
    var pausedNanos = 0L

    data class Snapshot(val percent: Float, val frame: Int, val total: Int, val etaSeconds: Double?, val fps: Double, val elapsed: Double)

    fun update(done: Int): Snapshot {
        val frac = (done.toFloat() / max(total, 1)).coerceAtMost(1f)
        val overall = lo + frac * (hi - lo)
        val elapsed = (clock() - t0 - pausedNanos) / 1e9
        samples.addLast(elapsed to overall)
        if (samples.size > 60) samples.removeFirst()
        var eta: Double? = null
        var fps = 0.0
        if (samples.size >= 2) {
            val (e0, p0) = samples.first()
            val (e1, p1) = samples.last()
            if (p1 > p0 && e1 > e0) {
                val rate = (p1 - p0) / (e1 - e0)
                eta = max(0.0, (1 - overall) / rate)
                fps = (p1 - p0) / (hi - lo) * total / (e1 - e0)
            }
        }
        return Snapshot(overall * 100f, done, total, eta, if (abs(fps) < 1e-9) 0.0 else fps, elapsed)
    }
}
