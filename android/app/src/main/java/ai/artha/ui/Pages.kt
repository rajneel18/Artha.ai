package ai.artha.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import kotlinx.coroutines.launch

private fun placeholder(p: String) = p.isBlank() || p == "Unknown" || p.startsWith("UPI ") || p.endsWith("QR shop") || p == "GPay merchant"

@Composable private fun PageList(title: String, action: (@Composable () -> Unit)? = null, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PageHeader(title, action)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 120.dp), content = content)
    }
}

// ---------------- Detected payments (our SMS feature) ----------------
@Composable
fun DetectedPage() {
    val act = rememberAct(); val toast = LocalToast.current
    val det = load { map("/api/detected") }
    val review = load { list("/api/review") } ?: emptyList()
    val items = det?.list("items") ?: emptyList(); val groups = det?.list("groups") ?: emptyList()
    PageList("Detected", action = { if (items.size > 1) Text("Log all", style = T.label.copy(fontWeight = FontWeight.Bold), color = P.gold,
        modifier = Modifier.tap(haptic = HapticFeedbackConstants.CONFIRM) { act { val r = call("POST", "/api/detected/log-all") as Map<*, *>; toast.show("Logged ${r["logged"]}") } }.padding(8.dp)) }) {
        val pending = det?.l("pending") ?: 0
        if (pending > 0) item { Card(Modifier.padding(bottom = 12.dp)) { Text("Reading $pending message${if (pending > 1) "s" else ""} on your phone…", style = T.body, color = P.muted) } }
        if (items.isEmpty() && groups.isEmpty() && review.isEmpty() && pending == 0L) item { EmptyState("All caught up", "New payments from your bank SMS will appear here.") { GlyphIcon("check", P.gold, 56.dp) } }
        items(items, key = { "d" + it.l("id") }) { d -> DetectedCard(d) }
        if (groups.isNotEmpty()) {
            item { SectionTitle("Imported history") }
            item { Text("${det?.l("history_logged") ?: 0} logged automatically. These need a look:", style = T.body, color = P.muted, modifier = Modifier.padding(bottom = 10.dp)) }
            items(groups, key = { "g" + it.s("gkey") + it.s("type") }) { g -> GroupCard(g) }
        }
        if (review.isNotEmpty()) {
            item { SectionTitle("Questions") }
            items(review, key = { "r" + it.l("id") }) { r ->
                Card(Modifier.padding(bottom = 12.dp)) {
                    Row { Text(r.s("merchant"), style = T.body.copy(fontWeight = FontWeight.SemiBold), color = P.text, modifier = Modifier.weight(1f)); Text(rupees(r.d("amount")), style = T.body, color = P.text) }
                    Text(r.s("question"), style = T.small, color = P.muted, modifier = Modifier.padding(vertical = 8.dp))
                    if (r.s("review_reason") == "duplicate") Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GhostButton("Delete duplicate", color = P.red) { act { call("POST", "/api/review/${r.l("id")}", mapOf("action" to "delete")) } }
                        GhostButton("Both are real") { act { call("POST", "/api/review/${r.l("id")}", mapOf("action" to "confirm")) } }
                    } else CategoryPicker(null) { c -> act { call("POST", "/api/review/${r.l("id")}", mapOf("action" to "confirm", "category" to c)); toast.show("Saved") } }
                }
            }
        }
    }
}

