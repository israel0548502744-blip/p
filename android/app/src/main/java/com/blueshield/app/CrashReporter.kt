package com.blueshield.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.blueshield.app.engine.Breadcrumbs
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Makes crashes diagnosable on a phone without a computer: Java crashes are written to a file,
 * native crashes / ANRs / kills are read back from the system (ApplicationExitInfo), and the next
 * launch offers the report for copying or sharing.
 */
object CrashReporter {
    private const val PREFS = "crash_reporter"
    private const val KEY_SEEN = "last_seen_exit_ts"

    fun install(context: Context) {
        Breadcrumbs.init(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                File(context.filesDir, "last_crash.txt").writeText(
                    "Java crash on thread '${thread.name}':\n$sw\n--- last steps ---\n${Breadcrumbs.tail()}",
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** A report about the previous run's crash, or null when it ended normally. */
    fun pending(context: Context): String? {
        val sb = StringBuilder()
        val javaCrash = File(context.filesDir, "last_crash.txt")
        if (javaCrash.exists()) sb.append(runCatching { javaCrash.readText() }.getOrDefault("")).append('\n')
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val seen = prefs.getLong(KEY_SEEN, 0L)
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val bad = setOf(
                    ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR,
                    ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, ApplicationExitInfo.REASON_OTHER,
                )
                am.getHistoricalProcessExitReasons(context.packageName, 0, 6)
                    .firstOrNull { it.timestamp > seen && it.reason in bad }
                    ?.let { info ->
                        sb.append("System exit info: ${reasonName(info.reason)} (status ${info.status}), importance ${info.importance}\n")
                        sb.append("Description: ${info.description}\n")
                        sb.append("--- last steps ---\n${Breadcrumbs.tail()}\n")
                    }
            }
        }
        if (sb.isBlank()) return null
        return "BlueShield ${BuildInfo.version} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n\n$sb".trim()
    }

    fun markSeen(context: Context) {
        File(context.filesDir, "last_crash.txt").delete()
        File(context.filesDir, "breadcrumbs.log").delete()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_SEEN, System.currentTimeMillis()).apply()
    }

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH (Java exception)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE (native library crash)"
        ApplicationExitInfo.REASON_ANR -> "ANR (not responding)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (killed for memory)"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        else -> "OTHER ($r)"
    }
}

object BuildInfo { const val version = "1.1.0" }

class BlueShieldApp : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
