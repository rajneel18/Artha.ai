package ai.artha.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val DAY_MS = 86_400_000L

/** "Today", "Yesterday" or "Mon, 14 Nov". */
fun shortDay(d: LocalDate): String = dayLabel(d.toString())

/** Today / Yesterday / any earlier day from a calendar. Future days can't be picked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayChooser(day: LocalDate, onPick: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    var calendar by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill("Today", day == today) { onPick(today) }
        Pill("Yesterday", day == today.minusDays(1)) { onPick(today.minusDays(1)) }
        val other = day != today && day != today.minusDays(1)
        Pill(if (other) day.format(DateTimeFormatter.ofPattern("d MMM")) else "Pick a day", other) { calendar = true }
    }
    if (calendar) {
        val endOfToday = (today.toEpochDay() + 1) * DAY_MS - 1
        val state = rememberDatePickerState(initialSelectedDateMillis = day.toEpochDay() * DAY_MS,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= endOfToday
                override fun isSelectableYear(year: Int) = year <= today.year
            })
        val colors = DatePickerDefaults.colors(containerColor = P.card, titleContentColor = P.muted, headlineContentColor = P.text,
            weekdayContentColor = P.muted, dayContentColor = P.text, selectedDayContainerColor = P.gold, selectedDayContentColor = P.onGold,
            todayContentColor = P.gold, todayDateBorderColor = P.gold, yearContentColor = P.text, selectedYearContainerColor = P.gold,
            selectedYearContentColor = P.onGold, disabledDayContentColor = P.line, navigationContentColor = P.text,
            subheadContentColor = P.muted, dividerColor = P.line)
        DatePickerDialog(onDismissRequest = { calendar = false }, colors = colors,
            confirmButton = { Text("Done", style = T.label, color = P.gold, modifier = Modifier.clip(RoundedCornerShape(12.dp)).tap {
                state.selectedDateMillis?.let { onPick(LocalDate.ofEpochDay(it / DAY_MS)) }; calendar = false }.padding(14.dp)) },
            dismissButton = { Text("Cancel", style = T.label, color = P.muted, modifier = Modifier.clip(RoundedCornerShape(12.dp)).tap { calendar = false }.padding(14.dp)) }) {
            DatePicker(state, colors = colors, showModeToggle = false)
        }
    }
}

/** Home card: today's daily budget at a glance. */
@Composable
fun TodayCard() {
    val nav = LocalNav.current
    val d = load { map("/api/daily?date=${LocalDate.now()}") } ?: return
    val a = d.d("allowance"); val sp = d.d("spent"); val left = a - sp
    Card(Modifier.padding(bottom = 16.dp), onClick = { nav.go("daily") }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Today", style = T.body, color = P.muted, modifier = Modifier.weight(1f))
            Text("Daily log", style = T.label, color = P.gold); GlyphIcon("chevron", P.gold, 18.dp)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(rupees0(kotlin.math.abs(left)), style = T.amount, color = if (left < 0) P.red else P.text)
            Text(if (left < 0) "  over today" else "  left today", style = T.body, color = P.muted, modifier = Modifier.padding(bottom = 6.dp))
        }
        Spacer(Modifier.height(12.dp))
        ProgressBar(if (a > 0) (sp / a).toFloat() else 0f, color = if (left < 0) P.red else P.gold, height = 10.dp)
        Spacer(Modifier.height(8.dp))
        Text("${rupees0(sp)} of ${rupees0(a)} daily budget", style = T.small, color = P.muted)
    }
}

