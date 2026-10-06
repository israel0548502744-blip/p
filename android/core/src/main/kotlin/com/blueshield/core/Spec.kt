package com.blueshield.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Mirror of shared/pipeline.json — the constants both the desktop and Android pipelines use. */
@Serializable
data class PipelineSpec(
    val version: Int,
    val defaults: CensorSettings,
    val thresholds: Thresholds,
    val gender: GenderSpec,
    val tracking: TrackingSpec,
    @SerialName("speed_presets") val speedPresets: Map<String, SpeedPreset>,
    @SerialName("sensitive_labels") val sensitiveLabels: List<String>,
    @SerialName("aggressive_extra_labels") val aggressiveExtraLabels: List<String>,
    @SerialName("ownership_box_pad") val ownershipBoxPad: Float,
    /** A skin blob up to this many box sizes outside a person's box still belongs to them (mirrors Composer.OWNER_REACH). */
    @SerialName("owner_reach") val ownerReach: Float,
    @SerialName("unassigned_min_area_with_people") val unassignedMinAreaWithPeople: Float,
    /** Unattributed skin blobs smaller than this fraction of the frame are noise, never censored. */
    @SerialName("unassigned_min_area") val unassignedMinArea: Float,
    @SerialName("analysis_max_side") val analysisMaxSide: Int,
    @SerialName("mask_max_side") val maskMaxSide: Int,
    /** Per-person region-of-interest segmentation parameters. */
    val roi: Roi,
    /** Edge-aware refinement of the skin probability (guided filter). */
    val refine: Refine,
    /** Face / neck / neckline rules and speck removal (sizes relative to the face box). */
    val neckline: Neckline,
) {
    @Serializable
    data class Refine(
        /** Window radius as a fraction of (width + height). */
        val radius: Float,
        val eps: Float,
    )

    @Serializable
    data class Neckline(
        @SerialName("band_half_width") val bandHalfWidth: Float,
        @SerialName("band_height") val bandHeight: Float,
        @SerialName("probe_half_width") val probeHalfWidth: Float,
        @SerialName("probe_height") val probeHeight: Float,
        @SerialName("cleavage_min_fill") val cleavageMinFill: Float,
        @SerialName("cleavage_start") val cleavageStart: Float,
        @SerialName("speck_person_frac") val speckPersonFrac: Float,
        @SerialName("speck_frame_frac") val speckFrameFrac: Float,
    )

    @Serializable
    data class Roi(
        @SerialName("side_scale") val sideScale: Float,
        @SerialName("paste_pad") val pastePad: Float,
        @SerialName("min_side_px") val minSidePx: Int,
        /** A crop this close to the whole frame adds nothing over a fresh full-frame pass. */
        @SerialName("max_frame_ratio") val maxFrameRatio: Float,
        /** Whole-frame segmentation on every Nth detection round (people get their own crop every frame). */
        @SerialName("full_every_det") val fullEveryDet: Int,
    )

    @Serializable
    data class SkinColor(
        @SerialName("max_blue_over_red") val maxBlueOverRed: Int,
        @SerialName("min_luma") val minLuma: Int,
    )

    @Serializable
    data class Range(val strict: Float, val sensitive: Float) {
        /** Linear interpolation by sensitivity in 0..1. */
        fun at(sensitivity: Float) = strict + (sensitive - strict) * sensitivity
    }

    @Serializable
    data class SkinRange(val strict: Float, val sensitive: Float, @SerialName("aggressive_bonus") val aggressiveBonus: Float)

    @Serializable
    data class Thresholds(
        @SerialName("skin_on") val skinOn: SkinRange,
        @SerialName("nudenet_min_score") val nudenetMinScore: Range,
        @SerialName("person_min_score") val personMinScore: Range,
        @SerialName("face_min_score") val faceMinScore: Float,
        @SerialName("min_face_px") val minFacePx: Float,
        /** Facial-skin probability above which a pixel is never body skin (unless faces are censored). */
        @SerialName("face_exclusion") val faceExclusion: Float,
        /** Colour sanity check: skin is never clearly bluer than red, nor almost black. */
        @SerialName("skin_color") val skinColor: SkinColor,
        /** Segmenter "person" probability a skin pixel must (nearly) touch to count. */
        @SerialName("person_gate") val personGate: Float,
        /** Away from every person box, a "person" blob smaller than this fraction of the frame is an object. */
        @SerialName("min_body_area") val minBodyArea: Float,
    )

    @Serializable
    data class GenderSpec(
        @SerialName("vote_factor") val voteFactor: Double,
        @SerialName("max_logit") val maxLogit: Double,
        @SerialName("min_votes") val minVotes: Int,
        @SerialName("min_weight") val minWeight: Double,
        @SerialName("votes_before_slowdown") val votesBeforeSlowdown: Int,
        @SerialName("reclassify_seconds") val reclassifySeconds: Double,
        @SerialName("male_min_confidence") val maleMinConfidence: Double,
        @SerialName("adult_min_age") val adultMinAge: Double,
        @SerialName("min_age_votes") val minAgeVotes: Int,
    )

    @Serializable
    data class TrackingSpec(
        @SerialName("fuser_memory") val fuserMemory: Float,
        @SerialName("fuser_lift") val fuserLift: Float,
        @SerialName("fuser_lift_aggressive") val fuserLiftAggressive: Float,
        @SerialName("hysteresis_off_ratio") val hysteresisOffRatio: Float,
        @SerialName("person_lost_seconds") val personLostSeconds: Double,
        @SerialName("reid_gallery_seconds") val reidGallerySeconds: Double,
        @SerialName("reid_min_similarity") val reidMinSimilarity: Float,
        @SerialName("region_max_misses") val regionMaxMisses: Int,
        @SerialName("scene_cut_threshold") val sceneCutThreshold: Float,
    )

    @Serializable
    data class SpeedPreset(@SerialName("seg_stride") val segStride: Int, @SerialName("det_stride") val detStride: Int)

    /** A fresh per-person gender/age evidence accumulator with the shared constants. */
    fun newGenderEstimate() = com.blueshield.core.gender.GenderEstimate(
        gender.voteFactor, gender.maxLogit, gender.minVotes, gender.minWeight, gender.maleMinConfidence, gender.adultMinAge, gender.minAgeVotes,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): PipelineSpec = json.decodeFromString(serializer(), text)

        /** The spec bundled as a classpath resource (copied from shared/pipeline.json at build time). */
        val bundled: PipelineSpec by lazy {
            val stream = PipelineSpec::class.java.getResourceAsStream("/blueshield/pipeline.json")
                ?: error("pipeline.json resource missing")
            parse(stream.bufferedReader().use { it.readText() })
        }
    }
}
