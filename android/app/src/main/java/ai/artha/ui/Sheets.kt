package ai.artha.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CategoryPicker(selected: String?, onPick: (String) -> Unit) {
    val cats = load { (call("GET", "/api/categories") as List<*>).map { it.toString() } } ?: emptyList()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cats.forEach { c -> Pill(c, c == selected) { onPick(c) } }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun EnvelopePicker(selected: Long?, onPick: (Long?) -> Unit) {
    val envs = load { list("/api/envelopes") } ?: emptyList()
    if (envs.isEmpty()) return
    Text("Envelope (optional)", style = T.label, color = P.muted, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("None", selected == null) { onPick(null) }
        envs.forEach { e -> Pill(e.s("name"), e.l("id") == selected) { onPick(e.l("id")) } }
    }
}

@Composable
fun AddExpenseSheet(date: java.time.LocalDate? = null, onDone: () -> Unit) {
    var day by remember { mutableStateOf(date ?: java.time.LocalDate.now()) }
    var mode by remember { mutableIntStateOf(0) }
    val act = rememberAct(); val toast = LocalToast.current; val native = LocalNative.current; val nav = LocalNav.current
    var text by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }; var merchant by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }; var envelope by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    Sheet(onDone) {
        Text("Add expense", style = T.title, color = P.text); Spacer(Modifier.height(14.dp))
        Segmented(listOf("Quick", "Manual"), mode) { mode = it }; Spacer(Modifier.height(14.dp))
        DayChooser(day) { day = it }; Spacer(Modifier.height(14.dp))
        if (mode == 0) {
            Field(text, { text = it }, "chai 40, auto 120, groceries 850")
            Text("Say several at once. Hinglish works too: \"200 chai aur 50 auto\".", style = T.small, color = P.muted, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(P.cardHi).tap { native.voice { text = it } }, contentAlignment = Alignment.Center) { GlyphIcon("mic", P.gold, 26.dp) }
                GhostButton("Paste SMS", Modifier.weight(1f)) {
                    val clip = native.clipboard()
                    if (clip.isBlank()) toast.show("Copy a bank SMS first")
                    else act { call("POST", "/api/ingest", mapOf("text" to clip, "mode" to "sms")); toast.show("Check it under Detected payments"); nav.go("detected") }
                    onDone()
                }
            }
            Spacer(Modifier.height(14.dp))
            GoldButton(if (busy) "Reading…" else "Review", enabled = text.isNotBlank() && !busy) {
                busy = true
                act {
                    pendingQuick = call("POST", "/api/parse", mapOf("text" to text)) as List<Map<String, Any?>>
                    pendingQuickDay = day
                    nav.go("quick")
                }
                onDone()
            }
        } else {
            Field(amount, { amount = it.filter { c -> c.isDigit() || c == '.' } }, "₹0", number = true, big = true); Spacer(Modifier.height(10.dp))
            Field(merchant, { merchant = it }, "Where or who? (Swiggy, Ramesh)"); Spacer(Modifier.height(14.dp))
            CategoryPicker(category) { category = it }
            EnvelopePicker(envelope) { envelope = it }
            Spacer(Modifier.height(16.dp))
            GoldButton("Save", enabled = (amount.toDoubleOrNull() ?: 0.0) > 0) {
                act {
                    @Suppress("UNCHECKED_CAST")
                    val tx = call("POST", "/api/transactions", mapOf("amount" to amount.toDouble(), "merchant" to merchant.ifBlank { "Cash" }, "category" to (category ?: "Other"), "date" to day.toString())) as? Map<String, Any?>
                    if (envelope != null && tx != null) call("POST", "/api/transactions/${tx.l("id")}/envelope", mapOf("envelope_id" to envelope))
                    toast.show("Saved ${rupees(amount.toDouble())} to ${shortDay(day).lowercase().replaceFirstChar { it.uppercase() }}")
                }
                onDone()
            }
        }
    }
}

