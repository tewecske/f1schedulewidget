package dev.f1schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.concurrent.thread

class RaceWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = refresh(ctx, force = false)

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) {
        render(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val state = state(ctx)
        when (intent.action) {
            ACTION_PREV, ACTION_NEXT -> {
                val step = if (intent.action == ACTION_NEXT) 1 else -1
                state.edit().putInt("off_$id", state.getInt("off_$id", 0) + step).apply()
                // Snap back to the upcoming race after a while, so the widget doesn't stay on an old weekend.
                ctx.getSystemService(AlarmManager::class.java)
                    .set(AlarmManager.RTC, System.currentTimeMillis() + RESET_AFTER_MS, broadcast(ctx, ACTION_RESET))
                refresh(ctx, force = false)
            }
            ACTION_MODE -> {
                state.edit().putBoolean("track_$id", !state.getBoolean("track_$id", false)).apply()
                render(ctx)
            }
            ACTION_HOME -> {
                state.edit().remove("off_$id").apply()
                refresh(ctx, force = true)
            }
            ACTION_RESET -> {
                state.edit().apply { state.all.keys.filter { it.startsWith("off_") }.forEach(::remove) }.apply()
                refresh(ctx, force = false)
            }
            ACTION_TICK -> refresh(ctx, force = false)
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_LOCALE_CHANGED -> render(ctx)
            else -> super.onReceive(ctx, intent)
        }
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        state(ctx).edit().apply { ids.forEach { remove("off_$it"); remove("track_$it") } }.apply()
    }

    override fun onDisabled(ctx: Context) {
        val alarms = ctx.getSystemService(AlarmManager::class.java)
        alarms.cancel(broadcast(ctx, ACTION_TICK))
        alarms.cancel(broadcast(ctx, ACTION_RESET))
    }

    /** Renders from cache, then fetches the schedule if stale and results of shown past races if missing. */
    private fun refresh(ctx: Context, force: Boolean) {
        val shown = render(ctx).filter { force || Schedule.shouldFetchPodium(ctx, it) }
        val stale = force || Schedule.isStale(ctx)
        if (!stale && shown.isEmpty()) return
        val pending = goAsync()
        thread {
            try {
                var changed = stale && Schedule.fetch(ctx)
                shown.forEach { if (Schedule.fetchPodium(ctx, it)) changed = true }
                if (changed) render(ctx)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_PREV = "dev.f1schedule.PREV"
        private const val ACTION_NEXT = "dev.f1schedule.NEXT"
        private const val ACTION_MODE = "dev.f1schedule.MODE"
        private const val ACTION_HOME = "dev.f1schedule.HOME"
        private const val ACTION_RESET = "dev.f1schedule.RESET"
        private const val ACTION_TICK = "dev.f1schedule.TICK"
        private const val RESET_AFTER_MS = 3 * 60_000L

        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val GREY = 0xFF9A9A9A.toInt()
        private const val DONE = 0xFF555555.toInt()
        private const val RED = 0xFFFF2800.toInt()

        // Text sizes (dp) at scale 1; must match the layout.
        private const val TITLE = 16f
        private const val SUB = 12f
        private const val ROW = 13f
        private const val FOOT = 11f
        private const val ARROW = 22f
        private const val LINE = 1.3f // line height / text size, measured on Roboto
        private const val SIZE_FUDGE = 0.85f
        private const val MAX_ROWS = 5 // sessions in a weekend
        private const val MIN_SCALE = 0.6f
        private const val MAX_SCALE = 2f

        private val ROWS = intArrayOf(R.id.row0, R.id.row1, R.id.row2, R.id.row3, R.id.row4, R.id.row5)
        private val NAMES = intArrayOf(R.id.name0, R.id.name1, R.id.name2, R.id.name3, R.id.name4, R.id.name5)
        private val TIMES = intArrayOf(R.id.time0, R.id.time1, R.id.time2, R.id.time3, R.id.time4, R.id.time5)

        private fun state(ctx: Context) = ctx.getSharedPreferences("widgets", Context.MODE_PRIVATE)

        /** Updates all widgets; returns shown finished races whose results aren't cached yet. */
        fun render(ctx: Context): List<Race> {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, RaceWidget::class.java))
            if (ids.isEmpty()) return emptyList()
            val now = Instant.now()
            val races = Schedule.races(ctx)
            val upcoming = races.indexOfFirst { it.end > now }.let { if (it < 0) races.lastIndex else it }
            val missing = mutableSetOf<Race>()
            for (id in ids) mgr.updateAppWidget(id, views(ctx, mgr, id, races, upcoming, now, missing))
            races.getOrNull(upcoming)?.let { scheduleTick(ctx, it, now) }
            return missing.toList()
        }

        private fun views(
            ctx: Context, mgr: AppWidgetManager, id: Int, races: List<Race>, upcoming: Int, now: Instant,
            missing: MutableSet<Race>,
        ): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            v.setOnClickPendingIntent(R.id.header, broadcast(ctx, ACTION_HOME, id))

            if (races.isEmpty()) {
                v.setTextViewText(R.id.title, "No schedule yet")
                v.setTextViewText(R.id.circuit, "Tap to refresh")
                v.setViewVisibility(R.id.circuit, View.VISIBLE)
                listOf(R.id.place, R.id.prev, R.id.next, R.id.footer).forEach { v.setViewVisibility(it, View.INVISIBLE) }
                ROWS.forEach { v.setViewVisibility(it, View.GONE) }
                return v
            }

            // Hosts may replay these actions onto the previous views, so undo the empty state explicitly.
            listOf(R.id.prev, R.id.next).forEach { v.setViewVisibility(it, View.VISIBLE) }

            val state = state(ctx)
            val offset = state.getInt("off_$id", 0)
            val index = (upcoming + offset).coerceIn(0, races.lastIndex)
            if (index - upcoming != offset) state.edit().putInt("off_$id", index - upcoming).apply()
            val race = races[index]
            val locale = Locale.getDefault()
            val timeFmt = DateTimeFormatter.ofPattern(
                if (DateFormat.is24HourFormat(ctx)) "EEE  HH:mm" else "EEE  h:mm a", locale,
            )
            // Size for the largest weekend rather than what's shown, so text doesn't jump between races.
            val sample = ZonedDateTime.of(2000, 1, 1, 23, 0, 0, 0, ZoneId.systemDefault()).format(timeFmt).length
            val fit = fit(ctx, mgr, id, sample)
            // Compact has no toggle, so it always shows local time.
            val track = !fit.compact && state.getBoolean("track_$id", false)
            val zone = if (track) race.trackZone else ZoneId.systemDefault()

            v.setTextViewText(R.id.title, race.name)
            v.setOnClickPendingIntent(R.id.prev, broadcast(ctx, ACTION_PREV, id))
            v.setOnClickPendingIntent(R.id.next, broadcast(ctx, ACTION_NEXT, id))
            v.setTextColor(R.id.prev, if (index > 0) WHITE else DONE)
            v.setTextColor(R.id.next, if (index < races.lastIndex) WHITE else DONE)

            val dayFmt = DateTimeFormatter.ofPattern("d MMM", locale)
            val first = race.sessions.first().start.atZone(zone)
            val last = race.sessions.last().start.atZone(zone)
            val dates = if (first.month == last.month) "${first.dayOfMonth}–${last.format(dayFmt)}"
            else "${first.format(dayFmt)} – ${last.format(dayFmt)}"
            v.setTextViewText(R.id.circuit, race.circuit)
            v.setTextViewText(R.id.place, "${race.city}, ${race.country}  ·  $dates")
            v.setTextViewText(R.id.mode, if (track) "⇄ Track time" else "⇄ Local time")
            v.setTextViewText(R.id.round, "Round ${race.round} / ${races.last().round}")
            v.setTextColor(R.id.round, if (index == upcoming) GREY else RED)
            v.setOnClickPendingIntent(R.id.mode, broadcast(ctx, ACTION_MODE, id))

            // Finished race: top 3 of qualifying (left) and race (right), once published.
            val podium = if (race.end <= now) Schedule.podium(ctx, race).also { if (it == null) missing += race } else null
            val rows = if (podium != null) {
                fun label(d: Driver) = if (fit.compact) d.code else d.name
                listOf(Row(if (fit.compact) "Quali" else "Qualifying", "Race", GREY, GREY)) + (0 until 3).map { i ->
                    Row(
                        podium.quali.getOrNull(i)?.let { "${i + 1}  ${label(it)}" } ?: "",
                        podium.race.getOrNull(i)?.let { "${label(it)}  ${i + 1}" } ?: "",
                        WHITE, WHITE,
                    )
                }
            } else {
                // Only the upcoming weekend highlights its current session.
                val current = if (index == upcoming) race.sessions.indexOfFirst { it.end > now } else -1
                val dayOnly = DateTimeFormatter.ofPattern("EEE", locale)
                race.sessions.mapIndexed { i, s ->
                    val t = s.start.atZone(zone)
                    val time = if (s.hasTime) t.format(timeFmt) else "${t.format(dayOnly)}  TBC"
                    val done = s.end <= now
                    Row(
                        if (fit.compact) s.short else s.name, time,
                        if (i == current) RED else if (done) DONE else WHITE,
                        if (i == current) RED else if (done) DONE else GREY,
                    )
                }
            }
            ROWS.forEachIndexed { i, row ->
                val r = rows.getOrNull(i)
                if (r == null) {
                    v.setViewVisibility(row, View.GONE)
                    return@forEachIndexed
                }
                v.setViewVisibility(row, View.VISIBLE)
                v.setTextViewText(NAMES[i], r.left)
                v.setTextViewText(TIMES[i], r.right)
                v.setTextColor(NAMES[i], r.leftColor)
                v.setTextColor(TIMES[i], r.rightColor)
            }

            // Unusually long names may still need smaller text than the reference.
            val chars = rows.maxOf { it.left.length + it.right.length }
            val scale = if (chars > fit.chars) fit.scale * fit.chars / chars else fit.scale
            apply(ctx, v, fit, scale.coerceAtLeast(MIN_SCALE))
            return v
        }

        /** Layout choice for a widget size: full or compact, text scale, and whether the circuit line fits. */
        private class Fit(val compact: Boolean, val scale: Float, val showCircuit: Boolean, val chars: Int)

        private fun fit(ctx: Context, mgr: AppWidgetManager, id: Int, timeChars: Int): Fit {
            val o = mgr.getAppWidgetOptions(id)
            val landscape = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            // Launchers report the cell area; the widget itself gets less (Pixel: 360x358 reported, 316x300 real).
            val w = SIZE_FUDGE * o.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val h = SIZE_FUDGE * o.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            val fullChars = "Sprint Qualifying".length + timeChars
            if (w <= 0 || h <= 0) return Fit(false, 1f, true, fullChars)

            fun scale(compact: Boolean, subLines: Int, chars: Int): Float {
                // padding, header margin, row gaps, footer (compact: tighter padding, no gaps or footer)
                val fixedH = if (compact) 12f + 6f else 24f + 6f + 3f * MAX_ROWS + 6f
                val lines = TITLE + SUB * subLines + ROW * MAX_ROWS + if (compact) 0f else FOOT
                val byHeight = (h - fixedH) / (LINE * lines)
                val byWidth = (w - 40f) / (ROW * 0.55f * chars)
                return minOf(byHeight, byWidth)
            }

            val withCircuit = scale(false, 2, fullChars)
            val without = scale(false, 1, fullChars)
            return when {
                withCircuit >= 0.9f -> Fit(false, withCircuit.coerceAtMost(MAX_SCALE), true, fullChars)
                // Hiding the circuit line only helps when height is the limit.
                without >= 0.8f -> Fit(false, without.coerceAtMost(MAX_SCALE), withCircuit >= without, fullChars)
                else -> {
                    val chars = "Sprint".length + timeChars
                    Fit(true, scale(true, 0, chars).coerceIn(MIN_SCALE, MAX_SCALE), false, chars)
                }
            }
        }

        private fun apply(ctx: Context, v: RemoteViews, fit: Fit, s: Float) {
            val full = if (fit.compact) View.GONE else View.VISIBLE
            v.setViewVisibility(R.id.place, full)
            v.setViewVisibility(R.id.footer, full)
            v.setViewVisibility(R.id.circuit, if (fit.showCircuit) View.VISIBLE else View.GONE)

            val dp = ctx.resources.displayMetrics.density
            fun px(value: Int) = (value * dp).toInt()
            if (fit.compact) v.setViewPadding(R.id.root, px(4), px(6), px(4), px(6))
            else v.setViewPadding(R.id.root, px(6), px(12), px(6), px(12))
            ROWS.forEach { v.setViewPadding(it, 0, if (fit.compact) 0 else px(3), 0, 0) }

            fun size(view: Int, base: Float) = v.setTextViewTextSize(view, TypedValue.COMPLEX_UNIT_DIP, base * s)
            size(R.id.title, TITLE)
            size(R.id.circuit, SUB)
            size(R.id.place, SUB)
            size(R.id.mode, FOOT)
            size(R.id.round, FOOT)
            size(R.id.prev, ARROW)
            size(R.id.next, ARROW)
            NAMES.forEach { size(it, ROW) }
            TIMES.forEach { size(it, ROW) }
        }

        private class Row(val left: String, val right: String, val leftColor: Int, val rightColor: Int)

        /** Re-render when the highlighted session changes (session start/end, race over). */
        private fun scheduleTick(ctx: Context, race: Race, now: Instant) {
            val next = race.sessions.flatMap { listOf(it.start, it.end) }.filter { it > now }.minOrNull() ?: return
            ctx.getSystemService(AlarmManager::class.java)
                .set(AlarmManager.RTC, next.toEpochMilli() + 1000, broadcast(ctx, ACTION_TICK))
        }

        private fun broadcast(ctx: Context, action: String, id: Int = 0): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, id,
                Intent(ctx, RaceWidget::class.java)
                    .setAction(action)
                    .setData(Uri.parse("f1schedule://$id/$action")) // unique per widget + action
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
