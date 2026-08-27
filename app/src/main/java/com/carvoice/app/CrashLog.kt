package com.carvoice.app

import android.content.Context

/** Mirrors the Windows app's crash_log.py: a permanent, persistent record
 * of the last crash/serious error, readable from Settings. Two ways
 * things land here:
 *   1. record() - called from inside a try/catch that already prevented a
 *      crash (e.g. a bad song file, a revoked permission) - the app kept
 *      running, but this is still worth keeping so a pattern of repeated
 *      errors is visible instead of silently swallowed forever.
 *   2. install() - a global uncaught-exception handler, for whatever
 *      wasn't anticipated by any try/catch anywhere. This does NOT stop
 *      the crash (Android's normal crash handling still happens after -
 *      trying to fully suppress an unknown/unanticipated failure is worse
 *      than just recording it and letting the OS do its normal thing) -
 *      it just guarantees that if it happens again, Settings shows the
 *      actual error and stack trace instead of nothing at all. */
object CrashLog {
    private const val FILE = "car_voice_crash_log"
    private const val KEY_LAST = "last_entry"

    fun record(context: Context, message: String) {
        try {
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit().putString(KEY_LAST, "[$stamp] $message").apply()
        } catch (e: Exception) {
            // Logging itself must never be what crashes the app.
        }
    }

    fun lastEntry(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_LAST, null)

    fun clear(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(KEY_LAST).apply()
    }

    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val writer = java.io.StringWriter()
                throwable.printStackTrace(java.io.PrintWriter(writer))
                record(appContext, "UNCAUGHT on ${thread.name}: ${writer.toString().take(4000)}")
            } catch (e: Exception) {
                // Even the crash handler itself must not throw.
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
