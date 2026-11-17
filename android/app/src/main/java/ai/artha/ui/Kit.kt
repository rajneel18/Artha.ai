package ai.artha.ui

import ai.artha.core.Api
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

// ---------------- colours ----------------
data class Palette(
    val bg: Color, val card: Color, val cardHi: Color, val line: Color, val text: Color, val muted: Color,
    val gold: Color, val goldSoft: Color, val onGold: Color, val green: Color, val red: Color, val dark: Boolean,
)

// Olive green accent on near-black (dark) or soft sage (light).
val DarkGold = Palette(
    bg = Color(0xFF0A0C09), card = Color(0xFF191C16), cardHi = Color(0xFF22261E), line = Color(0xFF2C3127),
    text = Color(0xFFF1F4EC), muted = Color(0xFF9CA392), gold = Color(0xFFA4B865), goldSoft = Color(0xFF283020),
    onGold = Color(0xFF0F130A), green = Color(0xFF5CCB8F), red = Color(0xFFEF6A5B), dark = true,
)
val LightGold = Palette(
    bg = Color(0xFFF2F4EB), card = Color(0xFFFBFCF7), cardHi = Color(0xFFE9EDDF), line = Color(0xFFDCE2CE),
    text = Color(0xFF1A1E14), muted = Color(0xFF6E7564), gold = Color(0xFF5F7530), goldSoft = Color(0xFFE2E9CF),
    onGold = Color(0xFFFBFCF7), green = Color(0xFF2E8B57), red = Color(0xFFC0442F), dark = false,
)
val LocalPalette = staticCompositionLocalOf { DarkGold }
val P: Palette @Composable get() = LocalPalette.current

val GoldGradient: Brush @Composable get() = Brush.horizontalGradient(if (P.dark) listOf(Color(0xFF7E9446), Color(0xFFC3D38C)) else listOf(Color(0xFF55692A), Color(0xFF8DA352)))

// ---------------- data access ----------------
/** Calls the on-device engine off the main thread. Throws with a readable message on errors. */
class Repo(val api: Api) {
    suspend fun call(method: String, path: String, body: Any? = null): Any? = withContext(Dispatchers.IO) {
        val r = api.handle(method, path, body?.let { ai.artha.core.toJson(it).toString() })
        if (r.status != 200) throw IllegalStateException(((r.body as? Map<*, *>)?.get("detail") as? String) ?: "Something went wrong")
        r.body
    }
    @Suppress("UNCHECKED_CAST") suspend fun map(path: String) = call("GET", path) as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") suspend fun list(path: String) = call("GET", path) as List<Map<String, Any?>>
}

val LocalRepo = staticCompositionLocalOf<Repo> { error("no repo") }

/** Bumped after every change so all screens reload their data. */
class Refresher { var tick by mutableIntStateOf(0); fun bump() { tick++ } }
val LocalRefresh = staticCompositionLocalOf { Refresher() }

@Composable
fun <T> load(vararg keys: Any?, block: suspend Repo.() -> T): T? {
    val repo = LocalRepo.current; val tick = LocalRefresh.current.tick
    var v by remember { mutableStateOf<T?>(null) }
    LaunchedEffect(tick, *keys) { v = try { repo.block() } catch (_: Exception) { v } }
    return v
}

fun Map<String, Any?>.d(k: String): Double = (this[k] as? Number)?.toDouble() ?: 0.0
fun Map<String, Any?>.s(k: String): String = this[k]?.toString() ?: ""
fun Map<String, Any?>.l(k: String): Long = (this[k] as? Number)?.toLong() ?: 0L
@Suppress("UNCHECKED_CAST") fun Map<String, Any?>.list(k: String) = (this[k] as? List<Map<String, Any?>>) ?: emptyList()

private val inr = NumberFormat.getNumberInstance(Locale("en", "IN")).apply { maximumFractionDigits = 2 }
fun rupees(x: Double) = (if (x < 0) "-₹" else "₹") + inr.format(kotlin.math.abs(x))
fun rupees0(x: Double) = (if (x < 0) "-₹" else "₹") + NumberFormat.getIntegerInstance(Locale("en", "IN")).format(kotlin.math.abs(Math.round(x)))

// ---------------- type ----------------
object T {
    val display = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp, lineHeight = 46.sp)
    val headline = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 38.sp, letterSpacing = (-0.3).sp)
    val title = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold)
    val body = TextStyle(fontSize = 16.sp, lineHeight = 22.sp)
    val label = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
    val small = TextStyle(fontSize = 13.sp)
    val amount = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
}