/** One day at a time: its budget, what you spent and the payments, with a strip to move between days. */
@Composable
fun DailyPage() {
    var day by remember { mutableStateOf(LocalDate.now()) }
    var adding by remember { mutableStateOf(false) }; var editing by remember { mutableStateOf(false) }
    val d = load(day) { map("/api/daily?date=$day") }
    val days = d?.list("days") ?: emptyList()
    val strip = rememberLazyListState()
    LaunchedEffect(days.size) { if (days.isNotEmpty()) strip.scrollToItem((days.indexOfFirst { it.s("date") == day.toString() }.takeIf { it >= 0 } ?: days.lastIndex - 4).coerceAtLeast(0)) }
    Column(Modifier.fillMaxSize()) {
        PageHeader("Daily log") { Box(Modifier.size(48.dp).clip(CircleShape).tap { editing = true }, contentAlignment = Alignment.Center) { GlyphIcon("sliders", P.text, 24.dp) } }
        LazyRow(state = strip, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(days.size) { i ->
                val x = days[i]; val date = LocalDate.parse(x.s("date")); val sel = date == day
                Column(Modifier.width(52.dp).clip(RoundedCornerShape(18.dp)).background(if (sel) P.gold else P.card).tap { day = date }.padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(date.format(DateTimeFormatter.ofPattern("EEE")), style = T.small, color = if (sel) P.onGold else P.muted)
                    Text(date.dayOfMonth.toString(), style = T.title.copy(fontSize = 19.sp), color = if (sel) P.onGold else P.text)
                    Spacer(Modifier.height(4.dp))
                    val dot = when (x.s("state")) { "over" -> P.red; "under" -> P.green; "zero" -> P.gold; else -> Color0 }
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (sel && dot != Color0) P.onGold else dot))
                }
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 120.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(shortDay(day), style = T.title, color = P.text, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                DayChooser(day) { day = it }
                Spacer(Modifier.height(14.dp))
            }
            if (d != null) item {
                val a = d.d("allowance"); val sp = d.d("spent"); val left = a - sp
                Card(Modifier.animateContentSize()) {
                    Text("Spent", style = T.body, color = P.muted)
                    Text(rupees0(sp), style = T.amount, color = if (left < 0) P.red else P.gold)
                    Spacer(Modifier.height(14.dp))
                    ProgressBar(if (a > 0) (sp / a).toFloat() else 0f, color = if (left < 0) P.red else P.gold)
                    Spacer(Modifier.height(14.dp))
                    Row {
                        Column(Modifier.weight(1f)) {
                            Text(if (left < 0) "Over" else "Left", style = T.small, color = P.muted)
                            Text(rupees0(kotlin.math.abs(left)), style = T.title, color = if (left < 0) P.red else P.green)
                        }
                        Column(horizontalAlignment = Alignment.End) { Text("Daily budget", style = T.small, color = P.muted); Text(rupees0(a), style = T.title, color = P.text) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                val wa = d.d("week_allowance"); val ws = d.d("week_spent")
                Text("Last 7 days: ${rupees0(ws)} of ${rupees0(wa)}" + if (ws <= wa) ", ${rupees0(wa - ws)} saved" else ", ${rupees0(ws - wa)} over",
                    style = T.small, color = P.muted)
                Spacer(Modifier.height(16.dp))
                GoldButton("Add to ${shortDay(day).let { if (it == "Today" || it == "Yesterday") it.lowercase() else it }}", icon = { GlyphIcon("plus", P.onGold, 20.dp) }) { adding = true }
                SectionTitle("Payments")
            }
            val txs = d?.list("transactions") ?: emptyList()
            if (d != null && txs.isEmpty()) item {
                Text("Nothing logged for this day yet.", style = T.body, color = P.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp))
            } else if (txs.isNotEmpty()) item { Card(pad = 8.dp) { txs.forEach { TxRow(it) } } }
        }
    }
    if (adding) AddExpenseSheet(day) { adding = false }
    if (editing && d != null) DailyBudgetSheet(d.d("allowance"), d.d("suggested")) { editing = false }
}

private val Color0 = androidx.compose.ui.graphics.Color.Transparent

@Composable
fun DailyBudgetSheet(current: Double, suggested: Double, onDone: () -> Unit) {
    val act = rememberAct(); val toast = LocalToast.current
    var v by remember { mutableStateOf(if (current > 0) current.toLong().toString() else "") }
    Sheet(onDone) {
        Text("Daily budget", style = T.title, color = P.text); Spacer(Modifier.height(4.dp))
        Text("How much you want to spend in a normal day. Changes apply from today; past days keep their budget.", style = T.body, color = P.muted)
        Spacer(Modifier.height(16.dp))
        Field(v, { v = it.filter { c -> c.isDigit() } }, "500", number = true, big = true)
        if (suggested > 0 && suggested.toLong().toString() != v) {
            Spacer(Modifier.height(10.dp))
            Pill("Use ${rupees0(suggested)} (monthly budget ÷ days)", false) { v = suggested.toLong().toString() }
        }
        Spacer(Modifier.height(16.dp))
        GoldButton("Save", enabled = (v.toDoubleOrNull() ?: 0.0) > 0) {
            act { call("PUT", "/api/settings", mapOf("daily_allowance" to v.toDouble())); toast.show("Daily budget set to ${rupees0(v.toDouble())}") }
            onDone()
        }
    }
}
