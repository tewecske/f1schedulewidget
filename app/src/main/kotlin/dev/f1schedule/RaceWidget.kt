package dev.f1schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
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
        state(ctx).edit().apply { ids.forEach { remove("off_$it"); remove("track_$it"); remove("bg_$it") } }.apply()
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

        private const val RED = 0xFFFF2800.toInt()
        const val DEFAULT_BG = 0xEB141414.toInt()

        // Text sizes (dp) at scale 1; must match the layout.
        private const val TITLE = 16f
        private const val SUB = 12f
        private const val ROW = 13f
        private const val FOOT = 11f
        // Must match dimens.xml.
        private const val ARROW_W = 26f
        private const val PAD_V = 6f
        private const val HEADER_GAP = 3f
        private const val FOOTER_GAP = 4f
        private const val ROW_GAP = 1f
        private const val COL_GAP = 12f
        private const val SIZE_FUDGE = 0.85f
        private const val MAX_ROWS = 5 // sessions in a weekend
        private const val MIN_SCALE = 0.6f
        private const val MAX_SCALE = 2f

        private val ROWS = intArrayOf(R.id.row0, R.id.row1, R.id.row2, R.id.row3, R.id.row4, R.id.row5)
        private val NAMES = intArrayOf(R.id.name0, R.id.name1, R.id.name2, R.id.name3, R.id.name4, R.id.name5)
        private val TIMES = intArrayOf(R.id.time0, R.id.time1, R.id.time2, R.id.time3, R.id.time4, R.id.time5)

        private fun state(ctx: Context) = ctx.getSharedPreferences("widgets", Context.MODE_PRIVATE)

        /** Background colour (ARGB) of a widget, or the default for all widgets when [id] is invalid. */
        fun background(ctx: Context, id: Int): Int {
            val default = state(ctx).getInt("bg_default", DEFAULT_BG)
            return if (id == AppWidgetManager.INVALID_APPWIDGET_ID) default else state(ctx).getInt("bg_$id", default)
        }

        /** Sets one widget's background, or the default for all widgets (dropping their own) when [id] is invalid. */
        fun setBackground(ctx: Context, id: Int, argb: Int) {
            val s = state(ctx)
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) return s.edit().putInt("bg_$id", argb).apply()
            s.edit().apply {
                s.all.keys.filter { it.startsWith("bg_") }.forEach(::remove)
                putInt("bg_default", argb)
            }.apply()
        }

        /** Text colours: primary, secondary, finished. */
        class Palette(val text: Int, val sub: Int, val done: Int)

        /** Dark text on light, mostly opaque backgrounds; light text otherwise. */
        fun palette(argb: Int): Palette {
            val lum = (0.2126 * Color.red(argb) + 0.7152 * Color.green(argb) + 0.0722 * Color.blue(argb)) / 255
            return if (lum > 0.55 && Color.alpha(argb) >= 0x80) Palette(0xFF141414.toInt(), 0xFF5C5C5C.toInt(), 0xFFADADAD.toInt())
            else Palette(0xFFFFFFFF.toInt(), 0xFF9A9A9A.toInt(), 0xFF555555.toInt())
        }

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
            val bg = background(ctx, id)
            val p = palette(bg)
            v.setInt(R.id.bg, "setColorFilter", bg or 0xFF000000.toInt())
            v.setInt(R.id.bg, "setImageAlpha", Color.alpha(bg))
            v.setTextColor(R.id.title, p.text)
            listOf(R.id.circuit, R.id.place, R.id.mode).forEach { v.setTextColor(it, p.sub) }

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
            val samples = (1..7).map { ZonedDateTime.of(2001, 1, it, 22, 59, 0, 0, ZoneId.systemDefault()).format(timeFmt) }
            val fit = fit(mgr, id, samples)
            // Without the footer there's no toggle, so show local time.
            val track = fit.footer && state.getBoolean("track_$id", false)
            val zone = if (track) race.trackZone else ZoneId.systemDefault()

            v.setTextViewText(R.id.title, race.name)
            v.setOnClickPendingIntent(R.id.prev, broadcast(ctx, ACTION_PREV, id))
            v.setOnClickPendingIntent(R.id.next, broadcast(ctx, ACTION_NEXT, id))
            v.setInt(R.id.prev_icon, "setColorFilter", if (index > 0) p.text else p.done)
            v.setInt(R.id.next_icon, "setColorFilter", if (index < races.lastIndex) p.text else p.done)

            val dayFmt = DateTimeFormatter.ofPattern("d MMM", locale)
            val first = race.sessions.first().start.atZone(zone)
            val last = race.sessions.last().start.atZone(zone)
            val dates = if (first.month == last.month) "${first.dayOfMonth}–${last.format(dayFmt)}"
            else "${first.format(dayFmt)} – ${last.format(dayFmt)}"
            v.setTextViewText(R.id.circuit, race.circuit)
            v.setTextViewText(R.id.place, "${race.city}, ${race.country}  ·  $dates")
            v.setTextViewText(R.id.mode, if (track) "⇄ Track time" else "⇄ Local time")
            v.setTextViewText(R.id.round, "Round ${race.round} / ${races.last().round}")
            v.setTextColor(R.id.round, if (index == upcoming) p.sub else RED)
            v.setOnClickPendingIntent(R.id.mode, broadcast(ctx, ACTION_MODE, id))

            // Finished race: top 3 of qualifying (left) and race (right), once published.
            val podium = if (race.end <= now) Schedule.podium(ctx, race).also { if (it == null) missing += race } else null
            val rows = if (podium != null) {
                fun label(d: Driver) = if (fit.short) d.code else d.name
                listOf(Row(if (fit.short) "Quali" else "Qualifying", "Race", p.sub, p.sub)) + (0 until 3).map { i ->
                    Row(
                        podium.quali.getOrNull(i)?.let { "${i + 1}  ${label(it)}" } ?: "",
                        podium.race.getOrNull(i)?.let { "${label(it)}  ${i + 1}" } ?: "",
                        p.text, p.text,
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
                        if (fit.short) s.short else s.name, time,
                        if (i == current) RED else if (done) p.done else p.text,
                        if (i == current) RED else if (done) p.done else p.sub,
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
            val widest = rows.maxOf { Measure.row(it.left, it.right) }
            apply(v, fit, minOf(fit.scale, fit.rowWidth / widest).coerceAtLeast(MIN_SCALE))
            return v
        }

        /** What a widget size shows: short names?, optional lines, and the text scale. */
        private class Fit(
            val short: Boolean, val footer: Boolean, val place: Boolean, val circuit: Boolean,
            val scale: Float, val rowWidth: Float, // dp available for a row's text
        )

        /**
         * Measures the content at scale 1 and picks the largest text that fits. Sessions come first;
         * then the footer, location and circuit lines are added in that order while they don't
         * shrink the text (below normal size on big widgets).
         */
        private fun fit(mgr: AppWidgetManager, id: Int, timeSamples: List<String>): Fit {
            val o = mgr.getAppWidgetOptions(id)
            val landscape = Resources.getSystem().configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            // Launchers report the cell area; the widget itself gets less (Pixel: 360x358 reported, 316x300 real).
            val w = SIZE_FUDGE * o.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val h = SIZE_FUDGE * o.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            val rowWidth = w - 2 * ARROW_W - COL_GAP
            if (w <= 0 || h <= 0) return Fit(false, true, true, true, 1f, Float.MAX_VALUE)

            val time = timeSamples.maxOf { Measure.width(it, ROW, medium = true) }
            fun byWidth(short: Boolean) = rowWidth / (Schedule.SESSION_NAMES.maxOf { Measure.width(if (short) it.second else it.first, ROW) } + time)
            fun byHeight(footer: Boolean, place: Boolean, circuit: Boolean): Float {
                var fixed = 2 * PAD_V + HEADER_GAP + MAX_ROWS * ROW_GAP
                var text = Measure.line(TITLE, medium = true) + MAX_ROWS * Measure.line(ROW)
                if (footer) { fixed += FOOTER_GAP; text += Measure.line(FOOT) }
                if (place) text += Measure.line(SUB)
                if (circuit) text += Measure.line(SUB)
                return (h - fixed) / text
            }

            val base = byHeight(false, false, false)
            val full = byWidth(false)
            val shortW = byWidth(true)
            // Full names unless they'd make the text noticeably smaller than normal (or than height allows).
            val short = full < 0.85f * minOf(shortW, base, 1f)
            val width = if (short) shortW else full
            val goal = minOf(width, base, MAX_SCALE, 1f) * 0.98f
            fun scale(f: Boolean, p: Boolean, c: Boolean) = minOf(width, byHeight(f, p, c), MAX_SCALE)
            val footer = scale(true, false, false) >= goal
            val place = footer && scale(true, true, false) >= goal
            val circuit = place && scale(true, true, true) >= goal
            return Fit(short, footer, place, circuit, scale(footer, place, circuit).coerceAtLeast(MIN_SCALE), rowWidth)
        }

        private fun apply(v: RemoteViews, fit: Fit, s: Float) {
            fun show(view: Int, on: Boolean) = v.setViewVisibility(view, if (on) View.VISIBLE else View.GONE)
            show(R.id.footer, fit.footer)
            show(R.id.place, fit.place)
            show(R.id.circuit, fit.circuit)

            fun size(view: Int, base: Float) = v.setTextViewTextSize(view, TypedValue.COMPLEX_UNIT_DIP, base * s)
            size(R.id.title, TITLE)
            size(R.id.circuit, SUB)
            size(R.id.place, SUB)
            size(R.id.mode, FOOT)
            size(R.id.round, FOOT)
            NAMES.forEach { size(it, ROW) }
            TIMES.forEach { size(it, ROW) }
        }

        /** Text metrics in dp, matching the layout's fonts (TextViews have no font padding). */
        private object Measure {
            private val regular = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
            private val medium = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }

            private fun paint(size: Float, medium: Boolean) = (if (medium) this.medium else regular).apply { textSize = size }
            fun width(text: String, size: Float, medium: Boolean = false) = paint(size, medium).measureText(text)
            fun line(size: Float, medium: Boolean = false) = paint(size, medium).fontMetrics.let { it.descent - it.ascent }
            fun row(left: String, right: String) = width(left, ROW) + width(right, ROW, medium = true)
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
