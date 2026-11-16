package ai.artha.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** The evening "log your day" nudge. Fires daily at your chosen hour, only if nothing was logged today. */
object Reminder {
    fun schedule(c: Context) {
        val app = Artha.of(c)
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(c, 77, Intent(c, ReminderReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.cancel(pi)
        val s = app.api.engine.settings()
        if (s["reminder_on"] == false) return
        val hour = ((s["reminder_hour"] as? Number)?.toInt() ?: 21).coerceIn(0, 23)
        var next = LocalDate.now().atTime(hour, 0)
        if (!next.isAfter(LocalDateTime.now())) next = next.plusDays(1)
        val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, at, AlarmManager.INTERVAL_DAY, pi)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val app = Artha.of(c)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) { Reminder.schedule(c); return }
        val streak = app.api.extras.streak()
        if (streak["today_done"] == true) return
        if (c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val current = (streak["current"] as? Number)?.toInt() ?: 0
        val open = PendingIntent.getActivity(c, 78, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        NotificationManagerCompat.from(c).notify(9, NotificationCompat.Builder(c, Artha.CH_DETECTED).setSmallIcon(R.drawable.ic_stat_rupee)
            .setContentTitle(if (current > 0) "Keep your $current-day streak" else "Anything to log today?")
            .setContentText("Log today's spends in ten seconds, or mark a zero-spend day.")
            .setContentIntent(open).setAutoCancel(true).build())
    }
}
