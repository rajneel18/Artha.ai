package ai.artha.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
private fun BigTitle(text: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = T.display, color = P.text, modifier = Modifier.weight(1f)); actions()
    }
}

fun dayLabel(iso: String): String {
    val d = try { LocalDate.parse(iso.take(10)) } catch (_: Exception) { return iso }
    val t = LocalDate.now()
    return when (d) { t -> "Today"; t.minusDays(1) -> "Yesterday"; else -> d.format(DateTimeFormatter.ofPattern(if (d.year == t.year) "EEE, d MMM" else "d MMM yyyy")) }
}

// ---------------- Transactions ----------------
@Composable
fun TransactionsTab() {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    var filters by remember { mutableStateOf(false) }
    val cats = load { (call("GET", "/api/categories") as List<*>).map { it.toString() } } ?: emptyList()
    val all = load { call("GET", "/api/transactions?limit=1000") as List<Map<String, Any?>> } ?: emptyList()
    val shown = all.filter { t -> (category == null || t.s("category") == category) &&
        (query.isBlank() || listOf("merchant", "category", "note").any { t.s(it).contains(query.trim(), ignoreCase = true) } || t.s("amount").startsWith(query.trim())) }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
        BigTitle("Transactions") { IconCircle("filter") { filters = !filters }; Spacer(Modifier.width(10.dp)); IconCircle("check") { category = null; query = "" } }
        Row(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(18.dp)).background(P.card).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon("search", P.muted, 22.dp); Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Search transactions", style = T.body.copy(fontSize = 17.sp), color = P.muted)
                BasicTextField(query, { query = it }, singleLine = true, textStyle = T.body.copy(fontSize = 17.sp, color = P.text), cursorBrush = SolidColor(P.gold), modifier = Modifier.fillMaxWidth())
            }
        }
        if (filters) Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("All", category == null) { category = null }
            cats.forEach { c -> Pill(c, category == c) { category = if (category == c) null else c } }
        }
        if (shown.isEmpty()) EmptyState("No transactions found", "Start adding expenses to see them here") { GlyphIcon("inbox", P.gold, 64.dp) }
        else LazyColumn(contentPadding = PaddingValues(top = 12.dp, bottom = 200.dp)) {
            shown.groupBy { it.s("date") }.forEach { (date, txs) ->
                item(key = "h$date") {
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 16.dp, bottom = 6.dp)) {
                        Text(dayLabel(date), style = T.label, color = P.muted, modifier = Modifier.weight(1f))
                        Text(rupees0(txs.filter { it.s("kind") == "debit" }.sumOf { if (it["my_share"] != null) it.d("my_share") else it.d("amount") }), style = T.label, color = P.muted)
                    }
                }
                item(key = "g$date") { Card(pad = 6.dp) { txs.forEach { TxRow(it) } } }
            }
        }
    }
}

// ---------------- Splits ----------------
@Composable
fun SplitsTab() {
    var segment by remember { mutableIntStateOf(0) }
    var newSplit by remember { mutableStateOf(false) }
    var newGroup by remember { mutableStateOf(false) }
    val act = rememberAct(); val toast = LocalToast.current
    val f = load { map("/api/friends") }
    val groups = load { list("/api/groups") } ?: emptyList()
    val net = f?.d("net") ?: 0.0
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 200.dp)) {
        item { BigTitle("Splits") }
        item {
            Card(border = P.gold.copy(alpha = 0.35f)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Net balance", style = T.label, color = P.muted)
                    Text(rupees0(net), style = T.amount.copy(fontSize = 44.sp), color = if (net > 0) P.green else if (net < 0) P.red else P.muted)
                    Text(if (net > 0) "Friends owe you" else if (net < 0) "You owe friends" else "No splits yet", style = T.body, color = P.muted)
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)); GoldButton("New Split", icon = { GlyphIcon("plus", P.onGold, 20.dp) }) { newSplit = true }; Spacer(Modifier.height(20.dp)) }
        item { Segmented(listOf("Friends", "Groups"), segment) { segment = it } }
        if (segment == 0) {
            val people = f?.list("friends") ?: emptyList()
            if (people.isEmpty()) item { Spacer(Modifier.height(16.dp)); Card { EmptyState("Never forget who you owe", "Split a payment with anyone. They don't need Artha. Track who owes what, to the rupee.") { GlyphIcon("people", P.gold, 56.dp) } } }
            else items(people, key = { it.s("person") }) { p ->
                Spacer(Modifier.height(12.dp))
                Card(pad = 16.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(P.goldSoft), contentAlignment = Alignment.Center) { Text(p.s("person").take(1), style = T.title, color = P.gold) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.s("person"), style = T.body.copy(fontWeight = FontWeight.SemiBold), color = P.text)
                            Text(if (p.d("owed") > 0) "owes you ${rupees(p.d("owed"))}" else "all settled", style = T.small, color = if (p.d("owed") > 0) P.green else P.muted)
                        }
                        if (p.d("owed") > 0) Text("Settled", style = T.label.copy(fontWeight = FontWeight.Bold), color = P.gold,
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(P.goldSoft).tap(haptic = HapticFeedbackConstants.CONFIRM) {
                                act { call("POST", "/api/splits/settle", mapOf("person" to p.s("person"))); toast.show("${p.s("person")} is settled") } }.padding(horizontal = 14.dp, vertical = 10.dp))
                    }
                }
            }
        } else {
            item { Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                Text("+  New group", style = T.label.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold), color = P.gold, modifier = Modifier.tap { newGroup = true }.padding(6.dp)) } }
            if (groups.isEmpty()) item { Card { EmptyState("Groups for flats and trips", "Make a group once (Flat, Goa trip) and split with everyone in one tap.") { GlyphIcon("people", P.gold, 56.dp) } } }
            else items(groups, key = { "g" + it.l("id") }) { g ->
                Spacer(Modifier.height(12.dp))
                Card(pad = 16.dp) {
                    Text(g.s("name"), style = T.body.copy(fontWeight = FontWeight.SemiBold), color = P.text)
                    @Suppress("UNCHECKED_CAST") Text((g["members"] as? List<String>)?.joinToString(", ") ?: "", style = T.small, color = P.muted)
                }
            }
        }
    }
    if (newSplit) NewSplitSheet(groups) { newSplit = false }
    if (newGroup) NewGroupSheet { newGroup = false }
}

