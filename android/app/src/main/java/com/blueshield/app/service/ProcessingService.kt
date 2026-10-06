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
import com.blueshield.app.engine.JobState
import com.blueshield.app.engine.Processor
import com.blueshield.app.engine.VideoMeta
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
 */
class ProcessingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground(notification(0f, getString(R.string.notif_starting)))
        val job = pendingJob
        pendingJob = null
        if (job == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        scope.launch {
            var lastNotified = 0L
            val update: (JobState) -> Unit = { s ->
                ProcessingRepository.publish(s)
                val now = System.currentTimeMillis()
                if (now - lastNotified > 1000) {
                    lastNotified = now
                    val text = when (s.stage) {
                        JobState.Stage.DETECTING -> getString(R.string.stage_detecting)
                        JobState.Stage.APPLYING -> getString(R.string.stage_applying)
                        JobState.Stage.ENCODING -> getString(R.string.stage_encoding)
                        else -> getString(R.string.stage_analyzing)
                    }
                    getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, notification(s.percent, text))
                }
            }
            val result = try {
                val processor = ProcessingRepository.processor ?: Processor(applicationContext).also { ProcessingRepository.processor = it }
                when (job) {
                    is Job.Full -> processor.run(job.meta, job.settings, update)
                    is Job.Rerender -> processor.rerender(job.overrides, ProcessingRepository.state.value, update)
                }
            } catch (t: Throwable) { // setup failures must show as an error message, not kill the app
                Breadcrumbs.mark("service: failed ${t.javaClass.simpleName}: ${t.message}")
                JobState(stage = JobState.Stage.ERROR, error = "${t.javaClass.simpleName}: ${t.message}")
            }
            ProcessingRepository.publish(result)
            ServiceCompat.stopForeground(this@ProcessingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            if (result.stage == JobState.Stage.COMPLETE) {
                getSystemService(NotificationManager::class.java)?.notify(NOTIF_DONE_ID, doneNotification())
            }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    private fun contentIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notification(percent: Float, text: String): Notification {
        ensureChannel(this)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setProgress(100, percent.toInt(), percent <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .build()
    }

    private fun doneNotification(): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_shield)
        .setContentTitle(getString(R.string.notif_done))
        .setContentText(getString(R.string.notif_done_text))
        .setAutoCancel(true)
        .setContentIntent(contentIntent())
        .build()

    override fun onDestroy() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onDestroy()
    }

    sealed interface Job {
        class Full(val meta: VideoMeta, val settings: CensorSettings) : Job
        class Rerender(val overrides: Map<Int, Override>) : Job
    }

    companion object {
        private const val CHANNEL = "processing"
        private const val NOTIF_ID = 1
        private const val NOTIF_DONE_ID = 2
        @Volatile private var pendingJob: Job? = null

        fun start(context: Context, job: Job) {
            pendingJob = job
            context.startForegroundService(Intent(context, ProcessingService::class.java))
        }

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
            }
        }
    }
}
