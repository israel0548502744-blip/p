@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.core.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context

class NotificationCompat {
    class Builder(context: Context, channelId: String) {
        fun setSmallIcon(icon: Int): Builder = this
        fun setContentTitle(title: CharSequence?): Builder = this
        fun setContentText(text: CharSequence?): Builder = this
        fun setProgress(max: Int, progress: Int, indeterminate: Boolean): Builder = this
        fun setOngoing(ongoing: Boolean): Builder = this
        fun setOnlyAlertOnce(only: Boolean): Builder = this
        fun setAutoCancel(auto: Boolean): Builder = this
        fun setContentIntent(intent: PendingIntent?): Builder = this
        fun build(): Notification = error("stub")
    }
}
object ServiceCompat {
    const val STOP_FOREGROUND_REMOVE = 1
    @JvmStatic fun startForeground(service: Service, id: Int, notification: Notification, foregroundServiceType: Int) {}
    @JvmStatic fun stopForeground(service: Service, flags: Int) {}
}
