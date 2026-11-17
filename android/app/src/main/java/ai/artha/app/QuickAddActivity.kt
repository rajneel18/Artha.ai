package ai.artha.app

import ai.artha.core.toJson
import ai.artha.ui.*
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.util.concurrent.Executors

/**
 * The small box that pops over the home screen from the widgets: type "70 lunch", press Enter, done.
 * Clear entries are saved right here; unclear ones open the app's review page.
 */
class QuickAddActivity : ComponentActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private var text by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private val api get() = Artha.of(this).api

    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (said.isNullOrBlank()) { if (text.isBlank()) finish() } else { text = said; submit() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && intent.getBooleanExtra("voice", false)) listen()
        setContent {
            CompositionLocalProvider(LocalPalette provides if (isSystemInDarkTheme()) DarkGold else LightGold) { Popup() }
        }
    }

    private fun listen() = try {
        voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN").putExtra(RecognizerIntent.EXTRA_PROMPT, "What did you spend? e.g. 70 lunch"))
    } catch (_: Exception) { toast("Voice typing isn't available") }

    @Composable
    private fun Popup() {
        Box(Modifier.fillMaxSize().tap(haptic = HapticFeedbackConstants.NO_HAPTICS) { finish() }, contentAlignment = Alignment.BottomCenter) {
            Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(12.dp).clip(RoundedCornerShape(28.dp)).background(P.card)
                .tap(haptic = HapticFeedbackConstants.NO_HAPTICS) { }.padding(20.dp)) {
                Text("Add expense", style = T.title, color = P.text)
                Spacer(Modifier.height(12.dp))
                Field(text, { text = it }, "70 lunch, chai 20, auto 120", autoFocus = !intent.getBooleanExtra("voice", false), onDone = { submit() })
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(56.dp).clip(CircleShape).background(P.cardHi).tap { listen() }, contentAlignment = Alignment.Center) { GlyphIcon("mic", P.gold, 26.dp) }
                    Spacer(Modifier.width(10.dp))
                    GoldButton(if (busy) "Adding…" else "Add", Modifier.weight(1f), enabled = text.isNotBlank() && !busy) { submit() }
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun submit() {
        val note = text.trim(); if (note.isEmpty() || busy) return
        busy = true
        io.execute {
            val r = api.handle("POST", "/api/parse", toJson(mapOf("text" to note)).toString())
            val items = (r.body as? List<Map<String, Any?>>).orEmpty()
            runOnUiThread {
                when {
                    r.status != 200 -> { busy = false; toast(if (r.status == 409) "Type it like \"70 lunch\" (the AI model isn't ready)" else ((r.body as? Map<*, *>)?.get("detail")?.toString() ?: "Couldn't add that")) }
                    items.isEmpty() -> { busy = false; toast("Couldn't find an amount. Try \"70 lunch\"") }
                    items.all { it["confident"] == true } -> io.execute {
                        for (i in items) api.handle("POST", "/api/transactions", toJson(mapOf("amount" to i["amount"], "merchant" to i["party"],
                            "category" to i["category"], "date" to i["date"])).toString())
                        runOnUiThread {
                            val one = items.singleOrNull()
                            toast(if (one != null) "Added ${Widgets.rupees((one["amount"] as Number).toDouble())} ${one["party"]} · ${one["category"]}" else "Added ${items.size} expenses")
                            finish()
                        }
                    }
                    else -> { // let you pick the category in the app
                        pendingQuick = items; pendingQuickDay = items.first()["date"]?.toString()?.let { LocalDate.parse(it) } ?: LocalDate.now()
                        startActivity(Intent(this, MainActivity::class.java).putExtra("open", "quick").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    }
                }
            }
        }
    }

    private fun toast(m: String) = Toast.makeText(applicationContext, m, Toast.LENGTH_LONG).show()
}