@Composable
private fun DetectedCard(d: Map<String, Any?>) {
    val act = rememberAct(); val toast = LocalToast.current
    var name by remember { mutableStateOf("") }; var category by remember(d) { mutableStateOf(d.s("category")) }; var showCats by remember { mutableStateOf(false) }
    val upcoming = d.s("type") == "upcoming"
    Card(Modifier.padding(bottom = 12.dp).animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.s("party"), style = T.title.copy(fontSize = 18.sp), color = P.text)
                Text((if (upcoming) "Upcoming · " else "") + dayLabel(d.s("received_at")) + if (d.l("confident") == 0L) " · please check" else "", style = T.small, color = if (d.l("confident") == 0L) P.gold else P.muted)
            }
            Text(rupees(d.d("amount")), style = T.title, color = P.text)
        }
        if (placeholder(d.s("party"))) { Spacer(Modifier.height(10.dp)); Field(name, { name = it }, "Who is this? Artha remembers it") }
        Spacer(Modifier.height(10.dp))
        Text(category, style = T.label, color = P.gold, modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(P.goldSoft).tap { showCats = !showCats }.padding(horizontal = 14.dp, vertical = 8.dp))
        if (showCats) { Spacer(Modifier.height(10.dp)); CategoryPicker(category) { category = it; showCats = false } }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Skip", Modifier.weight(1f)) { act { call("POST", "/api/detected/${d.l("id")}", mapOf("action" to "skip")) } }
            Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(16.dp)).background(GoldGradient).tap(haptic = HapticFeedbackConstants.CONFIRM) {
                act { call("POST", "/api/detected/${d.l("id")}", buildMap { put("action", "log"); put("category", category); if (name.isNotBlank()) put("party", name.trim()) })
                    toast.show(if (upcoming) "Added to recurring" else "Logged") }
            }, contentAlignment = Alignment.Center) { Text(if (upcoming) "Track" else "Log", style = T.label.copy(fontWeight = FontWeight.Bold), color = P.onGold) }
        }
        Text(d.s("body"), style = T.small.copy(fontSize = 11.sp), color = P.muted.copy(alpha = 0.7f), maxLines = 3, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun GroupCard(g: Map<String, Any?>) {
    val act = rememberAct(); val toast = LocalToast.current
    var name by remember { mutableStateOf("") }; var category by remember(g) { mutableStateOf(g.s("category")) }; var showCats by remember { mutableStateOf(false) }
    Card(Modifier.padding(bottom = 12.dp).animateContentSize()) {
        Row { Text(g.s("party"), style = T.title.copy(fontSize = 18.sp), color = P.text, modifier = Modifier.weight(1f)); Text(rupees0(g.d("total")), style = T.title, color = P.text) }
        Text("${g.l("count")} payment${if (g.l("count") > 1) "s" else ""} · ${dayLabel(g.s("first"))} to ${dayLabel(g.s("last"))}", style = T.small, color = P.muted)
        if (placeholder(g.s("party"))) { Spacer(Modifier.height(10.dp)); Field(name, { name = it }, "Who is this?") }
        Spacer(Modifier.height(10.dp))
        Text(category, style = T.label, color = P.gold, modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(P.goldSoft).tap { showCats = !showCats }.padding(horizontal = 14.dp, vertical = 8.dp))
        if (showCats) { Spacer(Modifier.height(10.dp)); CategoryPicker(category) { category = it; showCats = false } }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Skip", Modifier.weight(1f)) { act { call("POST", "/api/detected/group", mapOf("key" to g.s("gkey"), "type" to g.s("type"), "action" to "skip")) } }
            Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(16.dp)).background(GoldGradient).tap(haptic = HapticFeedbackConstants.CONFIRM) {
                act { val r = call("POST", "/api/detected/group", buildMap { put("key", g.s("gkey")); put("type", g.s("type")); put("action", "log"); put("category", category)
                    if (name.isNotBlank()) put("name", name.trim()) }) as Map<*, *>; toast.show("Logged ${r["count"]}") }
            }, contentAlignment = Alignment.Center) { Text("Log ${g.l("count")}", style = T.label.copy(fontWeight = FontWeight.Bold), color = P.onGold) }
        }
    }
}

