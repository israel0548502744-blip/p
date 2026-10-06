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
    /** Calling someone male needs at least this confidence, whatever the user's threshold (missing a woman costs more). */
    private val maleMinConfidence: Double = 0.0,
    /**
     * Median estimated age below this = a child (not censored when only women are). Only clearly small children:
     * the face model puts young adult women anywhere from ~10 to ~16, so a higher cut-off would leave real women
     * uncensored. Older girls count as adults (the safe side); "Don't" in the people list keeps them.
     */
    private val childMaxAge: Double = 7.0,
    /** Age observations needed before a person can be called a child; until then they count as adults. */
    private val minAgeVotes: Int = 6,
) {
    var logit = 0.0
        private set
    var votes = 0
        private set
    var weight = 0.0
        private set

    private val ages = ArrayList<Float>()

    fun addAge(age: Float) {
        if (age.isFinite()) ages += age
    }

    val ageVotes: Int get() = ages.size

    /** Median of the age estimates (robust to the odd bad crop), or null without any. */
    val ageMedian: Double?
        get() {
            if (ages.isEmpty()) return null
            val s = ages.sorted()
            val n = s.size
            return if (n % 2 == 1) s[n / 2].toDouble() else (s[n / 2 - 1] + s[n / 2]) / 2.0
        }

    /** Clearly a child: enough age observations with a median below the adult age. Unknown age counts as adult. */
    val isChild: Boolean get() = ages.size >= minAgeVotes && (ageMedian ?: Double.MAX_VALUE) < childMaxAge

    fun add(pMale: Float, weight: Float = 1f) {
        val p = pMale.toDouble().coerceIn(0.02, 0.98)
        logit = (logit + voteFactor * weight * ln((1 - p) / p)).coerceIn(-maxLogit, maxLogit)
        votes++
        this.weight += weight
    }

    /** Merge the evidence of a duplicate track of the same person. */
    fun absorb(other: GenderEstimate) {
        logit = (logit + other.logit).coerceIn(-maxLogit, maxLogit)
        votes += other.votes
        weight += other.weight
        ages += other.ages
    }

    val pFemale: Double get() = 1.0 / (1.0 + exp(-logit))
    val confidence: Double get() = max(pFemale, 1 - pFemale)

    fun label(threshold: Double): Label = when {
        votes < minVotes || weight < minWeight -> Label.UNCERTAIN
        pFemale >= threshold -> Label.FEMALE
        pFemale <= 1 - max(threshold, maleMinConfidence) -> Label.MALE
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

/**
 * Should this person be censored? target: "female" (adult women only — girls are not censored) | "everyone";
 * uncertainPolicy: "censor"|"keep".
 */
fun censorDecision(
    label: GenderEstimate.Label, target: String, uncertainPolicy: String, override: Override = Override.AUTO, child: Boolean = false,
): Boolean =
    when (override) {
        Override.CENSOR -> true
        Override.KEEP -> false
        Override.AUTO -> when {
            target == "everyone" -> true
            child -> false
            label == GenderEstimate.Label.FEMALE -> true
            label == GenderEstimate.Label.MALE -> false
            else -> uncertainPolicy == "censor"
        }
    }
