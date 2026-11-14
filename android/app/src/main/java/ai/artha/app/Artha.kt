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
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.text.NumberFormat
import java.util.Locale

/** App singleton: one engine + API shared by the UI and background capture. */
class Artha : Application() {
    lateinit var api: Api
        private set

    /** Secrets (the Claude API key) live in Android Keystore-backed encrypted preferences. */
    val secrets by lazy {
        EncryptedSharedPreferences.create(
            this, "artha_secrets",
            MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun onCreate() {
        super.onCreate()
        api = Api(Engine(AndroidDb(this)), ClaudeAi { secrets.getString("claude_api_key", null) })
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_QUESTION, "Needs your input", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "When Artha isn't sure about a payment it captured"
        })
        nm.createNotificationChannel(NotificationChannel(CH_LOGGED, "Payments logged", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Quiet confirmation for each payment captured automatically"
        })
    }

    /** Runs background capture and tells the user only what matters. */
    fun capture(text: String, source: String) {
        val s = api.engine.settings()
        val key = if (source == "sms") "auto_capture_sms" else "auto_capture_notifications"
        if (s[key] == false) return
        val tx = synchronized(this) { api.captureAutomatic(text, source) } ?: return
        val amount = "₹" + NumberFormat.getNumberInstance(Locale("en", "IN")).format(tx["amount"] as Double)
        val merchant = tx["merchant"] as String
        val question = tx["question"] as String?
        when {
            question != null -> notify(CH_QUESTION, "$amount · $merchant", question, (tx["id"] as Long).toInt())
            s["notify_logged"] == true -> notify(CH_LOGGED, "$amount · $merchant", "Logged as ${tx["category"]}", (tx["id"] as Long).toInt())
        }
    }

    private fun notify(channel: String, title: String, text: String, id: Int) {
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(this, id, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, channel).setSmallIcon(R.drawable.ic_stat_rupee).setContentTitle(title).setContentText(text)
            .setContentIntent(open).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        NotificationManagerCompat.from(this).notify(id, n)
    }

    companion object {
        const val CH_QUESTION = "question"
        const val CH_LOGGED = "logged"
        fun of(c: Context) = c.applicationContext as Artha
    }
}