// ---------------- feel: haptics + press scale ----------------
@Composable
fun Modifier.tap(enabled: Boolean = true, haptic: Int = HapticFeedbackConstants.CONTEXT_CLICK, onClick: () -> Unit): Modifier {
    val view = LocalView.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "press")
    return this.scale(s).clickable(interactionSource = src, indication = null, enabled = enabled) {
        view.performHapticFeedback(haptic); onClick()
    }
}

@Composable fun haptic(): (Int) -> Unit { val v = LocalView.current; return { v.performHapticFeedback(it) } }

// ---------------- building blocks ----------------
@Composable
fun Card(modifier: Modifier = Modifier, border: Color? = null, onClick: (() -> Unit)? = null, pad: Dp = 20.dp, content: @Composable ColumnScope.() -> Unit) {
    var m = modifier.clip(RoundedCornerShape(28.dp))
    if (onClick != null) m = Modifier.tap(onClick = onClick).then(m)
    Column(m.background(P.card).then(if (border != null) Modifier.border(1.dp, border, RoundedCornerShape(28.dp)) else Modifier).padding(pad), content = content)
}

@Composable
fun GoldButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, icon: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(20.dp)).background(if (enabled) GoldGradient else Brush.linearGradient(listOf(P.cardHi, P.cardHi)))
        .tap(enabled, HapticFeedbackConstants.CONFIRM, onClick), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { icon(); Spacer(Modifier.width(10.dp)) }
        Text(text, style = T.label.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold), color = if (enabled) P.onGold else P.muted)
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, color: Color = P.text, onClick: () -> Unit) {
    Box(modifier.height(48.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, P.line, RoundedCornerShape(16.dp)).tap(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center) { Text(text, style = T.label, color = color) }
}

@Composable
fun Pill(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) P.gold else P.cardHi, tween(180), label = "pill")
    Box(modifier.clip(RoundedCornerShape(50)).background(bg).tap(haptic = HapticFeedbackConstants.CLOCK_TICK, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center) { Text(text, style = T.label, color = if (selected) P.onGold else P.text) }
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(P.card).border(1.dp, P.line, RoundedCornerShape(26.dp))) {
        options.forEachIndexed { i, o ->
            val bg by animateColorAsState(if (i == selected) P.cardHi else Color.Transparent, tween(200), label = "seg")
            Box(Modifier.weight(1f).fillMaxHeight().background(bg).tap(haptic = HapticFeedbackConstants.CLOCK_TICK) { onSelect(i) }, contentAlignment = Alignment.Center) {
                Text(o, style = T.label.copy(fontSize = 16.sp, fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Medium), color = if (i == selected) P.text else P.muted)
            }
        }
    }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, color: Color = P.gold, height: Dp = 12.dp) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), spring(stiffness = Spring.StiffnessLow), label = "bar")
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50)).background(P.cardHi)) {
        Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(50)).background(color))
    }
}

@Composable
fun ToolTile(label: String, modifier: Modifier = Modifier, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(24.dp)).background(P.card).border(1.dp, P.line, RoundedCornerShape(24.dp)).tap(onClick = onClick)
        .padding(vertical = 22.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        icon(); Spacer(Modifier.height(12.dp))
        Text(label, style = T.label.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), color = P.text, textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
fun EmptyState(title: String, body: String, icon: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        icon(); Spacer(Modifier.height(18.dp))
        Text(title, style = T.title, color = P.text, textAlign = TextAlign.Center); Spacer(Modifier.height(8.dp))
        Text(body, style = T.body, color = P.muted, textAlign = TextAlign.Center)
    }
}

@Composable fun SectionTitle(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = T.title.copy(fontSize = 24.sp, fontWeight = FontWeight.ExtraBold), color = P.text, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) Text(action, style = T.label, color = P.muted, modifier = Modifier.tap(onClick = onAction).padding(6.dp))
    }
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, number: Boolean = false, big: Boolean = false,
          autoFocus: Boolean = false, onDone: (() -> Unit)? = null) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    if (autoFocus) LaunchedEffect(Unit) { kotlinx.coroutines.delay(250); try { focus.requestFocus(); keyboard?.show() } catch (_: Exception) {} }
    OutlinedTextField(value, onChange, modifier.fillMaxWidth().focusRequester(focus), placeholder = { Text(placeholder, color = P.muted, style = if (big) T.amount else T.body) },
        textStyle = (if (big) T.amount else T.body).copy(color = P.text), singleLine = !big || true, shape = RoundedCornerShape(18.dp),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = if (number) androidx.compose.ui.text.input.KeyboardType.Decimal else androidx.compose.ui.text.input.KeyboardType.Text,
            imeAction = if (onDone != null) androidx.compose.ui.text.input.ImeAction.Done else androidx.compose.ui.text.input.ImeAction.Default),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone?.invoke() }),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = P.gold, unfocusedBorderColor = P.line, cursorColor = P.gold,
            focusedContainerColor = P.cardHi, unfocusedContainerColor = P.cardHi))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = P.card, shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 8.dp).size(40.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(P.line)) }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp).navigationBarsPadding(), content = content)
    }
}

