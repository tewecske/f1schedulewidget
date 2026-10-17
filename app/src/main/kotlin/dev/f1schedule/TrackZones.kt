package dev.f1schedule

import java.time.ZoneId

/** Circuit time zones. The API only gives coordinates, so unknown circuits use the nearest known one. */
object TrackZones {
    private class Track(val id: String, val lat: Double, val lng: Double, val zone: String)

    private val TRACKS = listOf(
        Track("albert_park", -37.85, 144.97, "Australia/Melbourne"),
        Track("americas", 30.13, -97.64, "America/Chicago"),
        Track("bahrain", 26.03, 50.51, "Asia/Bahrain"),
        Track("baku", 40.37, 49.85, "Asia/Baku"),
        Track("catalunya", 41.57, 2.26, "Europe/Madrid"),
        Track("hungaroring", 47.58, 19.25, "Europe/Budapest"),
        Track("imola", 44.34, 11.72, "Europe/Rome"),
        Track("interlagos", -23.70, -46.70, "America/Sao_Paulo"),
        Track("jeddah", 21.63, 39.10, "Asia/Riyadh"),
        Track("losail", 25.49, 51.45, "Asia/Qatar"),
        Track("madring", 40.47, -3.62, "Europe/Madrid"),
        Track("marina_bay", 1.29, 103.86, "Asia/Singapore"),
        Track("miami", 25.96, -80.24, "America/New_York"),
        Track("monaco", 43.73, 7.42, "Europe/Monaco"),
        Track("monza", 45.62, 9.28, "Europe/Rome"),
        Track("red_bull_ring", 47.22, 14.76, "Europe/Vienna"),
        Track("ricard", 43.25, 5.79, "Europe/Paris"),
        Track("rodriguez", 19.40, -99.09, "America/Mexico_City"),
        Track("sepang", 2.76, 101.74, "Asia/Kuala_Lumpur"),
        Track("shanghai", 31.34, 121.22, "Asia/Shanghai"),
        Track("silverstone", 52.08, -1.02, "Europe/London"),
        Track("spa", 50.44, 5.97, "Europe/Brussels"),
        Track("suzuka", 34.84, 136.54, "Asia/Tokyo"),
        Track("vegas", 36.11, -115.17, "America/Los_Angeles"),
        Track("villeneuve", 45.50, -73.52, "America/Toronto"),
        Track("yas_marina", 24.47, 54.60, "Asia/Dubai"),
        Track("zandvoort", 52.39, 4.54, "Europe/Amsterdam"),
    )

    fun of(circuitId: String, lat: Double, lng: Double): ZoneId {
        val track = TRACKS.firstOrNull { it.id == circuitId }
            ?: TRACKS.minBy { (it.lat - lat) * (it.lat - lat) + (it.lng - lng) * (it.lng - lng) }
        return ZoneId.of(track.zone)
    }
}
