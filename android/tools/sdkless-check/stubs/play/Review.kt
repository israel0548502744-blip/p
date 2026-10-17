@file:Suppress("unused", "UNUSED_PARAMETER")
package com.google.android.play.core.review

import android.app.Activity
import android.content.Context

abstract class ReviewInfo
interface ReviewManager {
    fun requestReviewFlow(): com.google.android.gms.tasks.Task<ReviewInfo>
    fun launchReviewFlow(activity: Activity, info: ReviewInfo): com.google.android.gms.tasks.Task<Void>
}
object ReviewManagerFactory { @JvmStatic fun create(context: Context): ReviewManager = error("stub") }
