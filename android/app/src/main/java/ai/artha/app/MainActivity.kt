package ai.artha.app

import ai.artha.core.toJson
import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import android.speech.RecognizerIntent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Hosts the app UI (bundled in the APK, no server) and bridges it to the on-device engine and Android features. */
class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var cover: View
    private val app get() = Artha.of(this)
    private val io = Executors.newFixedThreadPool(2)
    private var unlocked = false
    private var lastStop = 0L

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraUri: Uri? = null
    private var pendingSave: Pair<String, String>? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        val uris = when {
            res.resultCode != RESULT_OK -> null
            data?.clipData != null -> Array(data.clipData!!.itemCount) { data.clipData!!.getItemAt(it).uri }
            data?.data != null -> arrayOf(data.data!!)
            cameraUri != null -> arrayOf(cameraUri!!)
            else -> null
        }
        fileCallback?.onReceiveValue(uris); fileCallback = null; cameraUri = null
    }
    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val text = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        js("window.__arthaVoice && __arthaVoice(${JSONObject.quote(text ?: "")})")
    }
    private val saveDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val (_, content) = pendingSave ?: return@registerForActivityResult
        pendingSave = null
        if (uri != null) io.execute {
            contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
            runOnUiThread { js("window.__arthaToast && __arthaToast('Saved')") }
        }
    }
    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        io.execute {
            try {
                val target = QwenModel.file(this).apply { parentFile?.mkdirs() }
                val tmp = java.io.File(target.path + ".import")
                contentResolver.openInputStream(uri)!!.use { inp -> tmp.outputStream().use { inp.copyTo(it, 1 shl 16) } }
                tmp.renameTo(target)
                AnalyzeWorker.start(this)
                js("window.__arthaToast && __arthaToast('AI model ready'); window.__arthaRefresh && __arthaRefresh()")
            } catch (e: Exception) { js("window.__arthaToast && __arthaToast('Could not import that file')") }
        }
    }
    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        // Android blocks SMS permissions for apps installed from an APK until "Allow restricted settings" is turned on.
        val smsAsked = res.keys.any { it == Manifest.permission.RECEIVE_SMS || it == Manifest.permission.READ_SMS }
        val denied = res.filterKeys { it == Manifest.permission.RECEIVE_SMS || it == Manifest.permission.READ_SMS }.values.any { !it }
        js(if (smsAsked && denied) "window.__arthaPermBlocked && __arthaPermBlocked()" else "window.__arthaRefresh && __arthaRefresh()")
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applySecureFlag()
        val root = FrameLayout(this)
        web = WebView(this)
        cover = View(this).apply { setBackgroundColor(Color.parseColor("#0f1115")); visibility = View.GONE }
        root.addView(web); root.addView(cover)
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom); WindowInsetsCompat.CONSUMED
        }

        // Serve the bundled UI from a virtual https origin; no file:// access, no network.
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = true
            mediaPlaybackRequiresUserGesture = true
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = loader.shouldInterceptRequest(request.url)
            // Android may kill the page's renderer (memory pressure, WebView update). Rebuild the screen instead of crashing.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy()
                recreate()
                return true
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host != "appassets.androidplatform.net") startActivity(Intent(Intent.ACTION_VIEW, request.url))
                return request.url.host != "appassets.androidplatform.net"
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null); fileCallback = cb
                val types = params.acceptTypes.flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotEmpty() }
                    .map { if (it.startsWith(".")) "text/*" else it }.ifEmpty { listOf("*/*") }
                val get = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType(if (types.size == 1) types[0] else "*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
                val chooser = Intent.createChooser(get, if (types.any { it.startsWith("image") }) "Receipt photo or PDF" else "Choose a file")
                if (types.any { it.startsWith("image") }) {
                    val f = File(cacheDir, "camera").apply { mkdirs() }.let { File(it, "receipt_${System.currentTimeMillis()}.jpg") }
                    cameraUri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", f)
                    chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, cameraUri)
                        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)))
                }
                pickFile.launch(chooser); return true
            }
        }
        web.addJavascriptInterface(Bridge(), "ArthaNative")
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.__arthaBack ? __arthaBack() : false") { handled ->
                    if (handled != "true") { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                }
            }
        })
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html" + sharedQuery(intent))
    }

    private fun sharedQuery(i: Intent?): String {
        val t = if (i?.action == Intent.ACTION_SEND) i.getStringExtra(Intent.EXTRA_TEXT) else null
        return if (t.isNullOrBlank()) "" else "?text=" + Uri.encode(t)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val t = if (intent.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else null
        if (!t.isNullOrBlank()) js("window.__arthaShared && __arthaShared(${JSONObject.quote(t)})")
    }

    // ---------------- app lock & privacy ----------------
    private fun settings() = app.api.engine.settings()

    private fun applySecureFlag() {
        if (settings()["hide_in_recents"] != false) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onStop() { super.onStop(); lastStop = System.currentTimeMillis() }

    override fun onResume() {
        super.onResume()
        val away = System.currentTimeMillis() - lastStop > 30_000
        if (settings()["app_lock"] == true && (!unlocked || (lastStop > 0 && away))) lock() else cover.visibility = View.GONE
        js("window.__arthaRefresh && __arthaRefresh()")
    }

    private fun canLock() = BiometricManager.from(this).canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

    private fun lock() {
        if (!canLock()) { cover.visibility = View.GONE; return }
        unlocked = false; cover.visibility = View.VISIBLE
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) { unlocked = true; lastStop = 0; cover.visibility = View.GONE }
            override fun onAuthenticationError(code: Int, msg: CharSequence) { if (!unlocked) finish() }
        }).authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Unlock Artha").setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL).build())
    }

    private fun js(code: String) = runOnUiThread { if (::web.isInitialized) web.evaluateJavascript(code, null) }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun downloadPct(): Int? = try {
        WorkManager.getInstance(this).getWorkInfosForUniqueWork(ModelDownloadWorker.NAME).get()
            .firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }?.progress?.getInt("pct", 0)
    } catch (_: Exception) { null }

    /** Queues bank SMS from the last [months] months for the AI. Only messages from bank-style senders that mention money. */
    private fun importInbox(months: Int): Int {
        val since = LocalDateTime.now().minusMonths(months.toLong()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        var n = 0
        val trackReceived = app.api.engine.settings()["track_received"] == true
        contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} >= ?", arrayOf(since.toString()), "${Telephony.Sms.DATE} ASC")?.use { c ->
            while (c.moveToNext()) {
                val sender = c.getString(0) ?: continue; val body = c.getString(1) ?: continue
                val low = body.lowercase()
                if (!isBankSender(sender) || !(low.contains("rs") || low.contains("inr") || low.contains("₹"))) continue
                // Only messages that say money went out (or came in, if you track it) are worth the AI's time.
                val ex = app.api.extractor
                if (!ex.mentionsMoneyOut(body) && !(trackReceived && ex.onlyMoneyIn(body))) continue
                if (ex.safetyCheck(body)?.first == "ignore") continue
                val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(2)), ZoneId.systemDefault())
                if (app.api.captureSms(body, sender, at, history = true)) n++
            }
        }
        return n
    }

    /** Everything the UI can ask of the phone. Exposed to the bundled page only. */
    inner class Bridge {
        @JavascriptInterface
        fun request(id: String, method: String, path: String, body: String?) {
            io.execute {
                val r = app.api.handle(method, path, body)
                val payload = toJson(mapOf("status" to r.status, "body" to r.body)).toString()
                js("window.__arthaResolve(${JSONObject.quote(id)}, $payload)")
                if (method != "GET" && path.contains("settings")) runOnUiThread { applySecureFlag() }
            }
        }

        @JavascriptInterface
        fun status(): String = toJson(mapOf(
            "sms" to granted(Manifest.permission.RECEIVE_SMS),
            "read_sms" to granted(Manifest.permission.READ_SMS),
            "post_notifications" to granted(Manifest.permission.POST_NOTIFICATIONS),
            "model_ready" to app.model.available(),
            "model_download_pct" to downloadPct(),
            "model_size_gb" to QwenModel.SIZE_BYTES / 1e9,
            "pending" to app.api.engine.pendingCount(),
            "can_lock" to canLock(),
            "version" to packageManager.getPackageInfo(packageName, 0).versionName,
            "crash_report" to CrashGuard.report(this@MainActivity),
            "startup_error" to app.startupError,
        )).toString()

        @JavascriptInterface fun requestSms() = askPerms.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.POST_NOTIFICATIONS))
        @JavascriptInterface fun requestNotifications() = askPerms.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        @JavascriptInterface fun openAppInfo() = startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        @JavascriptInterface fun openUrl(url: String) { if (url.startsWith("https://")) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

        @JavascriptInterface fun downloadModel() = ModelDownloadWorker.start(this@MainActivity)
        @JavascriptInterface fun importModel() = runOnUiThread { pickModel.launch(arrayOf("*/*")) }
        @JavascriptInterface fun deleteModel() { app.model.release(); QwenModel.file(this@MainActivity).delete() }
        @JavascriptInterface fun analyze() = AnalyzeWorker.start(this@MainActivity)
        @JavascriptInterface fun copyText(text: String) = runOnUiThread {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Artha report", text))
        }
        @JavascriptInterface fun clearCrash() = CrashGuard.clear(this@MainActivity)

        @JavascriptInterface
        fun importSms(months: Int) {
            if (!granted(Manifest.permission.READ_SMS)) { askPerms.launch(arrayOf(Manifest.permission.READ_SMS)); return }
            io.execute {
                val n = importInbox(months.coerceIn(1, 24))
                AnalyzeWorker.start(this@MainActivity)
                js("window.__arthaToast && __arthaToast(${JSONObject.quote(if (n == 0) "No new bank messages found" else "Found $n bank messages. Reading them now")}); window.__arthaRefresh && __arthaRefresh()")
            }
        }

        @JavascriptInterface fun clipboard(): String =
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: ""

        @JavascriptInterface fun voice() = runOnUiThread {
            try {
                voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN").putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a payment, e.g. 450 coffee split with Asha"))
            } catch (e: Exception) { js("window.__arthaToast && __arthaToast('Voice input is not available on this phone')") }
        }

        @JavascriptInterface fun saveFile(name: String, content: String) = runOnUiThread { pendingSave = name to content; saveDoc.launch(name) }

        @JavascriptInterface fun setBars(dark: Boolean, color: String) = runOnUiThread {
            try { window.decorView.setBackgroundColor(Color.parseColor(color)) } catch (_: Exception) {}
            WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
        }
    }
}