// ---------------- Log Your Day: review, fix, save all ----------------
@Composable
fun QuickAddPage() {
    val act = rememberAct(); val toast = LocalToast.current; val nav = LocalNav.current
    val rows = remember { mutableStateListOf<MutableMap<String, Any?>>().apply { addAll(pendingQuick.map { it.toMutableMap() }) } }
    var text by remember { mutableStateOf("") }
    PageList("Log Your Day") {
        item {
            Text("Describe everything you spent on, all in one go.", style = T.body, color = P.muted); Spacer(Modifier.height(10.dp))
            Field(text, { text = it }, "chai 40, auto 120, groceries 850"); Spacer(Modifier.height(10.dp))
            GhostButton("Read it", Modifier.fillMaxWidth(), P.gold) {
                act { @Suppress("UNCHECKED_CAST") val r = call("POST", "/api/parse", mapOf("text" to text)) as List<Map<String, Any?>>
                    if (r.isEmpty()) toast.show("Couldn't find amounts. Try \"chai 40, auto 120\"") else { rows.addAll(r.map { it.toMutableMap() }); text = "" } }
            }
        }
        items(rows.size) { i ->
            val r = rows[i]; var cat by remember(r) { mutableStateOf(r.s("category")) }; var open by remember { mutableStateOf(false) }
            Card(Modifier.padding(top = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.s("party"), style = T.body.copy(fontWeight = FontWeight.SemiBold), color = P.text, modifier = Modifier.weight(1f))
                    Text(rupees(r.d("amount")), style = T.title.copy(fontSize = 18.sp), color = P.text)
                    Text("  ×", style = T.title, color = P.muted, modifier = Modifier.tap { rows.removeAt(i) })
                }
                Text(cat, style = T.label, color = P.gold, modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(14.dp)).background(P.goldSoft).tap { open = !open }.padding(horizontal = 12.dp, vertical = 6.dp))
                if (open) { Spacer(Modifier.height(8.dp)); CategoryPicker(cat) { cat = it; r["category"] = it; open = false } }
            }
        }
        if (rows.isNotEmpty()) item {
            Spacer(Modifier.height(18.dp))
            GoldButton("Save all ${rows.size}") {
                val save = rows.toList()
                act { save.forEach { r -> call("POST", "/api/transactions", mapOf("amount" to r.d("amount"), "merchant" to r.s("party"), "category" to r.s("category"), "date" to r.s("date"))) }
                    toast.show("Saved ${save.size}"); pendingQuick = emptyList() }
                nav.back()
            }
        }
    }
}

// ---------------- Ask Artha ----------------
@Composable
fun AskPage() {
    val repo = LocalRepo.current; val scope = rememberCoroutineScope()
    val chat = remember { mutableStateListOf("Ask me about your spending. Try \"how much on food last month?\" or \"uber over 300\"." to false) }
    var q by remember { mutableStateOf("") }; val state = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        PageHeader("Ask Artha")
        LazyColumn(Modifier.weight(1f), state = state, contentPadding = PaddingValues(20.dp)) {
            items(chat.size) { i -> val (m, mine) = chat[i]
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                    Text(m, style = T.body, color = if (mine) P.onGold else P.text, modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(20.dp))
                        .background(if (mine) P.gold else P.card).padding(14.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Field(q, { q = it }, "Ask anything", Modifier.weight(1f)); Spacer(Modifier.width(10.dp))
            Box(Modifier.size(56.dp).clip(CircleShape).background(P.gold).tap(haptic = HapticFeedbackConstants.CONFIRM) {
                val question = q.trim(); if (question.isEmpty()) return@tap
                chat.add(question to true); q = ""
                scope.launch { val a = try { (repo.call("POST", "/api/ask", mapOf("question" to question)) as Map<*, *>)["answer"].toString() } catch (e: Exception) { e.message ?: "Couldn't answer that" }
                    chat.add(a to false); state.animateScrollToItem(chat.size - 1) }
            }, contentAlignment = Alignment.Center) { GlyphIcon("chevron", P.onGold, 26.dp) }
        }
    }
}

// ---------------- Money Story: the month in a few swipeable screens ----------------
@Composable
fun StoryPage() {
    val month = LocalDate.now().toString().take(7)
    val ins = load { map("/api/insights?month=$month") }; val streak = load { map("/api/streak") }; val pace = load { map("/api/pace") }
    @Suppress("UNCHECKED_CAST") val stats = ins?.get("stats") as? Map<String, Any?>
    @Suppress("UNCHECKED_CAST") val tips = (ins?.get("insights") as? List<String>) ?: emptyList()
    @Suppress("UNCHECKED_CAST") val top = (stats?.get("by_category") as? Map<String, Double>)?.maxByOrNull { it.value }
    val pages = listOfNotNull(
        "This month so far" to rupees0(stats?.d("spent") ?: 0.0),
        top?.let { "Most went to" to "${it.key}\n${rupees0(it.value)}" },
        stats?.list("top_merchants")?.firstOrNull()?.let { "Your favourite place" to "${it.s("merchant")}\n${it.l("count")} visits" },
        pace?.let { "Where it's heading" to it.s("headline") },
        streak?.let { "Your streak" to "${it.l("current")} days\nbest ${it.l("best")}" },
    ) + tips.take(3).map { "Worth knowing" to it }
    val pager = rememberPagerState { pages.size }
    Column(Modifier.fillMaxSize()) {
        PageHeader("Money Story")
        HorizontalPager(pager, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 28.dp), pageSpacing = 14.dp) { i ->
            val (k, v) = pages[i]
            Column(Modifier.fillMaxSize().padding(vertical = 24.dp).clip(RoundedCornerShape(36.dp)).background(if (i % 2 == 0) P.card else P.goldSoft).padding(32.dp),
                verticalArrangement = Arrangement.Center) {
                Text(k, style = T.label.copy(fontSize = 18.sp), color = P.gold); Spacer(Modifier.height(16.dp))
                Text(v, style = if (v.length < 40) T.display else T.headline, color = P.text)
            }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp), horizontalArrangement = Arrangement.Center) {
            repeat(pages.size) { i -> Box(Modifier.padding(4.dp).size(if (i == pager.currentPage) 22.dp else 8.dp, 8.dp).clip(RoundedCornerShape(4.dp)).background(if (i == pager.currentPage) P.gold else P.line)) }
        }
    }
}

