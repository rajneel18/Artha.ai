package ai.artha.app

import android.content.Context
import java.io.File

/**
 * Keeps one bad step from crashing the app forever.
 * - Java/Kotlin crashes are written to a report you can copy from Settings.
 * - Native crashes (inside the AI library) can't be caught, so risky steps leave a marker first; if the app
 *   dies during the step, the marker is still there at the next start and that step is switched off for good.
 */
object CrashGuard {
    private fun prefs(c: Context) = c.getSharedPreferences("crash_guard", Context.MODE_PRIVATE)
    private fun reportFile(c: Context) = File(c.filesDir, "last_crash.txt")

    fun install(c: Context) {
        val app = c.applicationContext
        // A marker left behind means the app died inside that step last time: disable it.
        // Older versions could switch the CPU path off after the phone simply stopped the app mid-read. Undo that.
        prefs(app).edit().remove("off_cpu").apply()
        prefs(app).getString("in_step", null)?.takeIf { it != "cpu" }?.let { step ->
            prefs(app).edit().putBoolean("off_$step", true).remove("in_step").apply()
            save(app, "The app stopped while running \"$step\" in the AI library. PAISAve switched to a safer mode for it.")
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { save(app, describe(e)) } catch (_: Throwable) {}
            previous?.uncaughtException(t, e)
        }
    }

    fun isOff(c: Context, step: String) = prefs(c).getBoolean("off_$step", false)

    /** Runs a step that might crash natively. If it does, the step is disabled at the next start. */
    fun <T> risky(c: Context, step: String, block: () -> T): T {
        prefs(c).edit().putString("in_step", step).commit()
        try { return block() } finally { prefs(c).edit().remove("in_step").commit() }
    }

    fun describe(e: Throwable): String = buildString {
        append("PAISAve ").append(android.os.Build.MODEL).append(" Android ").append(android.os.Build.VERSION.RELEASE).append('\n')
        append(e.toString()).append('\n')
        e.stackTrace.take(25).forEach { append("  at ").append(it).append('\n') }
        var cause = e.cause
        while (cause != null) { append("Caused by: ").append(cause).append('\n'); cause.stackTrace.take(8).forEach { append("  at ").append(it).append('\n') }; cause = cause.cause }
    }

    fun save(c: Context, text: String) { reportFile(c).writeText(java.time.LocalDateTime.now().toString() + "\n" + text) }
    fun report(c: Context): String? = reportFile(c).takeIf { it.exists() }?.readText()
    fun clear(c: Context) { reportFile(c).delete() }
}
