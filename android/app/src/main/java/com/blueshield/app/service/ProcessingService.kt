package com.blueshield.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.blueshield.app.MainActivity
import com.blueshield.app.R
import com.blueshield.app.engine.Breadcrumbs
import com.blueshield.app.engine.Errors
import com.blueshield.app.engine.History
import com.blueshield.app.engine.JobState
import com.blueshield.app.engine.Processor
import com.blueshield.app.engine.Results
import com.blueshield.app.engine.Trimmer
import com.blueshield.app.engine.VideoMeta
import com.blueshield.app.ui.tr
import com.blueshield.core.CensorSettings
import com.blueshield.core.gender.Override
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Process-wide job state, shared by the service (writer) and the UI (reader). */
object ProcessingRepository {
    private val _state = MutableStateFlow(JobState())
    val state: StateFlow<JobState> = _state.asStateFlow()
    @Volatile var processor: Processor? = null
    internal fun publish(s: JobState) { _state.value = s }
    fun reset() { _state.value = JobState() }
}

/**
 * Foreground service so processing continues when the app is backgrounded or the screen
 * turns off (long videos), with a progress notification. All work stays on the device.
 * Every finished video is kept in the recent results (and saved to the gallery with auto-save on) here, so a
 * queue of videos finishes even when the app's screen is closed.
 */
class ProcessingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground(notification(0f, tr("מתחיל…"), null))
        val job = pendingJob
        pendingJob = null
        if (job == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        scope.launch {
            var lastNotified = 0L
            var queueLabel: String? = null
            val update: (JobState) -> Unit = { s ->
                ProcessingRepository.publish(s)
                val now = System.currentTimeMillis()
                if (now - lastNotified > 1000) {
                    lastNotified = now
                    val text = when (s.stage) {
                        JobState.Stage.DETECTING -> tr("מאתר אזורים רגישים…")
                        JobState.Stage.APPLYING -> tr("מחיל את הצנזור…")
                        JobState.Stage.ENCODING -> tr("מקודד את הסרטון הסופי…")
                        else -> tr("מנתח את הסרטון…")
                    }
                    getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, notification(s.percent, text, queueLabel))
                }
            }
            val result = try {
                val processor = ProcessingRepository.processor ?: Processor(applicationContext).also { ProcessingRepository.processor = it }
                when (job) {
                    is Job.Full -> {
                        processor.ranges = emptyMap()
                        val meta = prepare(job.meta, job.trim)
                        val r = processor.run(meta, job.settings, job.analyseOnly, update)
                        keepFinished(r, meta.name, job.meta.uri.toString(), null)
                    }
                    is Job.Rerender -> {
                        val previous = ProcessingRepository.state.value
                        processor.ranges = job.ranges
                        val r = processor.rerender(job.overrides, previous, update)
                        keepFinished(r, job.name, job.source, previous.entryId)
                    }
                    is Job.Queue -> {
                        processor.ranges = emptyMap()
                        val ids = ArrayList<Long>()
                        val failed = ArrayList<String>()
                        var last = JobState()
                        for ((i, meta) in job.metas.withIndex()) {
                            queueLabel = tr("סרטון {0} מתוך {1}", i + 1, job.metas.size)
                            val step: (JobState) -> Unit = { s -> update(s.copy(queueIndex = i + 1, queueTotal = job.metas.size, queueIds = ids.toList())) }
                            val r = keepFinished(processor.run(meta, job.settings, update = step), meta.name, meta.uri.toString(), null)
                            if (r.stage == JobState.Stage.CANCELLED) { last = r; break }
                            if (r.stage == JobState.Stage.COMPLETE) r.entryId?.let(ids::add) else failed += meta.name
                            last = r
                        }
                        last.copy(queueIndex = job.metas.size, queueTotal = job.metas.size, queueIds = ids, queueFailed = failed,
                            stage = if (last.stage == JobState.Stage.ERROR && ids.isNotEmpty()) JobState.Stage.COMPLETE else last.stage)
                    }
                }
            } catch (t: Throwable) { // setup failures must show as an error message, not kill the app
                Breadcrumbs.mark("service: failed ${Errors.describe(t)}")
                JobState(stage = JobState.Stage.ERROR, error = Errors.describe(t))
            }
            ProcessingRepository.publish(result)
            ServiceCompat.stopForeground(this@ProcessingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            when (result.stage) {
                JobState.Stage.COMPLETE -> notifyDone(this@ProcessingService, result)
                JobState.Stage.CHOOSING -> notifyMessage(this@ProcessingService, tr("האנשים בסרטון זוהו"), tr("הקישו כדי לבחור את מי לצנזר."))
                else -> Unit
            }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    /** The part to censor, cut out first when the user trimmed the video. */
    private fun prepare(meta: VideoMeta, trim: Pair<Double, Double>?): VideoMeta {
        if (trim == null || (trim.first <= 0.05 && trim.second >= meta.durationSec - 0.05)) return meta
        Breadcrumbs.mark("trim: ${trim.first}-${trim.second} s")
        val uri = Trimmer.trim(applicationContext, meta, trim.first, trim.second)
        return VideoMeta.probe(applicationContext, uri).copy(name = meta.name)
    }

    private fun keepFinished(r: JobState, name: String, source: String?, replace: Long?): JobState {
        val out = r.output ?: return r
        if (r.stage != JobState.Stage.COMPLETE) return r
        return try {
            val k = Results.keep(applicationContext, out, true, name, source, replace)
            r.copy(entryId = k.entry.id, saveError = k.saveError)
        } catch (t: Throwable) {
            Breadcrumbs.mark("keep failed: ${Errors.describe(t)}")
            r.copy(saveError = Errors.describe(t))
        }
    }

    /**
     * dataSync is the foreground-service type used everywhere. (mediaProcessing is rejected by Android 16
     * with InvalidForegroundServiceTypeException, which crashed the app.) If the system still refuses to
     * promote the service, processing continues while the app is open instead of crashing.
     */
    private fun startInForeground(n: Notification) {
        try {
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            ServiceCompat.startForeground(this, NOTIF_ID, n, type)
        } catch (t: Throwable) {
            Breadcrumbs.mark("service: could not go foreground: ${Errors.describe(t)}")
        }
    }

    /** Android 15+ limits dataSync services to a few hours per day: stop cleanly when told to. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Breadcrumbs.mark("service: system timeout (type $fgsType)")
        ProcessingRepository.processor?.cancel()
        stopSelf(startId)
    }

    private fun notification(percent: Float, text: String, sub: String?): Notification {
        ensureChannel(this)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(tr("מצנזרים את הסרטון שלך"))
            .setContentText(text)
            .setSubText(sub)
            .setProgress(100, percent.toInt(), percent <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent(this, null))
            .build()
    }

    override fun onDestroy() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onDestroy()
    }

    sealed interface Job {
        /** [trim]: censor only this part (seconds); [analyseOnly]: stop once the people are found, to choose whom to censor. */
        class Full(val meta: VideoMeta, val settings: CensorSettings, val analyseOnly: Boolean = false, val trim: Pair<Double, Double>? = null) : Job
        /** [ranges]: people censored only within a time span (seconds). */
        class Rerender(val overrides: Map<Int, Override>, val ranges: Map<Int, ClosedFloatingPointRange<Double>> = emptyMap(),
                       val name: String = "video.mp4", val source: String? = null) : Job
        /** Several videos, one after another, with the same settings. */
        class Queue(val metas: List<VideoMeta>, val settings: CensorSettings) : Job
    }

    companion object {
        private const val CHANNEL = "processing"
        private const val DONE_CHANNEL = "done"
        private const val NOTIF_ID = 1
        private const val NOTIF_DONE_ID = 2
        const val ACTION_OPEN_ENTRY = "com.blueshield.app.OPEN_ENTRY"
        const val ACTION_SHARE_ENTRY = "com.blueshield.app.SHARE_ENTRY"
        const val EXTRA_ENTRIES = "entries"
        @Volatile private var pendingJob: Job? = null

        fun start(context: Context, job: Job) {
            pendingJob = job
            try {
                context.startForegroundService(Intent(context, ProcessingService::class.java))
            } catch (t: Throwable) { // e.g. the app is not allowed to start a foreground service right now
                Breadcrumbs.mark("service: start refused: ${Errors.describe(t)}")
                pendingJob = null
                ProcessingRepository.publish(JobState(stage = JobState.Stage.ERROR, error = tr("לא ניתן להתחיל את העיבוד: {0}", t.message)))
            }
        }

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("עיבוד סרטונים"), NotificationManager.IMPORTANCE_LOW))
            }
            if (nm.getNotificationChannel(DONE_CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(DONE_CHANNEL, tr("העיבוד הסתיים"), NotificationManager.IMPORTANCE_DEFAULT))
            }
        }

        private fun openIntent(context: Context, entries: LongArray?, share: Boolean = false): PendingIntent = PendingIntent.getActivity(
            context, if (share) 2 else if (entries != null) 1 else 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).apply {
                if (entries != null) {
                    action = if (share) ACTION_SHARE_ENTRY else ACTION_OPEN_ENTRY
                    putExtra(EXTRA_ENTRIES, entries)
                }
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** "Done", with the result's picture and buttons to open or share it. */
        fun notifyDone(context: Context, s: JobState) {
            val ids = (if (s.queueIds.isNotEmpty()) s.queueIds else listOfNotNull(s.entryId)).toLongArray()
            notifyResults(
                context, ids,
                if (ids.size > 1) tr("{0} סרטונים צונזרו", ids.size) else tr("הצנזור הושלם"),
                tr("הקישו כדי לצפות בסרטון, לשמור או לשתף אותו."),
            )
        }

        /** The same for any finished results (a batch of photos that ended while the app was in the background). */
        fun notifyResults(context: Context, ids: LongArray, title: String, text: String) {
            ensureChannel(context)
            val thumb = ids.firstOrNull()?.let { id -> History.list(context).firstOrNull { it.id == id } }?.let { History.decode(it.thumb, 400) }
            val b = NotificationCompat.Builder(context, DONE_CHANNEL)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(openIntent(context, ids.takeIf { it.isNotEmpty() }))
            if (thumb != null) b.setLargeIcon(thumb).setStyle(NotificationCompat.BigPictureStyle().bigPicture(thumb))
            if (ids.isNotEmpty()) {
                b.addAction(0, tr("פתיחה"), openIntent(context, ids))
                b.addAction(0, tr("שיתוף"), openIntent(context, ids, share = true))
            }
            runCatching { context.getSystemService(NotificationManager::class.java)?.notify(NOTIF_DONE_ID, b.build()) }
        }

        fun notifyMessage(context: Context, title: String, text: String) {
            ensureChannel(context)
            val n = NotificationCompat.Builder(context, DONE_CHANNEL)
                .setSmallIcon(R.drawable.ic_shield).setContentTitle(title).setContentText(text).setAutoCancel(true)
                .setContentIntent(openIntent(context, null)).build()
            runCatching { context.getSystemService(NotificationManager::class.java)?.notify(NOTIF_DONE_ID, n) }
        }
    }
}
