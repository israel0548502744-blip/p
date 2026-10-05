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
    /** Unattributed skin blobs smaller than this fraction of the frame are noise, never censored. */
    @SerialName("unassigned_min_area") val unassignedMinArea: Float,
    @SerialName("analysis_max_side") val analysisMaxSide: Int,
    @SerialName("mask_max_side") val maskMaxSide: Int,
) {
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
    )

    @Serializable
    data class GenderSpec(
        @SerialName("vote_factor") val voteFactor: Double,
        @SerialName("max_logit") val maxLogit: Double,
        @SerialName("min_votes") val minVotes: Int,
        @SerialName("min_weight") val minWeight: Double,
        @SerialName("votes_before_slowdown") val votesBeforeSlowdown: Int,
        @SerialName("reclassify_seconds") val reclassifySeconds: Double,
    )

    @Serializable
    data class TrackingSpec(
        @SerialName("fuser_release") val fuserRelease: Float,
        @SerialName("fuser_release_aggressive") val fuserReleaseAggressive: Float,
        @SerialName("hysteresis_off_ratio") val hysteresisOffRatio: Float,
        @SerialName("person_lost_seconds") val personLostSeconds: Double,
        @SerialName("reid_gallery_seconds") val reidGallerySeconds: Double,
        @SerialName("reid_min_similarity") val reidMinSimilarity: Float,
        @SerialName("region_max_misses") val regionMaxMisses: Int,
        @SerialName("scene_cut_threshold") val sceneCutThreshold: Float,
    )

    @Serializable
    data class SpeedPreset(@SerialName("seg_stride") val segStride: Int, @SerialName("det_stride") val detStride: Int)

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
