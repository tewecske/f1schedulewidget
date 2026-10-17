package dev.f1schedule

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class Session(val name: String, val start: Instant, val hasTime: Boolean, val length: Duration) {
    val end: Instant get() = start + length
}

class Race(
    val round: String,
    val name: String,
    val circuit: String,
    val city: String,
    val country: String,
    val trackZone: ZoneId,
    val sessions: List<Session>, // chronological, race last
) {
    val end: Instant get() = sessions.last().end
}

/** Season data from the Jolpica F1 API (Ergast successor), cached in SharedPreferences. */
object Schedule {
    private const val API = "https://api.jolpi.ca/ergast/f1/"
    private val MAX_AGE: Duration = Duration.ofHours(2)

    // API key -> label, typical length. Order is irrelevant; sessions are sorted by time.
    private val SESSIONS = listOf(
        Triple("FirstPractice", "Practice 1", 60L),
        Triple("SecondPractice", "Practice 2", 60L),
        Triple("ThirdPractice", "Practice 3", 60L),
        Triple("SprintShootout", "Sprint Qualifying", 45L),
        Triple("SprintQualifying", "Sprint Qualifying", 45L),
        Triple("Sprint", "Sprint", 60L),
        Triple("Qualifying", "Qualifying", 60L),
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("schedule", Context.MODE_PRIVATE)

    /** Cached season, chronological. */
    fun races(ctx: Context): List<Race> =
        prefs(ctx).getString("json", null)?.let { runCatching { parse(it) }.getOrNull() } ?: emptyList()

    fun isStale(ctx: Context): Boolean {
        val fetched = Instant.ofEpochMilli(prefs(ctx).getLong("fetched", 0))
        val now = Instant.now()
        return fetched + MAX_AGE < now || races(ctx).none { it.end > now }
    }

    /** Downloads this season (or next, once this one is over). Blocking; returns success. */
    fun fetch(ctx: Context): Boolean = runCatching {
        val now = Instant.now()
        val year = LocalDate.now(ZoneOffset.UTC).year
        var json = get("$API$year.json?limit=100")
        if (parse(json).none { it.end > now }) {
            runCatching { get("$API${year + 1}.json?limit=100") }.getOrNull()
                ?.takeIf { parse(it).isNotEmpty() }
                ?.let { json = it }
        }
        prefs(ctx).edit().putString("json", json).putLong("fetched", System.currentTimeMillis()).apply()
    }.isSuccess

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        try {
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(json: String): List<Race> {
        val races = JSONObject(json).getJSONObject("MRData").getJSONObject("RaceTable").getJSONArray("Races")
        return (0 until races.length()).map { i ->
            val r = races.getJSONObject(i)
            val circuit = r.getJSONObject("Circuit")
            val loc = circuit.getJSONObject("Location")
            val sessions = SESSIONS.mapNotNull { (key, label, mins) ->
                r.optJSONObject(key)?.let { session(label, it, mins) }
            }.sortedBy { it.start } + session("Race", r, 120)
            Race(
                round = r.getString("round"),
                name = r.getString("raceName"),
                circuit = circuit.getString("circuitName"),
                city = loc.getString("locality"),
                country = loc.getString("country"),
                trackZone = TrackZones.of(circuit.getString("circuitId"), loc.optDouble("lat"), loc.optDouble("long")),
                sessions = sessions,
            )
        }
    }

    private fun session(name: String, o: JSONObject, minutes: Long): Session {
        val time = o.optString("time")
        val hasTime = time.isNotEmpty()
        val start = Instant.parse("${o.getString("date")}T${if (hasTime) time else "00:00:00Z"}")
        return Session(name, start, hasTime, Duration.ofMinutes(if (hasTime) minutes else 24 * 60))
    }
}
