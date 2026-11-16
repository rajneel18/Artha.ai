package ai.artha.ui

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the screens can ask of the phone (permissions, the AI model, voice, files). Implemented by MainActivity. */
interface Native {
    fun status(): Map<String, Any?>
    fun requestSms()
    fun requestNotifications()
    fun openAppInfo()
    fun importSms(months: Int)
    fun downloadModel()
    fun importModel()
    fun deleteModel()
    fun analyze()
    fun voice(onText: (String) -> Unit)
    fun pickText(onText: (String) -> Unit)
    fun saveFile(name: String, content: String)
    fun clipboard(): String
    fun copy(text: String)
    fun clearCrash()
    fun applyPrivacy()
    fun scheduleReminder()
}

val LocalNative = staticCompositionLocalOf<Native> { error("no native") }

/** Simple toast at the bottom; optional action (e.g. Undo). */
class Toaster { var msg by mutableStateOf<String?>(null); var action by mutableStateOf<Pair<String, () -> Unit>?>(null); var id by mutableIntStateOf(0)
    fun show(m: String, act: Pair<String, () -> Unit>? = null) { msg = m; action = act; id++ } }
val LocalToast = staticCompositionLocalOf { Toaster() }

/** Full-screen pages that slide over the tabs (Envelopes, Settings, ...). */
class Nav { val stack = mutableStateListOf<String>(); fun go(s: String) { stack.add(s) }; fun back() = if (stack.isNotEmpty()) { stack.removeAt(stack.lastIndex); true } else false }
val LocalNav = staticCompositionLocalOf { Nav() }

@Composable
fun ArthaApp(repo: Repo, native: Native) {
    val refresher = remember { Refresher() }
    val toaster = remember { Toaster() }
    val nav = remember { Nav() }
    var themePref by remember { mutableStateOf("system") }
    LaunchedEffect(refresher.tick) { themePref = (try { repo.map("/api/settings")["theme"] as? String } catch (_: Exception) { null }) ?: "system" }
    val dark = when (themePref) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    CompositionLocalProvider(LocalRepo provides repo, LocalRefresh provides refresher, LocalToast provides toaster, LocalNav provides nav,
        LocalNative provides native, LocalPalette provides if (dark) DarkGold else LightGold) {
        MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = P.gold, surface = P.card, background = P.bg) else lightColorScheme(primary = P.gold, surface = P.card, background = P.bg)) {
            Shell()
        }
    }
}

@Composable
private fun Shell() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    val nav = LocalNav.current
    BackHandler(enabled = nav.stack.isNotEmpty() || tab != 0) { if (!nav.back()) tab = 0 }

    Box(Modifier.fillMaxSize().background(P.bg)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                AnimatedContent(tab, transitionSpec = {
                    (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { (if (targetState > initialState) 1 else -1) * it / 6 }) togetherWith fadeOut(tween(160))
                }, label = "tab") { t ->
                    when (t) { 0 -> HomeTab(); 1 -> TransactionsTab(); 2 -> SplitsTab(); else -> AnalyticsTab() }
                }
            }
            BottomBar(tab) { tab = it }
        }
        // The gold add button floats above the bar on Home, Transactions and Analytics.
        AnimatedVisibility(tab != 2 && nav.stack.isEmpty(), Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 108.dp),
            enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
            Box(Modifier.size(68.dp).shadow(16.dp, CircleShape, spotColor = P.gold).clip(CircleShape).background(P.gold)
                .tap(haptic = HapticFeedbackConstants.CONFIRM) { adding = true }, contentAlignment = Alignment.Center) { GlyphIcon("plus", P.onGold, 30.dp) }
        }
        // Full-screen pages slide in from the right.
        AnimatedContent(nav.stack.lastOrNull(), transitionSpec = {
            if (targetState != null && (initialState == null || nav.stack.size > 0)) slideInHorizontally(tween(280)) { it } + fadeIn(tween(200)) togetherWith fadeOut(tween(200))
            else fadeIn(tween(150)) togetherWith slideOutHorizontally(tween(260)) { it } + fadeOut(tween(200))
        }, label = "page") { page ->
            if (page != null) Box(Modifier.fillMaxSize().background(P.bg)) { Page(page) }
        }
        ToastHost(Modifier.align(Alignment.BottomCenter).padding(bottom = 104.dp))
        if (adding) AddExpenseSheet { adding = false }
    }
}

@Composable
private fun BottomBar(tab: Int, onTab: (Int) -> Unit) {
    val items = listOf("Home" to "home", "Transactions" to "receipt", "Splits" to "people", "Analytics" to "bars")
    Row(Modifier.fillMaxWidth().background(P.card).navigationBarsPadding().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        items.forEachIndexed { i, (label, icon) ->
            val on = i == tab
            Column(Modifier.weight(1f).tap(haptic = HapticFeedbackConstants.CLOCK_TICK) { onTab(i) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(64.dp, 36.dp).clip(RoundedCornerShape(18.dp)).background(if (on) P.goldSoft else Color.Transparent), contentAlignment = Alignment.Center) {
                    GlyphIcon(icon, if (on) P.gold else P.muted, 26.dp)
                }
                Spacer(Modifier.height(4.dp))
                Text(label, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) P.gold else P.muted)
            }
        }
    }
}

@Composable
private fun ToastHost(modifier: Modifier) {
    val t = LocalToast.current
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(t.id) { if (t.msg != null) { visible = true; delay(if (t.action != null) 5000 else 2800); visible = false } }
    AnimatedVisibility(visible, modifier.padding(horizontal = 16.dp), enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (P.dark) Color(0xFF2C2C2C) else Color(0xFF2B241C)).padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(t.msg ?: "", color = Color.White, style = T.body, modifier = Modifier.weight(1f))
            t.action?.let { (label, act) -> Text(label, color = P.gold, style = T.label.copy(fontWeight = FontWeight.Bold), modifier = Modifier.tap { visible = false; act() }.padding(start = 12.dp)) }
        }
    }
}

/** Runs an engine call, refreshes everything after, and shows errors as toasts. */
@Composable
fun rememberAct(): (suspend Repo.() -> Unit) -> Unit {
    val repo = LocalRepo.current; val refresh = LocalRefresh.current; val toast = LocalToast.current; val scope = rememberCoroutineScope()
    return { block -> scope.launch { try { repo.block(); refresh.bump() } catch (e: Exception) { toast.show(e.message ?: "Something went wrong") } } }
}

@Composable
fun PageHeader(title: String, action: (@Composable () -> Unit)? = null) {
    val nav = LocalNav.current
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 20.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(CircleShape).tap { nav.back() }, contentAlignment = Alignment.Center) { GlyphIcon("back", P.text, 26.dp) }
        Text(title, style = T.title.copy(fontSize = 26.sp, fontWeight = FontWeight.ExtraBold), color = P.text, modifier = Modifier.weight(1f).padding(start = 4.dp))
        action?.invoke()
    }
}

@Composable
private fun Page(name: String) {
    when (name) {
        "envelopes" -> EnvelopesPage()
        "recurring" -> RecurringPage()
        "ask" -> AskPage()
        "story" -> StoryPage()
        "streak" -> StreakPage()
        "settings" -> SettingsPage()
        "detected" -> DetectedPage()
        "quick" -> QuickAddPage()
        "daily" -> DailyPage()
        "categories" -> CategoryBudgetsPage()
        "all" -> Column(Modifier.fillMaxSize()) { PageHeader("Transactions"); TransactionsTab() }
        else -> Box(Modifier.fillMaxSize())
    }
}
