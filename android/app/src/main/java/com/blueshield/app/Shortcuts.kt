package com.blueshield.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import com.blueshield.app.ui.tr

/** Opens the app straight at the photo or video picker (widget buttons, quick settings tile). */
internal fun pickIntent(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getActivity(
    context, code,
    Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)

/** Home screen widget: "photo" and "video" buttons. */
class BlueShieldWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget).apply {
            setTextViewText(R.id.widget_photo, tr("תמונה"))
            setTextViewText(R.id.widget_video, tr("סרטון"))
            setOnClickPendingIntent(R.id.widget_photo, pickIntent(context, MainActivity.ACTION_PICK_PHOTO, 10))
            setOnClickPendingIntent(R.id.widget_video, pickIntent(context, MainActivity.ACTION_PICK_VIDEO, 11))
        }
        manager.updateAppWidget(ids, views)
    }
}

/** Quick settings tile: one tap opens the photo picker. */
class BlueShieldTile : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            label = "BlueShield"
            if (Build.VERSION.SDK_INT >= 29) subtitle = tr("צנזור תמונה")
            updateTile()
        }
    }

    override fun onClick() {
        val pi = pickIntent(this, MainActivity.ACTION_PICK_PHOTO, 12)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(pi)
        else @Suppress("DEPRECATION") startActivityAndCollapse(
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_PICK_PHOTO).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
