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
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TypefaceSpan
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
        private const val INNER_GAP = 6f
        private const val SIZE_FUDGE = 0.85f
        private const val SLACK = 1.08f // the system font can render a little wider than measured
        private const val EXACT_FUDGE = 0.97f
        private const val MAX_ROWS = 5 // sessions in a weekend

        // Table cells per column, one per row: a header plus Schedule.RESULT_ROWS results.
        private val POS = intArrayOf(
            R.id.pos0, R.id.pos1, R.id.pos2, R.id.pos3, R.id.pos4, R.id.pos5,
            R.id.pos6, R.id.pos7, R.id.pos8, R.id.pos9, R.id.pos10,
        )
        private val NAMES = intArrayOf(
            R.id.name0, R.id.name1, R.id.name2, R.id.name3, R.id.name4, R.id.name5,
            R.id.name6, R.id.name7, R.id.name8, R.id.name9, R.id.name10,
        )
        private val LAPS = intArrayOf(
            R.id.lap0, R.id.lap1, R.id.lap2, R.id.lap3, R.id.lap4, R.id.lap5,
            R.id.lap6, R.id.lap7, R.id.lap8, R.id.lap9, R.id.lap10,
        )
        private val DAYS = intArrayOf(
            R.id.day0, R.id.day1, R.id.day2, R.id.day3, R.id.day4, R.id.day5,
            R.id.day6, R.id.day7, R.id.day8, R.id.day9, R.id.day10,
        )
        private val TIMES = intArrayOf(
            R.id.time0, R.id.time1, R.id.time2, R.id.time3, R.id.time4, R.id.time5,
            R.id.time6, R.id.time7, R.id.time8, R.id.time9, R.id.time10,
        )
        private val RPOS = intArrayOf(
            R.id.rpos0, R.id.rpos1, R.id.rpos2, R.id.rpos3, R.id.rpos4, R.id.rpos5,
            R.id.rpos6, R.id.rpos7, R.id.rpos8, R.id.rpos9, R.id.rpos10,
        )
        private val CELLS = listOf(POS, NAMES, LAPS, DAYS, TIMES, RPOS)

        private fun state(ctx: Context) = ctx.getSharedPreferences("widgets", Context.MODE_PRIVATE)

        private val SETTING_KEYS = listOf("bg", "main", "past", "next", "footer", "footdate", "textsize")

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

        /** Text size in percent of normal; the same at every widget size. */
        fun textSize(ctx: Context, id: Int) = setting(ctx, id, "textsize") ?: 100

        fun setTextSize(ctx: Context, id: Int, percent: Int) = putSettings(ctx, id, listOf("textsize" to percent))

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
                v.setViewVisibility(R.id.table, View.GONE)
                return v
            }

            // Hosts may replay these actions onto the previous views, so undo the empty state explicitly.
            listOf(R.id.prev, R.id.next, R.id.table).forEach { v.setViewVisibility(it, View.VISIBLE) }

            val state = state(ctx)
            val offset = state.getInt("off_$id", 0)
            val index = (upcoming + offset).coerceIn(0, races.lastIndex)
            if (index - upcoming != offset) state.edit().putInt("off_$id", index - upcoming).apply()
            val race = races[index]
            val locale = Locale.getDefault()
            val dayOnly = DateTimeFormatter.ofPattern("EEE", locale)
            val timeFmt = DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm a", locale)
            // Size for the widest days and times rather than what's shown, so text doesn't jump between races.
            val days = (1..7).map { ZonedDateTime.of(2001, 1, it, 0, 0, 0, 0, ZoneId.systemDefault()).format(dayOnly) }
            val times = (0..23).map { ZonedDateTime.of(2001, 1, 1, it, 59, 0, 0, ZoneId.systemDefault()).format(timeFmt) }
            val foot = footer(ctx, id)
            val fit = fit(mgr, id, race.sessions.map { it.name }, days, times, foot.always, textSize(ctx, id) / 100f)
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
            // Narrow widgets (short session names) get the short label too.
            v.setTextViewText(R.id.mode, (if (track) "⇄ Track" else "⇄ Local") + if (fit.short) "" else " time")
            v.setTextViewText(R.id.round, if (dateBelow) dates else "Round ${race.round} / ${races.last().round}")
            v.setTextColor(R.id.round, if (index == upcoming) p.sub else p.next)
            v.setOnClickPendingIntent(R.id.mode, broadcast(ctx, ACTION_MODE, id))

            // Finished race: qualifying (left, with lap times when they fit) and race (right), once published.
            val results = if (race.end <= now) Schedule.results(ctx, race).also { if (it == null) missing += race } else null
            val rows = if (results != null) {
                // As many results as fit below the header.
                val n = minOf((fit.rowsAt(fit.scale) - 1).coerceAtLeast(1), maxOf(results.quali.size, results.race.size))
                fun rows(codes: Boolean, laps: Boolean): List<Row> {
                    fun label(d: Driver) = if (codes) d.code else d.name
                    return listOf(Row(if (codes) "Quali" else "Qualifying", p.sub, "Race", p.sub, mono = true)) + (0 until n).map { i ->
                        val q = results.quali.getOrNull(i)
                        val r = results.race.getOrNull(i)
                        Row(
                            q?.let(::label) ?: "", p.text, r?.let(::label) ?: "", p.text,
                            pos = if (q != null) "${i + 1}" else "",
                            lap = if (laps) q?.time ?: "" else "",
                            rpos = if (r != null) "${i + 1}" else "",
                            mono = true,
                        )
                    }
                }
                // Names before lap times, lap times before codes; the first that fits.
                val hasLaps = results.quali.any { it.time.isNotEmpty() }
                val options = listOfNotNull(
                    if (!fit.short && hasLaps) rows(codes = false, laps = true) else null,
                    if (!fit.short) rows(codes = false, laps = false) else null,
                    if (hasLaps) rows(codes = true, laps = true) else null,
                    rows(codes = true, laps = false),
                )
                options.firstOrNull { Measure.table(it) * fit.scale * SLACK <= fit.rowWidth } ?: options.last()
            } else {
                // Only the upcoming weekend highlights its current session.
                val current = if (index == upcoming) race.sessions.indexOfFirst { it.end > now } else -1
                // Too small for the whole weekend: leave out finished sessions first, then the last ones.
                val k = fit.rowsAt(fit.scale).coerceIn(1, race.sessions.size)
                val open = race.sessions.indexOfFirst { it.end > now }.let { if (it < 0) race.sessions.size else it }
                val start = minOf(open, race.sessions.size - k)
                race.sessions.withIndex().drop(start).take(k).map { (i, s) ->
                    val t = s.start.atZone(zone)
                    val done = s.end <= now
                    val color = if (i == current) p.next else if (done) p.done else p.sub
                    Row(
                        if (fit.short) s.short else s.name, if (i == current) p.next else if (done) p.done else p.text,
                        if (s.hasTime) t.format(timeFmt) else "TBC", color,
                        day = t.format(dayOnly),
                    )
                }
            }
            // Columns without content are hidden along with their gaps.
            val cols = listOf(Row::pos, Row::name, Row::lap, Row::day, Row::right, Row::rpos)
            listOf(R.id.col_pos, R.id.col_name, R.id.col_lap, R.id.col_day, R.id.col_time, R.id.col_rpos).forEachIndexed { c, col ->
                v.setViewVisibility(col, if (c == 1 || rows.any { cols[c](it).isNotEmpty() }) View.VISIBLE else View.GONE)
            }
            for (i in 0..Schedule.RESULT_ROWS) {
                val r = rows.getOrNull(i)
                CELLS.forEach { v.setViewVisibility(it[i], if (r == null) View.GONE else View.VISIBLE) }
                if (r == null) continue
                CELLS.forEachIndexed { c, cells -> v.setTextViewText(cells[i], if (r.mono) monospace(cols[c](r)) else cols[c](r)) }
                v.setTextColor(POS[i], p.sub)
                v.setTextColor(NAMES[i], r.nameColor)
                v.setTextColor(LAPS[i], p.sub)
                v.setTextColor(DAYS[i], r.rightColor)
                v.setTextColor(TIMES[i], r.rightColor)
                v.setTextColor(RPOS[i], p.sub)
            }

            apply(v, fit, fit.scale)
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
                return ((height - used) / (Measure.rowLine * s + ROW_GAP)).toInt()
            }
        }

        /**
         * What a widget size shows at text scale [scale], which doesn't depend on the size. Full session
         * [names] when they fit; then the footer, location and circuit lines are added in that order while
         * a whole weekend still fits below them. [forceFooter] keeps the footer regardless. Widgets too
         * small for a whole weekend show fewer rows.
         */
        private fun fit(
            mgr: AppWidgetManager, id: Int, names: List<String>, days: List<String>, times: List<String>,
            forceFooter: Boolean, scale: Float,
        ): Fit {
            val (w, h) = size(mgr.getAppWidgetOptions(id))
            val rowWidth = w - 2 * ARROW_W
            if (w <= 0 || h <= 0) return Fit(false, true, true, true, scale, Float.MAX_VALUE, Float.MAX_VALUE)

            // Session table: name | day | time.
            val full = names.maxOf { Measure.width(it, ROW) } + COL_GAP +
                days.maxOf { Measure.width(it, ROW, medium = true) } + INNER_GAP + times.maxOf { Measure.width(it, ROW, medium = true) }
            val short = full * scale * SLACK > rowWidth
            fun fits(f: Boolean, p: Boolean, c: Boolean) = Fit(short, f, p, c, scale, rowWidth, h).rowsAt(scale) >= MAX_ROWS
            val footer = forceFooter || fits(true, false, false)
            val place = footer && fits(footer, true, false)
            val circuit = place && fits(footer, true, true)
            return Fit(short, footer, place, circuit, scale, rowWidth, h)
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
            CELLS.forEach { cells -> cells.forEach { size(it, ROW) } }
        }

        /** Text metrics in dp, matching the layout's fonts (TextViews have no font padding). */
        private object Measure {
            private val regular = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
            private val medium = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
            private val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }

            private fun paint(size: Float, medium: Boolean, mono: Boolean = false) =
                (if (mono) this.mono else if (medium) this.medium else regular).apply { textSize = size }
            fun width(text: String, size: Float, medium: Boolean = false, mono: Boolean = false) = paint(size, medium, mono).measureText(text)
            fun line(size: Float, medium: Boolean = false) = paint(size, medium).fontMetrics.let { it.descent - it.ascent }

            /** Height of a table row's text: the taller of the normal and monospace fonts. */
            val rowLine get() = maxOf(line(ROW), paint(ROW, medium = false, mono = true).fontMetrics.let { it.descent - it.ascent })

            /** Width of the table at scale 1: each shown column as wide as its widest cell, plus the layout's gaps. */
            fun table(rows: List<Row>): Float {
                fun col(get: (Row) -> String, medium: Boolean = false) = rows.maxOf { width(get(it), ROW, medium, it.mono) }
                fun gap(w: Float, gap: Float) = if (w > 0) w + gap else 0f
                return gap(col(Row::pos), INNER_GAP) + col(Row::name) + gap(col(Row::lap), COL_GAP + INNER_GAP) +
                    gap(col(Row::day, medium = true), COL_GAP) + gap(col(Row::right, medium = true), INNER_GAP) +
                    gap(col(Row::rpos, medium = true), INNER_GAP)
            }
        }

        /** One table row: [name] on the left, [right] (time or race result) on the right, optional other columns. */
        private class Row(
            val name: String, val nameColor: Int, val right: String, val rightColor: Int,
            val pos: String = "", val lap: String = "", val day: String = "", val rpos: String = "",
            val mono: Boolean = false, // results: monospace so codes, positions and lap times line up
        )

        private fun monospace(text: String) = SpannableString(text).apply {
            setSpan(TypefaceSpan("monospace"), 0, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }

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
