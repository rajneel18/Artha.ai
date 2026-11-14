package ai.artha.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Bank SMS -> queue for the on-device AI. Messages from phone numbers (people) are never read. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val app = Artha.of(context)
        if (app.api.engine.settings()["auto_capture_sms"] == false) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        var queued = false
        for ((sender, msgs) in parts.groupBy { it.originatingAddress ?: "" }) {
            if (!isBankSender(sender)) continue
            val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(msgs.first().timestampMillis), ZoneId.systemDefault())
            if (app.api.captureSms(msgs.joinToString("") { it.messageBody ?: "" }, sender, at)) queued = true
        }
        if (queued) AnalyzeWorker.start(context)
    }
}

/** Banks send from short alphanumeric IDs like "AX-SARBNK"; people send from phone numbers. */
fun isBankSender(sender: String): Boolean = sender.any { it.isLetter() }

/** Reads every queued message with the AI, then tells you once what needs your OK. */
class AnalyzeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = Artha.of(applicationContext)
        val engine = app.api.engine
        if (!app.model.available()) return Result.success() // waits in the queue until the model is set up
        val before = engine.detected()["items"].let { (it as List<*>).size }
        var done = 0
        try {
            while (true) {
                val left = engine.pendingCount()
                if (left == 0L) break
                if (left > 3) setForeground(progressInfo("Reading bank messages… $left left", done, (done + left).toInt()))
                if (!engine.analyzeNext(app.api.extractor)) break
                done++
            }
        } finally { app.model.release() }
        val newItems = (engine.detected()["items"] as List<*>).size - before
        if (newItems > 0) app.notifyDetected(newItems)
        return Result.success()
    }

    override suspend fun getForegroundInfo() = progressInfo("Reading bank messages…", 0, 0)

    private fun progressInfo(text: String, done: Int, total: Int): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, Artha.CH_WORK).setSmallIcon(R.drawable.ic_stat_rupee)
            .setContentTitle("Artha").setContentText(text).setOngoing(true).setSilent(true)
            .setProgress(total, done, total == 0).build()
        return ForegroundInfo(42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        fun start(c: Context) = WorkManager.getInstance(c).enqueueUniqueWork("analyze", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<AnalyzeWorker>().setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build())
    }
}

/** Downloads the AI model once (resumes if interrupted). */
class ModelDownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val target = QwenModel.file(applicationContext); target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        return try {
            setForeground(info(0))
            val conn = (URL(QwenModel.URL).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true; connectTimeout = 30_000; readTimeout = 60_000
                if (part.exists()) setRequestProperty("Range", "bytes=${part.length()}-")
            }
            val resumed = conn.responseCode == 206
            if (conn.responseCode !in listOf(200, 206)) return Result.retry()
            val total = (if (resumed) part.length() else 0L) + conn.contentLengthLong
            RandomAccessFile(part, "rw").use { out ->
                if (!resumed) out.setLength(0)
                out.seek(out.length())
                conn.inputStream.use { inp ->
                    val buf = ByteArray(1 shl 16); var written = out.length(); var lastPct = -1
                    while (true) {
                        if (isStopped) return Result.retry()
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); written += n
                        val pct = (written * 100 / total).toInt()
                        if (pct != lastPct) { lastPct = pct; setProgress(workDataOf("pct" to pct)); setForeground(info(pct)) }
                    }
                }
            }
            part.renameTo(target)
            AnalyzeWorker.start(applicationContext) // read anything that arrived while the model was missing
            Result.success()
        } catch (e: Exception) { Result.retry() }
    }

    override suspend fun getForegroundInfo() = info(0)

    private fun info(pct: Int) = ForegroundInfo(43, NotificationCompat.Builder(applicationContext, Artha.CH_WORK)
        .setSmallIcon(R.drawable.ic_stat_rupee).setContentTitle("Downloading Artha's AI model").setContentText("$pct%")
        .setOngoing(true).setSilent(true).setProgress(100, pct, false).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    companion object {
        const val NAME = "model-download"
        fun start(c: Context) = WorkManager.getInstance(c).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ModelDownloadWorker>().build())
    }
}