// ---------------- Streak ----------------
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun StreakPage() {
    val act = rememberAct(); val toast = LocalToast.current
    val s = load { map("/api/streak") } ?: return
    PageList("Streak") {
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                GlyphIcon("flame", P.gold, 72.dp); Spacer(Modifier.height(12.dp))
                Text("${s.l("current")}", style = T.display.copy(fontSize = 72.sp), color = P.text)
                Text("day streak · best ${s.l("best")}", style = T.body, color = P.muted)
                s["milestone"]?.let { Text(it.toString(), style = T.label, color = P.gold, modifier = Modifier.padding(top = 8.dp)) }
                s["next_milestone"]?.let { Text("Next milestone: $it days", style = T.small, color = P.muted, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
        item {
            @Suppress("UNCHECKED_CAST") val days = (s["days"] as? List<Map<String, Any?>>) ?: emptyList()
            Card {
                Text("Last 5 weeks", style = T.label, color = P.muted); Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), maxItemsInEachRow = 7) {
                    days.forEach { d -> val st = d.s("state")
                        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(when (st) { "logged" -> P.gold; "zero" -> P.green.copy(alpha = 0.7f); "frozen" -> P.muted.copy(alpha = 0.5f); "today" -> P.goldSoft; else -> P.cardHi }))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Gold: logged · green: zero-spend day · grey: freeze used", style = T.small, color = P.muted)
            }
        }
        item {
            Spacer(Modifier.height(16.dp))
            Card {
                Text("How it works", style = T.title.copy(fontSize = 18.sp), color = P.text)
                listOf("Log at least one payment a day, or tap Zero day if you spent nothing.",
                    "Miss a day and a freeze keeps your streak alive. You get one each month" + (if (s["freeze_available"] == true) " (available now)." else " (used this month)."),
                    "Your bank SMS count automatically once you log them.").forEach { Text("•  $it", style = T.body, color = P.muted, modifier = Modifier.padding(top = 8.dp)) }
            }
        }
        if (s["today_done"] != true) item { Spacer(Modifier.height(16.dp)); GoldButton("I spent nothing today") { act { call("POST", "/api/streak/zero"); toast.show("Zero-spend day saved") } } }
    }
}

// ---------------- Envelopes ----------------
@Composable
fun EnvelopesPage() {
    val act = rememberAct()
    var adding by remember { mutableStateOf(false) }
    val envs = load { list("/api/envelopes") } ?: emptyList()
    PageList("Envelopes", action = { Text("+ New", style = T.label.copy(fontWeight = FontWeight.Bold, fontSize = 17.sp), color = P.gold, modifier = Modifier.tap { adding = true }.padding(8.dp)) }) {
        item { Text("Separate pots for trips, festivals or big plans. They don't count toward your monthly budgets, and archive themselves when they end.", style = T.body, color = P.muted, modifier = Modifier.padding(bottom = 12.dp)) }
        if (envs.isEmpty()) item { Card { EmptyState("No envelopes yet", "Create one, then link payments to it when you add or edit them.") { GlyphIcon("envelope", P.gold, 56.dp) } } }
        items(envs, key = { it.l("id") }) { e ->
            Card(Modifier.padding(bottom = 12.dp)) {
                Row { Text(e.s("name"), style = T.title.copy(fontSize = 19.sp), color = P.text, modifier = Modifier.weight(1f)); Text("${e.l("pct")}%", style = T.label, color = P.gold) }
                Spacer(Modifier.height(12.dp)); ProgressBar((e.d("spent") / e.d("budget").coerceAtLeast(1.0)).toFloat(), color = if (e.d("left") < 0) P.red else P.gold); Spacer(Modifier.height(10.dp))
                Row { Text("${rupees0(e.d("spent"))} of ${rupees0(e.d("budget"))}", style = T.small, color = P.muted, modifier = Modifier.weight(1f))
                    Text(e["days_left"]?.let { "${it} days left" } ?: "No end date", style = T.small, color = P.muted) }
                Text("Delete", style = T.small, color = P.red, modifier = Modifier.padding(top = 10.dp).tap { act { call("DELETE", "/api/envelopes/${e.l("id")}") } })
            }
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }; var amount by remember { mutableStateOf("") }; var months by remember { mutableIntStateOf(1) }
        Sheet({ adding = false }) {
            Text("New envelope", style = T.title, color = P.text); Spacer(Modifier.height(14.dp))
            Field(name, { name = it }, "Name (Goa trip, Diwali)"); Spacer(Modifier.height(10.dp))
            Field(amount, { amount = it.filter { c -> c.isDigit() } }, "₹ amount", number = true); Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(0 to "No end", 1 to "1 month", 3 to "3 months", 12 to "1 year").forEach { (m, l) -> Pill(l, months == m) { months = m } } }
            Spacer(Modifier.height(16.dp))
            GoldButton("Create") { act { call("POST", "/api/envelopes", mapOf("name" to name, "budget" to (amount.toDoubleOrNull() ?: 0.0),
                "end_date" to (if (months == 0) null else LocalDate.now().plusMonths(months.toLong()).toString()))) }; adding = false }
        }
    }
}

