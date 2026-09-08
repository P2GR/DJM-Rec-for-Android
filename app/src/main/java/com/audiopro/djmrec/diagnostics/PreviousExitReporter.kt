package com.audiopro.djmrec.diagnostics

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build

/** Android 11+ exposes native crash/ANR exit metadata without reading audio or trace files. */
internal object PreviousExitReporter {
    fun check(app: Application, enabled: Boolean) {
        val prefs = app.getSharedPreferences("settings", 0)
        val since = prefs.getLong("diagnostics_last_launch", Long.MAX_VALUE)
        val previousEnabled = prefs.getBoolean("diagnostics_previous_launch_enabled", false)
        prefs.edit().putLong("diagnostics_last_launch", System.currentTimeMillis())
            .putBoolean("diagnostics_previous_launch_enabled", enabled).apply()
        if (!enabled || !previousEnabled || Build.VERSION.SDK_INT < 30) return
        runCatching {
            val manager = app.getSystemService(ActivityManager::class.java)
            manager.getHistoricalProcessExitReasons(app.packageName, 0, 8)
                .filter { it.timestamp >= since && it.processName == app.packageName }
                .firstOrNull { it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || it.reason == ApplicationExitInfo.REASON_ANR }
                ?.let { exit -> RemoteDiagnostics.issue("Previous process failure",
                    "reason=${exit.reason} status=${exit.status} timestamp=${exit.timestamp} importance=${exit.importance}; native/ANR metadata only") }
        }
    }
}
