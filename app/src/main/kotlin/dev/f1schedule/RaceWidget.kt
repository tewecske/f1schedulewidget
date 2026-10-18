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
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.util.SizeF
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
        state(ctx).edit().apply { ids.forEach { remove("off_$it"); remove("track_$it"); SETTING_KEYS.forEach { k -> remove("${k}_$it") } } }.apply()
    }

    override fun onDisabled(ctx: Context) {
        val alarms = ctx.getSystemService(AlarmManager::class.java)
        alarms.cancel(broadcast(ctx, ACTION_TICK))
        alarms.cancel(broadcast(ctx, ACTION_RESET))
    }

    /** Renders from cache, then fetches the schedule if stale and results of shown past races if missing. */
    private fun refresh(ctx: Context, force: Boolean) {
        val shown = render(ctx).filter { force || Schedule.shouldFetchResults(ctx, it) }
        val stale = force || Schedule.isStale(ctx)
        if (!stale && shown.isEmpty()) return
        val pending = goAsync()
        thread {
            try {
                var changed = stale && Schedule.fetch(ctx)
                shown.forEach { if (Schedule.fetchResults(ctx, it)) changed = true }
                if (changed) render(ctx)
            } finally {
                pending.finish()
            }
        }
    }

    /** Colour settings (ARGB). Text colours are null when automatic. */
    class Colors(val bg: Int, val main: Int?, val past: Int?, val next: Int?)

    /** Bottom row: shown even when it doesn't fit, and the date instead of the round on the right. */
    class Footer(val always: Boolean, val date: Boolean)

    /** Resolved colours: background, main text, secondary text, finished sessions, highlighted session. */
    class Palette(val bg: Int, val text: Int, val sub: Int, val done: Int, val next: Int)

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
        private const val EXACT_FUDGE = 0.97f
        private const val MAX_ROWS = 5 // sessions in a weekend
        private const val MIN_SCALE = 0.6f
        private const val MAX_SCALE = 2f

        // Header plus Schedule.RESULT_ROWS results.
        private val ROWS = intArrayOf(
            R.id.row0, R.id.row1, R.id.row2, R.id.row3, R.id.row4, R.id.row5,
            R.id.row6, R.id.row7, R.id.row8, R.id.row9, R.id.row10,
        )
        private val NAMES = intArrayOf(
            R.id.name0, R.id.name1, R.id.name2, R.id.name3, R.id.name4, R.id.name5,
            R.id.name6, R.id.name7, R.id.name8, R.id.name9, R.id.name10,
        )
        private val MIDS = intArrayOf(
            R.id.mid0, R.id.mid1, R.id.mid2, R.id.mid3, R.id.mid4, R.id.mid5,
            R.id.mid6, R.id.mid7, R.id.mid8, R.id.mid9, R.id.mid10,
        )
        private val TIMES = intArrayOf(
            R.id.time0, R.id.time1, R.id.time2, R.id.time3, R.id.time4, R.id.time5,
            R.id.time6, R.id.time7, R.id.time8, R.id.time9, R.id.time10,
        )

        private fun state(ctx: Context) = ctx.getSharedPreferences("widgets", Context.MODE_PRIVATE)

        private val SETTING_KEYS = listOf("bg", "main", "past", "next", "footer", "footdate")

        /** A widget's own setting, else the default for all widgets; or only the default when [id] is invalid. */
        private fun setting(ctx: Context, id: Int, key: String): Int? {
            val s = state(ctx)
            val own = "${key}_$id"
            val k = if (id != AppWidgetManager.INVALID_APPWIDGET_ID && s.contains(own)) own else "${key}_default"
            return if (s.contains(k)) s.getInt(k, 0) else null
        }

        /** Sets one widget's settings, or the defaults for all widgets (dropping their own) when [id] is invalid. */
        private fun putSettings(ctx: Context, id: Int, values: List<Pair<String, Int?>>) {
            val s = state(ctx)
            val all = id == AppWidgetManager.INVALID_APPWIDGET_ID
            s.edit().apply {
                values.forEach { (key, value) ->
                    if (all) s.all.keys.filter { it.startsWith("${key}_") }.forEach(::remove)
                    val k = if (all) "${key}_default" else "${key}_$id"
                    if (value == null) remove(k) else putInt(k, value)
                }
            }.apply()
        }

        fun colors(ctx: Context, id: Int) = Colors(
            setting(ctx, id, "bg") ?: DEFAULT_BG, setting(ctx, id, "main"), setting(ctx, id, "past"), setting(ctx, id, "next"),
        )

        fun setColors(ctx: Context, id: Int, c: Colors) =
            putSettings(ctx, id, listOf("bg" to c.bg, "main" to c.main, "past" to c.past, "next" to c.next))

        fun footer(ctx: Context, id: Int) = Footer(setting(ctx, id, "footer") == 1, setting(ctx, id, "footdate") == 1)

        fun setFooter(ctx: Context, id: Int, f: Footer) =
            putSettings(ctx, id, listOf("footer" to if (f.always) 1 else 0, "footdate" to if (f.date) 1 else 0))

        /** Fills automatic text colours: dark on light backgrounds, light otherwise. */
        fun palette(c: Colors): Palette {
            val lum = (0.2126 * Color.red(c.bg) + 0.7152 * Color.green(c.bg) + 0.0722 * Color.blue(c.bg)) / 255
            val light = lum > 0.55
            val text = c.main ?: if (light) 0xFF141414.toInt() else 0xFFFFFFFF.toInt()
            val sub = (text and 0xFFFFFF) or (Color.alpha(text) * 6 / 10 shl 24)
            val done = c.past ?: if (light) 0xFFADADAD.toInt() else 0xFF555555.toInt()
            return Palette(c.bg, text, sub, done, c.next ?: RED)
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
            val p = palette(colors(ctx, id))
            v.setInt(R.id.bg, "setColorFilter", p.bg or 0xFF000000.toInt())
            v.setInt(R.id.bg, "setImageAlpha", Color.alpha(p.bg))
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
            val foot = footer(ctx, id)
            val fit = fit(mgr, id, samples, foot.always)
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
            // The date moves to the bottom row when chosen there.
            val dateBelow = fit.footer && foot.date
            v.setTextViewText(R.id.place, "${race.city}, ${race.country}" + if (dateBelow) "" else "  ·  $dates")
            v.setTextViewText(R.id.mode, if (track) "⇄ Track time" else "⇄ Local time")
            v.setTextViewText(R.id.round, if (dateBelow) dates else "Round ${race.round} / ${races.last().round}")
            v.setTextColor(R.id.round, if (index == upcoming) p.sub else p.next)
            v.setOnClickPendingIntent(R.id.mode, broadcast(ctx, ACTION_MODE, id))

            // Finished race: qualifying (left, with lap times when they fit) and race (right), once published.
            val results = if (race.end <= now) Schedule.results(ctx, race).also { if (it == null) missing += race } else null
            val rows = if (results != null) {
                // As many results as fit below the header at the session text size, at least the podium.
                val n = minOf((fit.rowsAt(fit.scale) - 1).coerceAtLeast(3), maxOf(results.quali.size, results.race.size))
                fun rows(codes: Boolean, times: Boolean): List<Row> {
                    fun label(d: Driver) = if (codes) d.code else d.name
                    return listOf(Row(if (codes) "Quali" else "Qualifying", "Race", p.sub, p.sub)) + (0 until n).map { i ->
                        val q = results.quali.getOrNull(i)
                        // Figure space pads 1-9 to the width of 10 so names line up.
                        val pos = if (n >= 10 && i < 9) "\u2007${i + 1}" else "${i + 1}"
                        Row(
                            q?.let { "$pos  ${label(it)}" } ?: "",
                            results.race.getOrNull(i)?.let { "${label(it)}  $pos" } ?: "",
                            p.text, p.text,
                            mid = if (times) q?.time ?: "" else "",
                        )
                    }
                }
                // Names before lap times, lap times before codes; whatever fits without smaller text.
                val hasTimes = results.quali.any { it.time.isNotEmpty() }
                val options = listOfNotNull(
                    if (!fit.short && hasTimes) rows(codes = false, times = true) else null,
                    if (!fit.short) rows(codes = false, times = false) else null,
                    if (hasTimes) rows(codes = true, times = true) else null,
                    rows(codes = true, times = false),
                )
                options.firstOrNull { o -> o.maxOf { Measure.row(it) } * fit.scale <= fit.rowWidth } ?: options.last()
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
                        if (i == current) p.next else if (done) p.done else p.text,
                        if (i == current) p.next else if (done) p.done else p.sub,
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
                v.setViewVisibility(MIDS[i], if (r.mid.isEmpty()) View.GONE else View.VISIBLE)
                v.setTextViewText(MIDS[i], r.mid)
                v.setTextColor(MIDS[i], p.sub)
                v.setTextViewText(NAMES[i], r.left)
                v.setTextViewText(TIMES[i], r.right)
                v.setTextColor(NAMES[i], r.leftColor)
                v.setTextColor(TIMES[i], r.rightColor)
            }

            // Unusually long names may still need smaller text than the reference.
            val widest = rows.maxOf { Measure.row(it) }
            apply(v, fit, minOf(fit.scale, fit.rowWidth / widest).coerceAtLeast(MIN_SCALE))
            return v
        }

        /** What a widget size shows: short names?, optional lines, and the text scale. */
        private class Fit(
            val short: Boolean, val footer: Boolean, val place: Boolean, val circuit: Boolean,
            val scale: Float, val rowWidth: Float, // dp available for a row's text
            val height: Float,
        ) {
            /** Rows that fit below the header (and above the footer) at text scale [s]. */
            fun rowsAt(s: Float): Int {
                var used = 2 * PAD_V + HEADER_GAP + Measure.line(TITLE, medium = true) * s
                if (footer) used += FOOTER_GAP + Measure.line(FOOT) * s
                if (place) used += Measure.line(SUB) * s
                if (circuit) used += Measure.line(SUB) * s
                return ((height - used) / (Measure.line(ROW) * s + ROW_GAP)).toInt()
            }
        }

        /**
         * Measures the content at scale 1 and picks the largest text that fits. Sessions come first;
         * then the footer, location and circuit lines are added in that order while they don't
         * shrink the text (below normal size on big widgets). [forceFooter] keeps the footer regardless.
         */
        private fun fit(mgr: AppWidgetManager, id: Int, timeSamples: List<String>, forceFooter: Boolean): Fit {
            val (w, h) = size(mgr.getAppWidgetOptions(id))
            val rowWidth = w - 2 * ARROW_W - COL_GAP
            if (w <= 0 || h <= 0) return Fit(false, true, true, true, 1f, Float.MAX_VALUE, 0f)

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

            val base = byHeight(forceFooter, false, false)
            val full = byWidth(false)
            val shortW = byWidth(true)
            // Full names unless they'd make the text noticeably smaller than normal (or than height allows).
            val short = full < 0.85f * minOf(shortW, base, 1f)
            val width = if (short) shortW else full
            val goal = minOf(width, base, MAX_SCALE, 1f) * 0.98f
            fun scale(f: Boolean, p: Boolean, c: Boolean) = minOf(width, byHeight(f, p, c), MAX_SCALE)
            val footer = forceFooter || scale(true, false, false) >= goal
            val place = footer && scale(true, true, false) >= goal
            val circuit = place && scale(true, true, true) >= goal
            return Fit(short, footer, place, circuit, scale(footer, place, circuit).coerceAtLeast(MIN_SCALE), rowWidth, h)
        }

        /** The widget's size in dp in the current orientation, slightly under to be safe. */
        private fun size(o: Bundle): Pair<Float, Float> {
            val landscape = Resources.getSystem().configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            // Android 12+: the launcher's exact sizes, portrait one tallest, landscape one widest.
            if (Build.VERSION.SDK_INT >= 31) {
                @Suppress("DEPRECATION")
                val sizes = o.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                if (!sizes.isNullOrEmpty()) {
                    val s = if (landscape) sizes.maxBy { it.width } else sizes.maxBy { it.height }
                    return EXACT_FUDGE * s.width to EXACT_FUDGE * s.height
                }
            }
            // Older: min/max ranges, which some launchers over-report (Pixel: 360x358 reported, 316x300 real).
            val w = o.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val h = o.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            return SIZE_FUDGE * w to SIZE_FUDGE * h
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
            MIDS.forEach { size(it, ROW) }
            TIMES.forEach { size(it, ROW) }
        }

        /** Text metrics in dp, matching the layout's fonts (TextViews have no font padding). */
        private object Measure {
            private val regular = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
            private val medium = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }

            private fun paint(size: Float, medium: Boolean) = (if (medium) this.medium else regular).apply { textSize = size }
            fun width(text: String, size: Float, medium: Boolean = false) = paint(size, medium).measureText(text)
            fun line(size: Float, medium: Boolean = false) = paint(size, medium).fontMetrics.let { it.descent - it.ascent }
            fun row(r: Row) = width(r.left, ROW) + width(r.right, ROW, medium = true) +
                if (r.mid.isEmpty()) 0f else width(r.mid, ROW) + COL_GAP
        }

        private class Row(val left: String, val right: String, val leftColor: Int, val rightColor: Int, val mid: String = "")

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
