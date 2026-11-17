package ai.artha.app

import ai.artha.ui.ArthaApp
import ai.artha.ui.Native
import ai.artha.ui.Repo
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.provider.Telephony
import android.speech.RecognizerIntent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.Executors

/** Hosts the native Compose UI and gives it access to the phone (permissions, AI model, voice, files, app lock). */
class MainActivity : AppCompatActivity(), Native {
    private val app get() = Artha.of(this)
    private val io = Executors.newSingleThreadExecutor()
    private var locked by mutableStateOf(false)
    private var unlocked = false
    private var lastStop = 0L

    private var onVoice: ((String) -> Unit)? = null
    private var onText: ((String) -> Unit)? = null
    private var pendingSave: String? = null

    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { onVoice?.invoke(it) }
    }
    private val textLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) io.execute {
            val t = try { contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } } catch (_: Exception) { null }
            runOnUiThread { if (t != null) onText?.invoke(t) else toast("Couldn't open that file") }
        }
    }
    private val saveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val content = pendingSave; pendingSave = null
        if (uri != null && content != null) io.execute { contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }; runOnUiThread { toast("Saved") } }
    }
    private val modelLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) io.execute {
            try {
                val target = QwenModel.file(this).apply { parentFile?.mkdirs() }
                val tmp = java.io.File(target.path + ".import")
                contentResolver.openInputStream(uri)!!.use { inp -> tmp.outputStream().use { inp.copyTo(it, 1 shl 16) } }
                tmp.renameTo(target); AnalyzeWorker.start(this); runOnUiThread { toast("AI model ready") }
            } catch (e: Exception) { runOnUiThread { toast("Could not import that file") } }
        }
    }
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val smsDenied = res.filterKeys { it == Manifest.permission.RECEIVE_SMS || it == Manifest.permission.READ_SMS }.values.any { !it }
        if (smsDenied) toast("Android blocked SMS access. Open App info, tap the three dots, Allow restricted settings, then try again.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyPrivacy()
        Reminder.schedule(this)
        handleShare(intent)
        val repo = Repo(app.api)
        setContent {
            Box(Modifier.fillMaxSize()) {
                ArthaApp(repo, this@MainActivity)
                if (locked) Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0A)))
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleShare(intent) }

    /** "Share" a bank SMS to PAISAve from Messages: it goes to the Detected list like any other SMS. */
    private fun handleShare(i: Intent?) {
        val text = if (i?.action == Intent.ACTION_SEND) i.getStringExtra(Intent.EXTRA_TEXT) else null
        if (text.isNullOrBlank()) return
        io.execute { app.api.handle("POST", "/api/ingest", ai.artha.core.toJson(mapOf("text" to text, "mode" to "sms")).toString()); runOnUiThread { toast("Check it under Detected payments") } }
    }

    // ---------------- app lock ----------------
    override fun onStop() { super.onStop(); lastStop = System.currentTimeMillis() }
    override fun onResume() {
        super.onResume()
        val away = System.currentTimeMillis() - lastStop > 30_000
        if (app.api.engine.settings()["app_lock"] == true && (!unlocked || (lastStop > 0 && away))) lock() else locked = false
    }
    private fun canLock() = BiometricManager.from(this).canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
    private fun lock() {
        if (!canLock()) { locked = false; return }
        unlocked = false; locked = true
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) { unlocked = true; lastStop = 0; locked = false }
            override fun onAuthenticationError(code: Int, msg: CharSequence) { if (!unlocked) finish() }
        }).authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Unlock PAISAve").setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL).build())
    }

    // ---------------- Native ----------------
    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    override fun status(): Map<String, Any?> = mapOf(
        "sms" to granted(Manifest.permission.RECEIVE_SMS),
        "read_sms" to granted(Manifest.permission.READ_SMS),
        "post_notifications" to granted(Manifest.permission.POST_NOTIFICATIONS),
        "model_ready" to app.model.available(),
        "model_download_pct" to try {
            WorkManager.getInstance(this).getWorkInfosForUniqueWork(ModelDownloadWorker.NAME).get()
                .firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }?.progress?.getInt("pct", 0)
        } catch (_: Exception) { null },
        "can_lock" to canLock(),
        "version" to packageManager.getPackageInfo(packageName, 0).versionName,
        "crash_report" to (app.startupError ?: CrashGuard.report(this)),
    )

    override fun requestSms() = permLauncher.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.POST_NOTIFICATIONS))
    override fun requestNotifications() = permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    override fun openAppInfo() = startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    override fun downloadModel() { ModelDownloadWorker.start(this) }
    override fun importModel() = modelLauncher.launch(arrayOf("*/*"))
    override fun deleteModel() { app.model.release(); QwenModel.file(this).delete() }
    override fun analyze() { AnalyzeWorker.start(this) }
    override fun clipboard(): String = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
    override fun copy(text: String) = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("PAISAve", text))
    override fun clearCrash() = CrashGuard.clear(this)
    override fun scheduleReminder() = Reminder.schedule(this)

    override fun applyPrivacy() {
        if (app.api.engine.settings()["hide_in_recents"] != false) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun voice(onText: (String) -> Unit) {
        onVoice = onText
        try {
            voiceLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN").putExtra(RecognizerIntent.EXTRA_PROMPT, "Say what you spent, e.g. chai 40, auto 120"))
        } catch (_: Exception) { toast("Voice input isn't available on this phone") }
    }

    override fun pickText(onText: (String) -> Unit) { this.onText = onText; textLauncher.launch(arrayOf("text/*", "application/json", "text/csv", "*/*")) }
    override fun saveFile(name: String, content: String) { pendingSave = content; saveLauncher.launch(name) }

    override fun importSms(months: Int) {
        if (!granted(Manifest.permission.READ_SMS)) { permLauncher.launch(arrayOf(Manifest.permission.READ_SMS)); return }
        io.execute {
            val n = importInbox(months.coerceIn(1, 24))
            AnalyzeWorker.start(this)
            runOnUiThread { toast(if (n == 0) "No new bank messages found" else "Found $n bank messages. Reading them now") }
        }
    }

    /** Queues bank SMS from the last [months] months. Only bank-style senders, and only messages that say money moved. */
    private fun importInbox(months: Int): Int {
        val since = LocalDateTime.now().minusMonths(months.toLong()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        var n = 0
        val trackReceived = app.api.engine.settings()["track_received"] == true
        val ex = app.api.extractor
        contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} >= ?", arrayOf(since.toString()), "${Telephony.Sms.DATE} ASC")?.use { c ->
            while (c.moveToNext()) {
                val sender = c.getString(0) ?: continue; val body = c.getString(1) ?: continue
                val low = body.lowercase()
                if (!isBankSender(sender) || !(low.contains("rs") || low.contains("inr") || low.contains("₹"))) continue
                if (!ex.mentionsMoneyOut(body) && !(trackReceived && ex.onlyMoneyIn(body))) continue
                if (ex.safetyCheck(body)?.first == "ignore") continue
                val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(2)), ZoneId.systemDefault())
                if (app.api.captureSms(body, sender, at, history = true)) n++
            }
        }
        return n
    }
}