// ---------------- Recurring ----------------
@Composable
fun RecurringPage() {
    val act = rememberAct(); var adding by remember { mutableStateOf(false) }
    val subs = load { map("/api/subscriptions") }
    val list = subs?.list("subscriptions") ?: emptyList()
    PageList("Recurring", action = { Text("+ Add", style = T.label.copy(fontWeight = FontWeight.Bold, fontSize = 17.sp), color = P.gold, modifier = Modifier.tap { adding = true }.padding(8.dp)) }) {
        item { Card(Modifier.padding(bottom = 12.dp)) {
            Text("Monthly recurring cost", style = T.label, color = P.muted)
            Text(rupees0((subs?.d("monthly_total") ?: 0.0) + (subs?.d("fixed_monthly_total") ?: 0.0)), style = T.amount, color = P.gold)
            Text("Detected from your payments, or added by you", style = T.small, color = P.muted)
        } }
        if (list.isEmpty()) item { Card { EmptyState("Nothing recurring yet", "Bills that repeat (rent, Spotify, phone recharge) show up here after two similar payments, or add one.") { GlyphIcon("repeat", P.gold, 56.dp) } } }
        items(list, key = { it.l("id") }) { s ->
            Card(Modifier.padding(bottom = 10.dp), pad = 16.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.s("name"), style = T.body.copy(fontWeight = FontWeight.SemiBold), color = P.text)
                        Text("${rupees(s.d("amount"))} ${s.s("period")}" + (if (s.s("status") == "active" && s.s("next_date").isNotBlank()) " · next ${dayLabel(s.s("next_date"))}" else " · ${s.s("status")}"), style = T.small, color = P.muted)
                    }
                    Text(if (s.s("status") == "active") "Cancel" else "Active", style = T.label, color = P.gold, modifier = Modifier.tap {
                        act { call("PATCH", "/api/subscriptions/${s.l("id")}", mapOf("status" to if (s.s("status") == "active") "cancelled" else "active")) } }.padding(8.dp))
                }
            }
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }; var amount by remember { mutableStateOf("") }; var period by remember { mutableStateOf("monthly") }
        Sheet({ adding = false }) {
            Text("Add recurring", style = T.title, color = P.text); Spacer(Modifier.height(14.dp))
            Field(name, { name = it }, "Name (Rent, Spotify)"); Spacer(Modifier.height(10.dp))
            Field(amount, { amount = it.filter { c -> c.isDigit() || c == '.' } }, "₹ amount", number = true); Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("weekly", "monthly", "quarterly", "yearly").forEach { p -> Pill(p.replaceFirstChar { it.uppercase() }, period == p) { period = p } } }
            Spacer(Modifier.height(16.dp))
            GoldButton("Add") { act { call("POST", "/api/subscriptions", mapOf("name" to name, "amount" to (amount.toDoubleOrNull() ?: 0.0), "period" to period)) }; adding = false }
        }
    }
}

