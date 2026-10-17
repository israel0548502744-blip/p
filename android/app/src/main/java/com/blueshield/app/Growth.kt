package com.blueshield.app

import android.app.Activity
import android.content.Context
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Which features are free and which will later be part of "BlueShield Pro". During the growth phase everything is
 * open to everyone ([FREE_FOR_ALL]); the checks already sit where a paid feature starts, so turning on a paid plan
 * later (Google Play Billing, which also works without an internet permission) only changes [has].
 */
object Entitlements {
    enum class Feature {
        /** More than [FREE_BATCH] photos at once. */
        BIG_BATCH,
        /** Several videos in a queue. */
        VIDEO_QUEUE,
        /** Videos longer than [FREE_VIDEO_SECONDS]. */
        LONG_VIDEO,
        /** The hand brush. */
        BRUSH,
    }

    const val FREE_FOR_ALL = true
    const val FREE_BATCH = 10
    const val FREE_VIDEO_SECONDS = 60

    /** Set by the billing layer once it exists (a bought "Pro"). */
    @Volatile var pro = false

    fun has(f: Feature): Boolean = FREE_FOR_ALL || pro
}

/**
 * Growth without collecting anything: after a few results that went well, Google Play's own rating card is offered
 * once (it is shown by the Play Store app; installs from outside Play just skip it).
 */
object Growth {
    private const val PREFS = "growth"

    /** A result was saved or shared. */
    fun recordSuccess(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putInt("successes", p.getInt("successes", 0) + 1).apply()
    }

    fun shouldAskForRating(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getInt("successes", 0) >= 3 && !p.getBoolean("asked", false)
    }

    fun askForRating(activity: Activity) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("asked", true).apply()
        runCatching {
            val manager = ReviewManagerFactory.create(activity)
            manager.requestReviewFlow().addOnCompleteListener { t ->
                if (t.isSuccessful) runCatching { manager.launchReviewFlow(activity, t.result) }
            }
        }
    }
}
