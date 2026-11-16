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
import androidx.core.app.RemoteInput
import ai.artha.core.Row
import ai.artha.core.d
import ai.artha.core.l
import ai.artha.core.s
import java.text.NumberFormat
import java.util.Locale

/** App singleton: one engine, one AI model, shared by the screen and background work. */
class Artha : Application() {
    lateinit var api: Api
        private set
    lateinit var model: QwenModel
        private set

    /** Set when the engine couldn't start (e.g. a database problem), so the screen can explain instead of crashing. */
    var startupError: String? = null
        private set

    override fun onCreate() {
        super.onCreate()
        CrashGuard.install(this)
        model = QwenModel(this)
        api = try { Api(Engine(AndroidDb(this)), model) } catch (e: Throwable) {
            startupError = CrashGuard.describe(e); CrashGuard.save(this, startupError!!)
            Api(Engine(ai.artha.core.InMemoryDb()), model) // lets the screen open and show the error
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_DETECTED, "Payments detected", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "When Artha finds payments in your bank SMS for you to confirm"
        })
        nm.createNotificationChannel(NotificationChannel(CH_ASK, "What was it for?", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Pops up after a payment so you can say what it was for"
        })
        nm.createNotificationChannel(NotificationChannel(CH_WORK, "Reading messages", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Progress while the on-device AI reads messages or downloads"
        })
    }

    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); if (level >= TRIM_MEMORY_BACKGROUND) model.release() }

    fun notifyDetected(count: Int) {
        if (!canNotify()) return
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = if (count == 1) "1 payment detected. Tap to review" else "$count payments detected. Tap to review"
        NotificationManagerCompat.from(this).notify(7, NotificationCompat.Builder(this, CH_DETECTED).setSmallIcon(R.drawable.ic_stat_rupee)
            .setContentTitle("Artha").setContentText(text).setContentIntent(open).setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
    }

    private fun canNotify() = api.engine.settings()["notify_logged"] != false &&
        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun action(action: String, id: Long, mutable: Boolean) = PendingIntent.getBroadcast(this, (id * 4 + action.length).toInt(),
        Intent(this, PaymentActionReceiver::class.java).setAction(action).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE))

    /** Pops down right after a payment: "₹1 paid to Shravani Shinde. What was it for?" with a reply box. */
    fun askPurpose(d: Row) {
        if (!canNotify()) return
        val id = d.l("id") ?: return
        val amount = "₹" + NumberFormat.getNumberInstance(Locale("en", "IN")).format(d.d("amount") ?: 0.0)
        val title = if (d.s("type") == "credit") "$amount received from ${d.s("party")}" else "$amount paid to ${d.s("party")}"
        val party = d.s("party") ?: ""
        val unnamed = party == "Unknown" || party.startsWith("UPI ") || party.endsWith("QR shop") || party == "GPay merchant"
        val ask = if (unnamed) "Who is this and what for?" else "What was it for?"
        val reply = NotificationCompat.Action.Builder(0, ask, action(ACTION_REPLY, id, true))
            .addRemoteInput(RemoteInput.Builder(KEY_PURPOSE).setLabel(if (unnamed) "e.g. Canteen, food" else "e.g. lunch, rent share, petrol").build())
            .setAllowGeneratedReplies(false).build()
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CH_ASK).setSmallIcon(R.drawable.ic_stat_rupee).setContentTitle(title)
            .setContentText(if (unnamed) "$ask Name it once and Artha remembers this payee" else "$ask Reply and Artha picks the category")
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(reply)
            .addAction(0, "Log as ${d.s("category") ?: "Other"}", action(ACTION_ACCEPT, id, false))
            .addAction(0, "Skip", action(ACTION_SKIP, id, false)).build()
        NotificationManagerCompat.from(this).notify(NOTIFY_BASE + id.toInt(), n)
    }

    /** Replaces the question with the outcome, then clears it. */
    fun finishPurpose(id: Long, text: String, keep: Boolean = false) {
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = NotificationManagerCompat.from(this)
        nm.notify(NOTIFY_BASE + id.toInt(), NotificationCompat.Builder(this, CH_DETECTED).setSmallIcon(R.drawable.ic_stat_rupee)
            .setContentTitle("Artha").setContentText(text).setSilent(true).apply { if (!keep) setTimeoutAfter(5000) }.build())
    }

    companion object {
        const val CH_ASK = "ask_purpose"
        const val KEY_PURPOSE = "purpose"
        const val ACTION_REPLY = "ai.artha.REPLY"
        const val ACTION_ACCEPT = "ai.artha.ACCEPT"
        const val ACTION_SKIP = "ai.artha.SKIP"
        const val NOTIFY_BASE = 1000
        const val CH_DETECTED = "detected"
        const val CH_WORK = "work"
        fun of(c: Context) = c.applicationContext as Artha
    }
}