// ---------------- Category budgets ----------------
@Composable
fun CategoryBudgetsPage() {
    val act = rememberAct()
    val settings = load { map("/api/settings") }
    val month = LocalDate.now().toString().take(7)
    val ins = load { map("/api/insights?month=$month") }
    @Suppress("UNCHECKED_CAST") val limits = (settings?.get("category_limits") as? Map<String, Any?>)?.mapValues { (it.value as? Number)?.toDouble() ?: 0.0 } ?: emptyMap()
    @Suppress("UNCHECKED_CAST") val spent = ((ins?.get("stats") as? Map<String, Any?>)?.get("by_category") as? Map<String, Double>) ?: emptyMap()
    @Suppress("UNCHECKED_CAST") val recs = ((ins?.get("recommendations") as? Map<String, Any?>)?.get("categories") as? List<Map<String, Any?>>)?.associateBy { it.s("category") } ?: emptyMap()
    val cats = load { list("/api/categories").map { it.toString() } } ?: emptyList()
    var editing by remember { mutableStateOf<String?>(null) }
    PageList("Budgets") {
        item { Text("Monthly limits per category. They reset on the 1st.", style = T.body, color = P.muted, modifier = Modifier.padding(bottom = 12.dp)) }
        items(cats, key = { it }) { c ->
            val lim = limits[c] ?: 0.0; val sp = spent[c] ?: 0.0
            Card(Modifier.padding(bottom = 10.dp), pad = 16.dp, onClick = { editing = c }) {
                Row { Text(c, style = T.body.copy(fontWeight = FontWeight.SemiBold), color = P.text, modifier = Modifier.weight(1f))
                    Text(if (lim > 0) "${rupees0(sp)} / ${rupees0(lim)}" else rupees0(sp), style = T.label, color = if (lim > 0 && sp > lim) P.red else P.muted) }
                if (lim > 0) { Spacer(Modifier.height(10.dp)); ProgressBar((sp / lim).toFloat(), color = if (sp > lim) P.red else if (sp > lim * 0.8) P.gold else P.green, height = 8.dp) }
                else recs[c]?.let { Text("Suggested ${rupees0(it.d("suggested"))} (average of the last 3 months)", style = T.small, color = P.gold, modifier = Modifier.padding(top = 6.dp)) }
            }
        }
    }
    editing?.let { c ->
        var v by remember(c) { mutableStateOf(limits[c]?.takeIf { it > 0 }?.toLong()?.toString() ?: recs[c]?.l("suggested")?.toString() ?: "") }
        Sheet({ editing = null }) {
            Text("$c limit", style = T.title, color = P.text); Spacer(Modifier.height(14.dp))
            Field(v, { v = it.filter { ch -> ch.isDigit() } }, "₹ per month", number = true, big = true); Spacer(Modifier.height(16.dp))
            GoldButton("Save") { act { call("PUT", "/api/settings", mapOf("category_limits" to limits + (c to (v.toDoubleOrNull() ?: 0.0)))) }; editing = null }
        }
    }
}

