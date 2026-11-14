package ai.artha.app

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.Executors

private val worker = Executors.newSingleThreadExecutor()

/** Bank SMS -> ledger. Only alphanumeric senders (banks use IDs like "AX-HDFCBK"); messages from phone numbers are ignored. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val bySender = parts.groupBy { it.originatingAddress ?: "" }
        val pending = goAsync()
        worker.execute {
            try {
                for ((sender, msgs) in bySender) {
                    if (Regex("^\\+?[0-9 ]{6,}$").matches(sender)) continue // a person, not a bank
                    Artha.of(context).capture(msgs.joinToString("") { it.messageBody ?: "" }, "sms")
                }
            } finally { pending.finish() }
        }
    }
}

/** Payment notifications from UPI and bank apps -> ledger. Other apps' notifications are never read. */
class PaymentListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in PAYMENT_APPS) return
        val ex = sbn.notification.extras
        val text = listOfNotNull(ex.getCharSequence(Notification.EXTRA_TITLE), ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: ex.getCharSequence(Notification.EXTRA_TEXT)).joinToString(". ")
        if (text.isBlank()) return
        // In messaging apps, only bank-style messages (account / UPI / card references), never chats from people.
        if (sbn.packageName in MESSAGING_APPS && !BANK_HINT.containsMatchIn(text)) return
        val app = applicationContext
        worker.execute { Artha.of(app).capture(text, "notification") }
    }

    companion object {
        val MESSAGING_APPS = setOf("com.google.android.apps.messaging", "com.samsung.android.messaging")
        private val BANK_HINT = Regex("(?i)\\b(a/c|acct|account|upi|ref(?:erence)?\\s*(?:no)?|card|xx\\d{3,}|\\*\\d{3,}|neft|imps)\\b")
        val PAYMENT_APPS = setOf(
            "com.google.android.apps.nbu.paisa.user", // Google Pay
            "com.phonepe.app", "net.one97.paytm", "in.org.npci.upiapp", // PhonePe, Paytm, BHIM
            "in.amazon.mShop.android.shopping", "com.dreamplug.androidapp", // Amazon Pay, CRED
            "com.snapwork.hdfc", "com.hdfcbank.payzapp", "com.csam.icici.bank.imobile", "com.sbi.lotusintouch", "com.sbi.SBIFreedomPlus",
            "com.axis.mobile", "com.msf.kbank.mobile", "com.Version1", "com.fss.indus", "com.bankofbaroda.mconnect", "com.idfcfirstbank.optimus",
            "com.jupiter.money", "com.fi.money", "com.slice",
            "com.google.android.apps.messaging", "com.samsung.android.messaging", // SMS apps, if SMS permission is off
        )
    }
}
