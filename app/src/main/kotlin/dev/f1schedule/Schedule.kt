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

class Session(val name: String, val short: String, val start: Instant, val hasTime: Boolean, val length: Duration) {
    val end: Instant get() = start + length
}

class Race(
    val season: String,
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

class Driver(val name: String, val code: String)

/** Top 3 of qualifying and the race. */
class Podium(val quali: List<Driver>, val race: List<Driver>)

/** Season data from the Jolpica F1 API (Ergast successor), cached in SharedPreferences. */
object Schedule {
    private const val API = "https://api.jolpi.ca/ergast/f1/"
    private val MAX_AGE: Duration = Duration.ofHours(2)
    private val PODIUM_RETRY: Duration = Duration.ofMinutes(10)

    private class Kind(val key: String, val name: String, val short: String, val minutes: Long)

    // Order is irrelevant; sessions are sorted by time.
    private val SESSIONS = listOf(
        Kind("FirstPractice", "Practice 1", "FP1", 60),
        Kind("SecondPractice", "Practice 2", "FP2", 60),
        Kind("ThirdPractice", "Practice 3", "FP3", 60),
        Kind("SprintShootout", "Sprint Qualifying", "SQ", 45),
        Kind("SprintQualifying", "Sprint Qualifying", "SQ", 45),
        Kind("Sprint", "Sprint", "Sprint", 60),
        Kind("Qualifying", "Qualifying", "Quali", 60),
    )
    private val RACE = Kind("", "Race", "Race", 120)

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

    fun podium(ctx: Context, race: Race): Podium? =
        prefs(ctx).getString("podium_${race.season}_${race.round}", null)?.let { s ->
            // "Name:CODE|...;Name:CODE|..." (older caches have no codes)
            val (q, r) = s.split(";").map { part ->
                part.split("|").filter(String::isNotEmpty).map {
                    val name = it.substringBefore(":")
                    Driver(name, it.substringAfter(":", name.take(3).uppercase()))
                }
            }
            Podium(q, r)
        }

    /** Results appear some hours after a race; don't hammer the API until then. */
    fun shouldFetchPodium(ctx: Context, race: Race): Boolean {
        val tried = Instant.ofEpochMilli(prefs(ctx).getLong("podium_try_${race.season}_${race.round}", 0))
        return tried + PODIUM_RETRY < Instant.now()
    }

    /** Downloads top 3 of qualifying and race. Blocking; true once the race result is published. */
    fun fetchPodium(ctx: Context, race: Race): Boolean = runCatching {
        val key = "${race.season}_${race.round}"
        prefs(ctx).edit().putLong("podium_try_$key", System.currentTimeMillis()).apply()
        val base = "$API${race.season}/${race.round}"
        val result = top3(get("$base/results.json?limit=3"), "Results")
        if (result.isEmpty()) return false
        val quali = runCatching { top3(get("$base/qualifying.json?limit=3"), "QualifyingResults") }.getOrDefault(emptyList())
        fun enc(top: List<Driver>) = top.joinToString("|") { "${it.name}:${it.code}" }
        prefs(ctx).edit().putString("podium_$key", enc(quali) + ";" + enc(result)).apply()
    }.isSuccess

    private fun top3(json: String, key: String): List<Driver> {
        val races = JSONObject(json).getJSONObject("MRData").getJSONObject("RaceTable").getJSONArray("Races")
        if (races.length() == 0) return emptyList()
        val rows = races.getJSONObject(0).getJSONArray(key)
        return (0 until minOf(3, rows.length())).map {
            val d = rows.getJSONObject(it).getJSONObject("Driver")
            val name = d.getString("familyName")
            Driver(name, d.optString("code").ifEmpty { name.take(3).uppercase() })
        }
    }

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
            val sessions = SESSIONS.mapNotNull { k -> r.optJSONObject(k.key)?.let { session(k, it) } }
                .sortedBy { it.start } + session(RACE, r)
            Race(
                season = r.getString("season"),
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

    private fun session(kind: Kind, o: JSONObject): Session {
        val time = o.optString("time")
        val hasTime = time.isNotEmpty()
        val start = Instant.parse("${o.getString("date")}T${if (hasTime) time else "00:00:00Z"}")
        return Session(kind.name, kind.short, start, hasTime, Duration.ofMinutes(if (hasTime) kind.minutes else 24 * 60))
    }
}
