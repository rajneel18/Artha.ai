package ai.artha.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun HomeTab() {
    val nav = LocalNav.current
    val native = LocalNative.current
    val settings = load { map("/api/settings") }
    val pace = load { map("/api/pace") }
    val streak = load { map("/api/streak") }
    val det = load { map("/api/detected") }
    val review = load { list("/api/review") }
    val recent = load { list("/api/transactions?limit=5") }
    val st = remember(LocalRefresh.current.tick) { native.status() }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 200.dp)) {
        item { Greeting(settings?.s("user_name").orEmpty(), (det?.list("items")?.size ?: 0) + (review?.size ?: 0)) }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 22.dp).tap { nav.go("story") }, verticalAlignment = Alignment.CenterVertically) {
                Text(pace?.s("headline") ?: " ", style = T.headline, color = P.text, modifier = Modifier.weight(1f))
                GlyphIcon("chevron", P.muted, 26.dp)
            }
        }
        st["crash_report"]?.let { item { CrashCard(it.toString()) } }
        if (st["model_ready"] != true || st["sms"] != true) item { SetupCard(st) }
        item { DetectedSummary(det, review) }
        item { TodayCard() }
        item { MonthlySpend(pace) }
        item { StreakCard(streak) }
        item { Tools() }
        item { SectionTitle("Recent", "See all") { nav.go("all") } }
        val txs = recent ?: emptyList()
        if (txs.isEmpty()) item { Card { Text("Your payments will show up here as your bank SMS arrive, or tap + to add one.", style = T.body, color = P.muted) } }
        else item { Card(pad = 8.dp) { txs.forEach { TxRow(it) } } }
    }
}

@Composable
private fun Greeting(name: String, alerts: Int) {
    val nav = LocalNav.current
    val hour = LocalTime.now().hour
    val hello = when { hour < 12 -> "Good morning"; hour < 17 -> "Good afternoon"; else -> "Good evening" }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (name.isBlank()) hello else "$hello, $name", style = T.title.copy(fontSize = 24.sp), color = P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(LocalDate.now().format(DateTimeFormatter.ofPattern("d MMM yyyy")), style = T.body, color = P.muted.copy(alpha = 0.7f))
        }
        Box {
            IconCircle("bell") { nav.go("detected") }
            if (alerts > 0) Box(Modifier.align(Alignment.TopEnd).size(20.dp).clip(CircleShape).background(P.red), contentAlignment = Alignment.Center) {
                Text("$alerts", fontSize = 11.sp, color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(52.dp).clip(CircleShape).background(GoldGradient).tap { nav.go("settings") }, contentAlignment = Alignment.Center) {
            Text(name.firstOrNull()?.uppercase() ?: "A", style = T.title, color = P.onGold)
        }
    }
}

@Composable
private fun SetupCard(st: Map<String, Any?>) {
    val nav = LocalNav.current
    Card(Modifier.padding(bottom = 16.dp), border = P.gold.copy(alpha = 0.4f), onClick = { nav.go("settings") }) {
        Text("Finish setting up", style = T.title.copy(fontSize = 18.sp), color = P.text)
        Spacer(Modifier.height(6.dp))
        val steps = buildList {
            if (st["model_ready"] != true) add(if (st["model_download_pct"] != null) "AI model downloading… ${st["model_download_pct"]}%" else "Download the on-device AI model (1.6 GB, one time)")
            if (st["sms"] != true) add("Allow bank SMS so payments log themselves")
        }
        steps.forEach { Text("•  $it", style = T.body, color = P.muted) }
    }
}

@Composable
private fun CrashCard(report: String) {
    val native = LocalNative.current; val toast = LocalToast.current; val refresh = LocalRefresh.current
    Card(Modifier.padding(bottom = 16.dp), border = P.red.copy(alpha = 0.5f)) {
        Text("Artha stopped last time", style = T.title.copy(fontSize = 18.sp), color = P.text)
        Text("It has switched to a safer mode. Copy the report to send it.", style = T.body, color = P.muted)
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Copy report", color = P.gold) { native.copy(report); toast.show("Report copied") }
            GhostButton("Dismiss") { native.clearCrash(); refresh.bump() }
        }
    }
}

@Composable
private fun DetectedSummary(det: Map<String, Any?>?, review: List<Map<String, Any?>>?) {
    val nav = LocalNav.current
    val items = det?.list("items") ?: emptyList()
    val groups = det?.list("groups") ?: emptyList()
    val pending = det?.l("pending") ?: 0
    val questions = review?.size ?: 0
    if (items.isEmpty() && groups.isEmpty() && pending == 0L && questions == 0) return
    Card(Modifier.padding(bottom = 16.dp).animateContentSize(), border = P.gold.copy(alpha = 0.5f), onClick = { nav.go("detected") }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(when {
                    items.isNotEmpty() -> "${items.size} payment${if (items.size > 1) "s" else ""} to confirm"
                    groups.isNotEmpty() -> "Imported history needs a look"
                    questions > 0 -> "$questions question${if (questions > 1) "s" else ""} about payments"
                    else -> "Reading your bank SMS…"
                }, style = T.title.copy(fontSize = 18.sp), color = P.text)
                Text(if (pending > 0) "$pending message${if (pending > 1) "s" else ""} still being read on your phone" else "Tap to review and log", style = T.body, color = P.muted)
            }
            GlyphIcon("chevron", P.gold, 26.dp)
        }
        items.take(2).forEach { d ->
            Spacer(Modifier.height(10.dp))
            Row { Text(d.s("party"), style = T.body, color = P.text, modifier = Modifier.weight(1f), maxLines = 1); Text(rupees(d.d("amount")), style = T.body.copy(fontWeight = FontWeight.SemiBold), color = P.text) }
        }
    }
}

