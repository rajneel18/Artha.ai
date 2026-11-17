package ai.artha.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.Executors

/** Home-screen widgets: the 1x1 "left today" ring and the quick-add bar. */
object Widgets {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var queued = false

    private val OLIVE = Color.parseColor("#A4B865"); private val RED = Color.parseColor("#E87060")
    private val TEXT = Color.parseColor("#F1F4EC"); private val MUTED = Color.parseColor("#9CA392"); private val TRACK = Color.parseColor("#30362A")

    /** Refreshes every widget soon (several changes in a row cause one refresh). */
    fun update(c: Context) {
        val app = c.applicationContext
        synchronized(this) { if (queued) return; queued = true }
        main.postDelayed({ synchronized(this) { queued = false }; io.execute { try { refresh(app) } catch (_: Throwable) {} } }, 400)
    }

    private fun refresh(c: Context) {
        val m = AppWidgetManager.getInstance(c)
        val ring = m.getAppWidgetIds(ComponentName(c, RingWidget::class.java))
        if (ring.isNotEmpty()) {
            val d = Artha.of(c).api.engine.daily(java.time.LocalDate.now(), span = 1)
            val allowance = (d["allowance"] as Number).toDouble(); val spent = (d["spent"] as Number).toDouble()
            val v = RemoteViews(c.packageName, R.layout.widget_ring)
            v.setImageViewBitmap(R.id.ring, ringBitmap(allowance, spent))
            v.setContentDescription(R.id.ring, if (spent <= allowance) "${rupees(allowance - spent)} left today" else "${rupees(spent - allowance)} over today")
            v.setOnClickPendingIntent(R.id.root, quickAdd(c, voice = false))
            m.updateAppWidget(ring, v)
        }
        val bars = m.getAppWidgetIds(ComponentName(c, QuickAddWidget::class.java))
        if (bars.isNotEmpty()) {
            val v = RemoteViews(c.packageName, R.layout.widget_quick)
            v.setOnClickPendingIntent(R.id.root, quickAdd(c, voice = false))
            v.setOnClickPendingIntent(R.id.mic, quickAdd(c, voice = true))
            m.updateAppWidget(bars, v)
        }
    }

    fun quickAdd(c: Context, voice: Boolean): PendingIntent = PendingIntent.getActivity(c, if (voice) 2 else 1,
        Intent(c, QuickAddActivity::class.java).putExtra("voice", voice).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun rupees(x: Double) = "₹" + NumberFormat.getIntegerInstance(Locale("en", "IN")).format(Math.round(x))

    /** The ring: what's left of today's allowance (olive), red once you're over. */
    private fun ringBitmap(allowance: Double, spent: Double): Bitmap {
        val size = 320; val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888); val cv = Canvas(b)
        val over = spent > allowance
        val left = allowance - spent
        val stroke = 26f; val r = RectF(stroke, stroke, size - stroke, size - stroke)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND }
        p.color = TRACK; cv.drawArc(r, 0f, 360f, false, p)
        val frac = if (over) 1f else if (allowance > 0) (left / allowance).toFloat().coerceIn(0f, 1f) else 0f
        if (frac > 0f) { p.color = if (over) RED else OLIVE; cv.drawArc(r, -90f, 360f * frac, false, p) }
        val amount = rupees(kotlin.math.abs(left))
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); color = if (over) RED else TEXT }
        t.textSize = 80f; while (t.measureText(amount) > size - 2 * stroke - 40 && t.textSize > 30) t.textSize -= 4
        cv.drawText(amount, size / 2f, size / 2f + t.textSize * 0.3f, t)
        val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 36f; color = MUTED }
        cv.drawText(if (over) "over" else "left", size / 2f, size / 2f + t.textSize * 0.3f + 48f, s)
        return b
    }
}

class RingWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = Widgets.update(c)
}

class QuickAddWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = Widgets.update(c)
}
