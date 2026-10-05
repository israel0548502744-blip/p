package com.blueshield.core.gender

import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.max

/**
 * Stage 2 — gender classification state for one tracked person.
 *
 * Each usable face observation adds a weighted log-odds vote; a decision is only
 * made once enough votes agree beyond the user's confidence threshold. Otherwise
 * the person is "uncertain" and follows the fallback policy. (Same maths as the
 * desktop `GenderEstimate`; constants from shared/pipeline.json.)
 */
class GenderEstimate(
    private val voteFactor: Double,
    private val maxLogit: Double,
    private val minVotes: Int,
    private val minWeight: Double = 0.0,
) {
    var logit = 0.0
        private set
    var votes = 0
        private set
    var weight = 0.0
        private set

    fun add(pMale: Float, weight: Float = 1f) {
        val p = pMale.toDouble().coerceIn(0.02, 0.98)
        logit = (logit + voteFactor * weight * ln((1 - p) / p)).coerceIn(-maxLogit, maxLogit)
        votes++
        this.weight += weight
    }

    val pFemale: Double get() = 1.0 / (1.0 + exp(-logit))
    val confidence: Double get() = max(pFemale, 1 - pFemale)

    fun label(threshold: Double): Label = when {
        votes < minVotes || weight < minWeight -> Label.UNCERTAIN
        pFemale >= threshold -> Label.FEMALE
        pFemale <= 1 - threshold -> Label.MALE
        else -> Label.UNCERTAIN
    }

    enum class Label(val key: String) { FEMALE("female"), MALE("male"), UNCERTAIN("uncertain") }
}

enum class Override(val key: String) {
    AUTO("auto"), CENSOR("censor"), KEEP("keep");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/** Should this person be censored? target: "female"|"everyone"; uncertainPolicy: "censor"|"keep". */
fun censorDecision(label: GenderEstimate.Label, target: String, uncertainPolicy: String, override: Override = Override.AUTO): Boolean =
    when (override) {
        Override.CENSOR -> true
        Override.KEEP -> false
        Override.AUTO -> when {
            target == "everyone" -> true
            label == GenderEstimate.Label.FEMALE -> true
            label == GenderEstimate.Label.MALE -> false
            else -> uncertainPolicy == "censor"
        }
    }