@Composable
private fun MonthlySpend(pace: Map<String, Any?>?) {
    var editing by remember { mutableStateOf(false) }
    val spent = pace?.d("spent") ?: 0.0; val budget = pace?.d("budget") ?: 0.0; val left = budget - spent
    Card(Modifier.padding(bottom = 16.dp), onClick = { editing = true }) {
        Text("Total Monthly Spend", style = T.body, color = P.muted)
        Spacer(Modifier.height(6.dp))
        Text(rupees0(spent), style = T.amount, color = P.gold)
        Spacer(Modifier.height(18.dp))
        ProgressBar(if (budget > 0) (spent / budget).toFloat() else 0f, color = if (left < 0) P.red else P.gold)
        Spacer(Modifier.height(18.dp))
        Row {
            Column(Modifier.weight(1f)) { Text("Remaining", style = T.small, color = P.muted); Text(rupees0(left), style = T.title, color = if (left < 0) P.red else P.green) }
            Column(horizontalAlignment = Alignment.End) { Text("Budget", style = T.small, color = P.muted); Text(rupees0(budget), style = T.title, color = P.text) }
        }
        pace?.let { if (it["normal_day"] != null || it.d("safe_per_day") > 0) {
            Spacer(Modifier.height(10.dp))
            Text("Safe to spend ${rupees0(it.d("safe_per_day"))}/day" + (it["normal_day"]?.let { n -> " · your normal ${rupees0((n as Number).toDouble())}/day" } ?: ""), style = T.small, color = P.muted)
        } }
    }
    if (editing) BudgetSheet(budget) { editing = false }
}

@Composable
fun BudgetSheet(current: Double, onDone: () -> Unit) {
    val act = rememberAct(); var v by remember { mutableStateOf(if (current > 0) current.toLong().toString() else "") }
    Sheet(onDone) {
        Text("Monthly budget", style = T.title, color = P.text); Spacer(Modifier.height(4.dp))
        Text("How much do you want to spend in a month, excluding savings?", style = T.body, color = P.muted); Spacer(Modifier.height(16.dp))
        Field(v, { v = it.filter { c -> c.isDigit() } }, "15000", number = true, big = true); Spacer(Modifier.height(16.dp))
        GoldButton("Save") { act { call("PUT", "/api/settings", mapOf("monthly_budget" to (v.toDoubleOrNull() ?: 0.0))) }; onDone() }
    }
}

@Composable
private fun StreakCard(s: Map<String, Any?>?) {
    val nav = LocalNav.current; val act = rememberAct(); val toast = LocalToast.current
    if (s == null) return
    val current = s.l("current"); val atRisk = s["at_risk"] == true; val done = s["today_done"] == true
    Card(Modifier.padding(bottom = 4.dp), border = if (atRisk) P.gold.copy(alpha = 0.6f) else null, onClick = { nav.go("streak") }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(P.goldSoft), contentAlignment = Alignment.Center) { GlyphIcon("flame", if (current > 0) P.gold else P.muted, 28.dp) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (current > 0) "$current-day streak" else "Start your streak", style = T.title.copy(fontSize = 19.sp), color = P.text)
                Text(when { done -> "Today is logged. Nice."; atRisk -> "At risk. Log a payment or mark a zero-spend day."; else -> "Log something today to begin." }, style = T.small, color = if (atRisk) P.gold else P.muted)
            }
            if (!done) Text("Zero day", style = T.label.copy(fontWeight = FontWeight.Bold), color = P.gold,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(P.goldSoft).tap(haptic = HapticFeedbackConstants.CONFIRM) {
                    act { call("POST", "/api/streak/zero"); toast.show("Zero-spend day saved. Streak kept.") } }.padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }
}

@Composable
private fun Tools() {
    val nav = LocalNav.current
    SectionTitle("Tools")
    val tools = listOf(Triple("Daily Log", "list", "daily"), Triple("Ask Artha", "chat", "ask"), Triple("Money Story", "sparkle", "story"),
        Triple("Envelopes", "envelope", "envelopes"), Triple("Recurring", "repeat", "recurring"), Triple("Budgets", "sliders", "categories"))
    tools.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { (label, icon, page) -> ToolTile(label, Modifier.weight(1f), icon = { GlyphIcon(icon) }) { nav.go(page) } }
        }
    }
}

@Composable
fun TxRow(t: Map<String, Any?>, onClick: (() -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    val credit = t.s("kind") == "credit"
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).tap { if (onClick != null) onClick() else open = true }.padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(P.cardHi), contentAlignment = Alignment.Center) {
            Text(t.s("merchant").firstOrNull()?.uppercase() ?: "?", style = T.label.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold), color = P.gold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.s("merchant"), style = T.body.copy(fontWeight = FontWeight.SemiBold), color = P.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.s("category") + (if (t["my_share"] != null) " · your share ${rupees(t.d("my_share"))}" else ""), style = T.small, color = P.muted, maxLines = 1)
        }
        Text((if (credit) "+" else "") + rupees(t.d("amount")), style = T.body.copy(fontWeight = FontWeight.Bold), color = if (credit) P.green else P.text)
    }
    if (open) TxSheet(t.l("id")) { open = false }
}