// ---------------- icons drawn by hand (no emoji, no big icon packs) ----------------
@Composable
fun GlyphIcon(kind: String, tint: Color = P.gold, size: Dp = 30.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val st = Stroke(width = w * 0.085f, cap = StrokeCap.Round)
        when (kind) {
            "bars" -> listOf(0.12f to 0.45f, 0.42f to 0.15f, 0.72f to 0.6f).forEach { (x, top) ->
                drawRoundRect(tint, Offset(w * x, h * top), Size(w * 0.18f, h * (0.9f - top)), CornerRadius(w * 0.03f)) }
            "receipt" -> { drawRoundRect(tint, Offset(w * 0.2f, h * 0.1f), Size(w * 0.6f, h * 0.8f), CornerRadius(w * 0.08f), style = st)
                for (i in 0..2) drawLine(tint, Offset(w * 0.33f, h * (0.32f + i * 0.17f)), Offset(w * 0.67f, h * (0.32f + i * 0.17f)), st.width, StrokeCap.Round) }
            "people" -> { drawCircle(tint, w * 0.14f, Offset(w * 0.38f, h * 0.32f), style = st); drawCircle(tint, w * 0.11f, Offset(w * 0.7f, h * 0.36f), style = st)
                drawArc(tint, 180f, 180f, false, Offset(w * 0.12f, h * 0.58f), Size(w * 0.52f, h * 0.5f), style = st)
                drawArc(tint, 200f, 140f, false, Offset(w * 0.55f, h * 0.62f), Size(w * 0.36f, h * 0.4f), style = st) }
            "home" -> { val p = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.15f, h * 0.48f); lineTo(w * 0.5f, h * 0.15f); lineTo(w * 0.85f, h * 0.48f)
                    moveTo(w * 0.24f, h * 0.4f); lineTo(w * 0.24f, h * 0.85f); lineTo(w * 0.76f, h * 0.85f); lineTo(w * 0.76f, h * 0.4f) }
                drawPath(p, tint, style = st) }
            "chat" -> { drawRoundRect(tint, Offset(w * 0.12f, h * 0.15f), Size(w * 0.76f, h * 0.55f), CornerRadius(w * 0.1f))
                val p = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.25f, h * 0.68f); lineTo(w * 0.25f, h * 0.88f); lineTo(w * 0.45f, h * 0.68f); close() }; drawPath(p, tint) }
            "sparkle" -> { fun star(cx: Float, cy: Float, r: Float) { val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(cx, cy - r); quadraticTo(cx, cy, cx + r, cy); quadraticTo(cx, cy, cx, cy + r); quadraticTo(cx, cy, cx - r, cy); quadraticTo(cx, cy, cx, cy - r) }; drawPath(p, tint) }
                star(w * 0.4f, h * 0.55f, w * 0.3f); star(w * 0.76f, h * 0.24f, w * 0.14f); star(w * 0.78f, h * 0.8f, w * 0.1f) }
            "repeat" -> { drawArc(tint, 200f, 250f, false, Offset(w * 0.16f, h * 0.16f), Size(w * 0.68f, h * 0.68f), style = st)
                val p = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.7f, h * 0.08f); lineTo(w * 0.86f, h * 0.22f); lineTo(w * 0.66f, h * 0.3f); close() }; drawPath(p, tint) }
            "envelope" -> { drawRoundRect(tint, Offset(w * 0.1f, h * 0.25f), Size(w * 0.8f, h * 0.55f), CornerRadius(w * 0.08f), style = st)
                val p = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.12f, h * 0.3f); lineTo(w * 0.5f, h * 0.56f); lineTo(w * 0.88f, h * 0.3f) }; drawPath(p, tint, style = st) }
            "flame" -> { val p = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.5f, h * 0.08f); cubicTo(w * 0.9f, h * 0.4f, w * 0.88f, h * 0.92f, w * 0.5f, h * 0.92f)
                    cubicTo(w * 0.12f, h * 0.92f, w * 0.1f, h * 0.55f, w * 0.34f, h * 0.38f); cubicTo(w * 0.36f, h * 0.55f, w * 0.46f, h * 0.6f, w * 0.5f, h * 0.55f); cubicTo(w * 0.42f, h * 0.35f, w * 0.48f, h * 0.2f, w * 0.5f, h * 0.08f); close() }
                drawPath(p, tint) }
            "list" -> for (i in 0..2) { drawCircle(tint, w * 0.05f, Offset(w * 0.2f, h * (0.28f + i * 0.22f))); drawLine(tint, Offset(w * 0.34f, h * (0.28f + i * 0.22f)), Offset(w * 0.84f, h * (0.28f + i * 0.22f)), st.width, StrokeCap.Round) }
            "inbox" -> { drawRoundRect(tint, Offset(w * 0.14f, h * 0.18f), Size(w * 0.72f, h * 0.64f), CornerRadius(w * 0.08f), style = st)
                val p = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.14f, h * 0.56f); lineTo(w * 0.36f, h * 0.56f); quadraticTo(w * 0.5f, h * 0.72f, w * 0.64f, h * 0.56f); lineTo(w * 0.86f, h * 0.56f) }; drawPath(p, tint, style = st) }
            "mic" -> { drawRoundRect(tint, Offset(w * 0.36f, h * 0.1f), Size(w * 0.28f, h * 0.48f), CornerRadius(w * 0.14f))
                drawArc(tint, 0f, 180f, false, Offset(w * 0.24f, h * 0.3f), Size(w * 0.52f, h * 0.42f), style = st); drawLine(tint, Offset(w * 0.5f, h * 0.72f), Offset(w * 0.5f, h * 0.88f), st.width, StrokeCap.Round) }
            "photo" -> { drawRoundRect(tint, Offset(w * 0.12f, h * 0.2f), Size(w * 0.76f, h * 0.6f), CornerRadius(w * 0.08f), style = st); drawCircle(tint, w * 0.07f, Offset(w * 0.34f, h * 0.4f))
                val p = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.18f, h * 0.74f); lineTo(w * 0.45f, h * 0.5f); lineTo(w * 0.6f, h * 0.62f); lineTo(w * 0.7f, h * 0.54f); lineTo(w * 0.84f, h * 0.7f) }; drawPath(p, tint, style = st) }
            "bell" -> { val p = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.22f, h * 0.7f); lineTo(w * 0.78f, h * 0.7f); lineTo(w * 0.7f, h * 0.58f); lineTo(w * 0.7f, h * 0.42f)
                    cubicTo(w * 0.7f, h * 0.2f, w * 0.3f, h * 0.2f, w * 0.3f, h * 0.42f); lineTo(w * 0.3f, h * 0.58f); close() }; drawPath(p, tint, style = st)
                drawLine(tint, Offset(w * 0.44f, h * 0.82f), Offset(w * 0.56f, h * 0.82f), st.width, StrokeCap.Round) }
            "plus" -> { drawLine(tint, Offset(w * 0.5f, h * 0.18f), Offset(w * 0.5f, h * 0.82f), w * 0.1f, StrokeCap.Round); drawLine(tint, Offset(w * 0.18f, h * 0.5f), Offset(w * 0.82f, h * 0.5f), w * 0.1f, StrokeCap.Round) }
            "search" -> { drawCircle(tint, w * 0.24f, Offset(w * 0.44f, h * 0.44f), style = st); drawLine(tint, Offset(w * 0.62f, h * 0.62f), Offset(w * 0.84f, h * 0.84f), st.width, StrokeCap.Round) }
            "filter" -> for (i in 0..2) drawLine(tint, Offset(w * (0.18f + i * 0.12f), h * (0.3f + i * 0.2f)), Offset(w * (0.82f - i * 0.12f), h * (0.3f + i * 0.2f)), st.width, StrokeCap.Round)
            "check" -> { drawCircle(tint, w * 0.38f, Offset(w * 0.5f, h * 0.5f), style = st)
                val p = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.34f, h * 0.5f); lineTo(w * 0.46f, h * 0.62f); lineTo(w * 0.68f, h * 0.38f) }; drawPath(p, tint, style = st) }
            "back" -> { val p = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.62f, h * 0.2f); lineTo(w * 0.32f, h * 0.5f); lineTo(w * 0.62f, h * 0.8f) }; drawPath(p, tint, style = st) }
            "chevron" -> { val p = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.4f, h * 0.24f); lineTo(w * 0.66f, h * 0.5f); lineTo(w * 0.4f, h * 0.76f) }; drawPath(p, tint, style = st) }
            "sliders" -> for (i in 0..2) { val y = h * (0.28f + i * 0.22f); drawLine(tint, Offset(w * 0.16f, y), Offset(w * 0.84f, y), st.width * 0.8f, StrokeCap.Round)
                drawCircle(tint, w * 0.07f, Offset(w * (0.3f + i * 0.2f), y)) }
            else -> drawCircle(tint, w * 0.3f, Offset(w / 2, h / 2), style = st)
        }
    }
}

@Composable
fun IconCircle(kind: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.size(52.dp).clip(CircleShape).background(P.card).tap(onClick = onClick), contentAlignment = Alignment.Center) { GlyphIcon(kind, P.text, 24.dp) }
}