@Composable
private fun NewSplitSheet(groups: List<Map<String, Any?>>, onDone: () -> Unit) {
    val act = rememberAct(); val toast = LocalToast.current
    var amount by remember { mutableStateOf("") }; var what by remember { mutableStateOf("") }; var people by remember { mutableStateOf("") }
    Sheet(onDone) {
        Text("New split", style = T.title, color = P.text); Spacer(Modifier.height(14.dp))
        Field(amount, { amount = it.filter { c -> c.isDigit() || c == '.' } }, "₹0", number = true, big = true); Spacer(Modifier.height(10.dp))
        Field(what, { what = it }, "What for? (Dinner at Toit)"); Spacer(Modifier.height(10.dp))
        Field(people, { people = it }, "With whom? (Rahul, Priya)")
        if (groups.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            @Suppress("UNCHECKED_CAST") groups.forEach { g -> Pill(g.s("name"), false) { people = (g["members"] as? List<String>)?.joinToString(", ") ?: "" } }
        }
        Spacer(Modifier.height(16.dp))
        GoldButton("Split equally") {
            act { call("POST", "/api/splits/new", mapOf("amount" to (amount.toDoubleOrNull() ?: 0.0), "what" to what, "people" to people.split(",", "&", " and ").map { it.trim() }.filter { it.isNotEmpty() }))
                toast.show("Split added") }; onDone()
        }
    }
}

@Composable
private fun NewGroupSheet(onDone: () -> Unit) {
    val act = rememberAct(); var name by remember { mutableStateOf("") }; var members by remember { mutableStateOf("") }
    Sheet(onDone) {
        Text("New group", style = T.title, color = P.text); Spacer(Modifier.height(14.dp))
        Field(name, { name = it }, "Name (Flat 302, Goa trip)"); Spacer(Modifier.height(10.dp))
        Field(members, { members = it }, "People (Rahul, Priya, Asha)"); Spacer(Modifier.height(16.dp))
        GoldButton("Create group") { act { call("POST", "/api/groups", mapOf("name" to name, "members" to members.split(",").map { it.trim() }.filter { it.isNotEmpty() })) }; onDone() }
    }
}

// ---------------- Analytics ----------------
@Composable
fun AnalyticsTab() {
    val nav = LocalNav.current
    val month = LocalDate.now().toString().take(7)
    val ins = load { map("/api/insights?month=$month") }
    val pace = load { map("/api/pace") }
    val stats = ins?.get("stats") as? Map<String, Any?>
    @Suppress("UNCHECKED_CAST") val byCat = (stats?.get("by_category") as? Map<String, Double>)?.entries?.sortedByDescending { it.value } ?: emptyList()
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 200.dp)) {
        item { BigTitle("Analytics") }
        if (byCat.isEmpty()) item { EmptyState("No expenses yet", "Start tracking your spending to unlock insights and analytics.") { GlyphIcon("bars", P.gold, 64.dp) } }
        else {
            item {
                Card {
                    Text("This month", style = T.label, color = P.muted)
                    Text(rupees0(stats?.d("spent") ?: 0.0), style = T.amount, color = P.gold)
                    pace?.let { Text(it.s("headline"), style = T.body, color = P.text, modifier = Modifier.padding(top = 6.dp)) }
                }
            }
            item {
                Spacer(Modifier.height(16.dp))
                Card(Modifier.animateContentSize()) {
                    Text("Where it went", style = T.title.copy(fontSize = 18.sp), color = P.text); Spacer(Modifier.height(10.dp))
                    val max = byCat.maxOf { it.value }.coerceAtLeast(1.0)
                    byCat.forEach { (c, v) ->
                        Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(c, style = T.body, color = P.text, modifier = Modifier.width(120.dp), maxLines = 1)
                            ProgressBar((v / max).toFloat(), Modifier.weight(1f), height = 10.dp)
                            Text(rupees0(v), style = T.label, color = P.text, modifier = Modifier.width(84.dp).padding(start = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        }
                    }
                }
            }
            @Suppress("UNCHECKED_CAST") val tips = (ins?.get("insights") as? List<String>) ?: emptyList()
            if (tips.isNotEmpty()) item {
                Spacer(Modifier.height(16.dp))
                Card { Text("Insights", style = T.title.copy(fontSize = 18.sp), color = P.text); tips.forEach { Text("•  $it", style = T.body, color = P.muted, modifier = Modifier.padding(top = 8.dp)) } }
            }
        }
        item {
            SectionTitle("Tools")
            listOf(listOf(Triple("Ask Artha", "chat", "ask"), Triple("Money Story", "sparkle", "story")),
                listOf(Triple("Recurring", "repeat", "recurring"), Triple("Envelopes", "envelope", "envelopes")),
                listOf(Triple("Streak", "flame", "streak"), Triple("Budgets", "sliders", "categories"))).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (label, icon, page) -> ToolTile(label, Modifier.weight(1f), icon = { GlyphIcon(icon, size = 34.dp) }) { nav.go(page) } }
                }
            }
        }
    }
}