/** Results from Quick Add waiting on the review page. */
var pendingQuick by mutableStateOf<List<Map<String, Any?>>>(emptyList())
/** The day chosen in the add sheet for those results. */
var pendingQuickDay by mutableStateOf(java.time.LocalDate.now())

@Composable
fun TxSheet(id: Long, onDone: () -> Unit) {
    val act = rememberAct(); val toast = LocalToast.current
    val t = load(id) { map("/api/transactions/$id") } ?: return
    var merchant by remember(t) { mutableStateOf(t.s("merchant")) }
    var amount by remember(t) { mutableStateOf(t.d("amount").let { if (it % 1 == 0.0) it.toLong().toString() else it.toString() }) }
    var category by remember(t) { mutableStateOf(t.s("category")) }
    var envelope by remember(t) { mutableStateOf((t["envelope_id"] as? Number)?.toLong()) }
    var people by remember { mutableStateOf("") }
    var day by remember(t) { mutableStateOf(try { java.time.LocalDate.parse(t.s("date").take(10)) } catch (_: Exception) { java.time.LocalDate.now() }) }
    Sheet(onDone) {
        DayChooser(day) { day = it }
        Spacer(Modifier.height(12.dp))
        Field(amount, { amount = it.filter { c -> c.isDigit() || c == '.' } }, "₹0", number = true, big = true); Spacer(Modifier.height(10.dp))
        Field(merchant, { merchant = it }, "Merchant"); Spacer(Modifier.height(14.dp))
        CategoryPicker(category) { category = it }
        EnvelopePicker(envelope) { envelope = it }
        if (t.s("note").isNotBlank() && t.s("note") != t.s("merchant")) Text(t.s("note"), style = T.small, color = P.muted, modifier = Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(16.dp))
        GoldButton("Save") {
            act {
                call("PATCH", "/api/transactions/$id", mapOf("merchant" to merchant, "amount" to (amount.toDoubleOrNull() ?: t.d("amount")), "category" to category, "date" to day.toString()))
                call("POST", "/api/transactions/$id/envelope", mapOf("envelope_id" to envelope))
                toast.show("Saved. PAISAve will remember $merchant as $category")
            }
            onDone()
        }
        @Suppress("UNCHECKED_CAST") val splits = (t["splits"] as? List<Map<String, Any?>>) ?: emptyList()
        Text("Split", style = T.title.copy(fontWeight = FontWeight.Bold), color = P.text, modifier = Modifier.padding(top = 24.dp, bottom = 10.dp))
        if (splits.isNotEmpty()) {
            splits.forEach { s -> Row(Modifier.padding(vertical = 4.dp)) { Text(s.s("person"), style = T.body, color = P.text, modifier = Modifier.weight(1f)); Text(rupees(s.d("amount")) + if (s.l("settled") == 1L) " · paid" else "", style = T.body, color = P.muted) } }
            Text("Your share ${rupees(t.d("my_share"))}", style = T.label, color = P.gold, modifier = Modifier.padding(top = 6.dp))
        } else {
            Field(people, { people = it }, "Split with (Rahul, Priya)"); Spacer(Modifier.height(10.dp))
            GhostButton("Split equally", Modifier.fillMaxWidth(), P.gold) {
                act { call("POST", "/api/transactions/$id/split", mapOf("people" to people.split(",", "&").map { it.trim() }.filter { it.isNotEmpty() })); toast.show("Split saved") }
                onDone()
            }
        }
        Spacer(Modifier.height(14.dp))
        GhostButton("Delete", Modifier.fillMaxWidth(), P.red) {
            act {
                call("DELETE", "/api/transactions/$id")
                toast.show("Deleted ${t.s("merchant")}", "Undo" to { act { call("POST", "/api/transactions", mapOf("amount" to t.d("amount"), "merchant" to t.s("merchant"),
                    "category" to t.s("category"), "kind" to t.s("kind"), "date" to t.s("date"), "note" to t.s("note"))) } })
            }
            onDone()
        }
    }
}