// ---------------- Settings ----------------
@Composable
fun SettingsPage() {
    val act = rememberAct(); val toast = LocalToast.current; val native = LocalNative.current; val refresh = LocalRefresh.current
    val s = load { map("/api/settings") } ?: return
    val st = remember(refresh.tick) { native.status() }
    var name by remember(s) { mutableStateOf(s.s("user_name")) }
    var budget by remember { mutableStateOf(false) }
    var months by remember { mutableIntStateOf(3) }
    var deleting by remember { mutableStateOf(false) }
    fun set(k: String, v: Any?) = act { call("PUT", "/api/settings", mapOf(k to v)) }
    PageList("Settings") {
        item { Group("You") {
            Field(name, { name = it }, "Your name"); Spacer(Modifier.height(10.dp)); GhostButton("Save name", Modifier.fillMaxWidth()) { set("user_name", name) }
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "System", "dark" to "Dark", "light" to "Light").forEach { (k, l) -> Pill(l, s.s("theme").ifBlank { "system" } == k) { set("theme", k) } }
            }
            Row2("Monthly budget", rupees0(s.d("monthly_budget"))) { budget = true }
        } }
        item { Group("On-device AI") {
            val pct = st["model_download_pct"]
            Text(when { st["model_ready"] == true -> "Ready. Reads your SMS on this phone."; pct != null -> "Downloading… $pct%"; else -> "Not installed (about 1.6 GB, one time)" }, style = T.body, color = P.muted)
            if (pct != null) { Spacer(Modifier.height(10.dp)); ProgressBar(((pct as Number).toFloat()) / 100f) }
            Spacer(Modifier.height(10.dp))
            if (st["model_ready"] == true) GhostButton("Remove model", Modifier.fillMaxWidth(), P.red) { native.deleteModel(); refresh.bump() }
            else if (pct == null) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("Download", Modifier.weight(1f), P.gold) { native.downloadModel(); toast.show("Downloading. You can keep using Artha"); refresh.bump() }
                GhostButton("Import file", Modifier.weight(1f)) { native.importModel() }
            }
        } }
        item { Group("Bank SMS") {
            Row2("Read new bank SMS", if (st["sms"] == true) (if (s["auto_capture_sms"] != false) "On" else "Paused") else "Allow") {
                if (st["sms"] != true) native.requestSms() else set("auto_capture_sms", s["auto_capture_sms"] == false) }
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 3, 6, 12).forEach { m -> Pill("${m}m", months == m) { months = m } }
                Spacer(Modifier.weight(1f)); Text("Import past SMS", style = T.label, color = P.gold, modifier = Modifier.tap {
                    if (st["model_ready"] != true) toast.show("Download the AI model first") else native.importSms(months) })
            }
            Toggle("Log confident payments without asking", s["auto_log_confident"] == true) { set("auto_log_confident", it) }
            Toggle("Log confident imported payments automatically", s["auto_log_history"] != false) { set("auto_log_history", it) }
            Toggle("Track money received", s["track_received"] == true) { set("track_received", it) }
            Toggle("Ask after each payment (notification)", s["notify_logged"] != false) { if (it && st["post_notifications"] != true) native.requestNotifications(); set("notify_logged", it) }
            Text("Android may say \"Restricted setting\". Open App info, tap the three dots, Allow restricted settings, then try again.", style = T.small, color = P.muted, modifier = Modifier.padding(top = 10.dp).tap { native.openAppInfo() })
        } }
        item { Group("Evening reminder") {
            Toggle("Remind me to log my day", s["reminder_on"] != false) { set("reminder_on", it); native.scheduleReminder() }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(19, 20, 21, 22).forEach { h -> Pill("${h - 12} pm", s.l("reminder_hour") == h.toLong()) { set("reminder_hour", h); native.scheduleReminder() } }
            }
        } }
        item { Group("Privacy") {
            Toggle("Lock with fingerprint or screen lock", s["app_lock"] == true) { set("app_lock", it) }
            Toggle("Hide from recent apps, block screenshots", s["hide_in_recents"] != false) { set("hide_in_recents", it); native.applyPrivacy() }
            Text("Everything stays on this phone. No account, no server, excluded from cloud backup.", style = T.small, color = P.muted, modifier = Modifier.padding(top = 10.dp))
        } }
        item { Group("Your data") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("Export CSV", Modifier.weight(1f)) { act { val f = call("GET", "/api/export.csv") as Map<*, *>; native.saveFile(f["filename"].toString(), f["content"].toString()) } }
                GhostButton("Full backup", Modifier.weight(1f)) { act { val f = call("GET", "/api/export.json") as Map<*, *>; native.saveFile(f["filename"].toString(), f["content"].toString()) } }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("Import statement", Modifier.weight(1f)) { native.pickText { t -> act { val r = call("POST", "/api/import/text", mapOf("text" to t)) as Map<*, *>; native.analyze(); toast.show("Reading ${r["queued"]} rows") } } }
                GhostButton("Restore backup", Modifier.weight(1f)) { native.pickText { t -> act { call("POST", "/api/data/restore", mapOf("content" to t, "confirm" to "RESTORE")); toast.show("Backup restored") } } }
            }
            Spacer(Modifier.height(10.dp))
            GhostButton("Delete data…", Modifier.fillMaxWidth(), P.red) { deleting = true }
            Text("Artha ${st["version"] ?: ""}", style = T.small, color = P.muted, modifier = Modifier.padding(top = 14.dp))
        } }
    }
    if (budget) BudgetSheet(s.d("monthly_budget")) { budget = false }
    if (deleting) {
        var scope by remember { mutableStateOf("transactions") }; var confirm by remember { mutableStateOf("") }
        Sheet({ deleting = false }) {
            Text("Delete data", style = T.title, color = P.text); Spacer(Modifier.height(12.dp))
            listOf("transactions" to "All transactions, splits and subscriptions", "learned" to "What Artha learned (payees, rules)", "everything" to "Everything (factory reset)").forEach { (k, l) ->
                Pill(l, scope == k, Modifier.padding(bottom = 8.dp)) { scope = k } }
            Field(confirm, { confirm = it }, "Type DELETE to confirm"); Spacer(Modifier.height(14.dp))
            GoldButton("Delete permanently", enabled = confirm == "DELETE") { act { call("POST", "/api/data/delete", mapOf("scope" to scope, "confirm" to "DELETE")); toast.show("Deleted") }; deleting = false }
        }
    }
}

@Composable private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, style = T.label, color = P.gold, modifier = Modifier.padding(top = 20.dp, bottom = 10.dp, start = 4.dp))
    Card(content = content)
}

@Composable private fun Row2(label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp).tap(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = T.body, color = P.text, modifier = Modifier.weight(1f)); Text(value, style = T.label, color = P.gold); GlyphIcon("chevron", P.muted, 20.dp)
    }
}

@Composable private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val h = haptic()
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = T.body, color = P.text, modifier = Modifier.weight(1f))
        Switch(on, { h(HapticFeedbackConstants.CLOCK_TICK); onChange(it) }, colors = SwitchDefaults.colors(checkedTrackColor = P.gold, checkedThumbColor = P.onGold, uncheckedTrackColor = P.cardHi))
    }
}
