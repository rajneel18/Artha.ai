package ai.artha.app

import ai.artha.core.Api
import ai.artha.core.Engine
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** App singleton: one engine, one AI model, shared by the screen and background work. */
class Artha : Application() {
    lateinit var api: Api
        private set
    lateinit var model: QwenModel
        private set

    override fun onCreate() {
        super.onCreate()
        model = QwenModel(this)
        api = Api(Engine(AndroidDb(this)), model)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_DETECTED, "Payments detected", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "When Artha finds payments in your bank SMS for you to confirm"
        })
        nm.createNotificationChannel(NotificationChannel(CH_WORK, "Reading messages", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Progress while the on-device AI reads messages or downloads"
        })
    }

    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); if (level >= TRIM_MEMORY_BACKGROUND) model.release() }

    fun notifyDetected(count: Int) {
        if (api.engine.settings()["notify_logged"] == false) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = if (count == 1) "1 payment detected. Tap to review" else "$count payments detected. Tap to review"
        NotificationManagerCompat.from(this).notify(7, NotificationCompat.Builder(this, CH_DETECTED).setSmallIcon(R.drawable.ic_stat_rupee)
            .setContentTitle("Artha").setContentText(text).setContentIntent(open).setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
    }

    companion object {
        const val CH_DETECTED = "detected"
        const val CH_WORK = "work"
        fun of(c: Context) = c.applicationContext as Artha
    }
}
