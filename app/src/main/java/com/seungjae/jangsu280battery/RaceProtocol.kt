package com.seungjae.jangsu280battery

import org.json.JSONArray
import org.json.JSONObject

/** Platform-neutral RACE protocol models. Android/iOS/server must keep these field semantics identical. */
data class RaceGate(
    val name: String,
    val type: String,
    val routeM: Double,
    val lat: Double,
    val lon: Double,
    val bearingDeg: Double,
    val widthM: Double
) {
    fun toJson() = JSONObject().apply {
        put("name", name); put("type", type); put("route_m", routeM)
        put("lat", lat); put("lon", lon); put("bearing_deg", bearingDeg); put("width_m", widthM)
    }
    companion object {
        fun fromJson(o: JSONObject) = RaceGate(
            o.optString("name", "Gate"), o.optString("type", "SECTOR").uppercase(),
            o.optDouble("route_m", 0.0), o.optDouble("lat", 0.0), o.optDouble("lon", 0.0),
            o.optDouble("bearing_deg", 0.0), o.optDouble("width_m", 5.0).coerceIn(1.0, 20.0)
        )
    }
}

data class RaceReferencePoint(val routeM: Double, val elapsedMs: Long) {
    fun toJson() = JSONObject().apply { put("m", routeM); put("t", elapsedMs) }
    companion object { fun fromJson(o: JSONObject) = RaceReferencePoint(o.optDouble("m", 0.0), o.optLong("t", 0L)) }
}

data class RaceEventConfig(
    val eventId: Long,
    val eventCode: String,
    val name: String,
    val courseServerId: Long?,
    val courseName: String,
    val distanceM: Double,
    val gates: List<RaceGate>,
    val reference: List<RaceReferencePoint> = emptyList(),
    val leaderName: String = "",
    val leaderElapsedMs: Long? = null
) {
    fun toJson() = JSONObject().apply {
        put("event_id", eventId); put("event_code", eventCode); put("name", name)
        if (courseServerId != null) put("course_id", courseServerId)
        put("course_name", courseName); put("distance_m", distanceM)
        put("gates", JSONArray().apply { gates.forEach { put(it.toJson()) } })
        put("reference", JSONArray().apply { reference.forEach { put(it.toJson()) } })
        put("leader_name", leaderName)
        leaderElapsedMs?.let { put("leader_elapsed_ms", it) }
    }

    companion object {
        fun fromJson(o: JSONObject): RaceEventConfig {
            val gatesA = o.optJSONArray("gates") ?: JSONArray()
            val refA = o.optJSONArray("reference") ?: JSONArray()
            return RaceEventConfig(
                eventId = o.optLong("event_id", o.optLong("id", 0L)),
                eventCode = o.optString("event_code", o.optString("code", "")).uppercase(),
                name = o.optString("name", "RACE"),
                courseServerId = if (o.has("course_id") && !o.isNull("course_id")) o.optLong("course_id") else null,
                courseName = o.optString("course_name", "RACE Course"),
                distanceM = o.optDouble("distance_m", o.optDouble("distance_km", 0.0) * 1000.0),
                gates = (0 until gatesA.length()).mapNotNull { gatesA.optJSONObject(it)?.let(RaceGate::fromJson) },
                reference = (0 until refA.length()).mapNotNull { refA.optJSONObject(it)?.let(RaceReferencePoint::fromJson) },
                leaderName = o.optString("leader_name", ""),
                leaderElapsedMs = if (o.has("leader_elapsed_ms") && !o.isNull("leader_elapsed_ms")) o.optLong("leader_elapsed_ms") else null
            )
        }
    }
}

data class RaceSectorResult(
    val index: Int,
    val name: String,
    val sectorMs: Long,
    val splitMs: Long,
    val rank: Int? = null
) {
    fun toJson() = JSONObject().apply {
        put("index", index); put("name", name); put("sector_ms", sectorMs); put("split_ms", splitMs)
        rank?.let { put("rank", it) }
    }
    companion object {
        fun fromJson(o: JSONObject) = RaceSectorResult(
            o.optInt("index", 1), o.optString("name", "S${o.optInt("index", 1)}"),
            o.optLong("sector_ms", 0L), o.optLong("split_ms", 0L),
            if (o.has("rank") && !o.isNull("rank")) o.optInt("rank") else null
        )
    }
}

data class RaceRunSummary(
    val runId: String,
    val runNumber: Int,
    val eventCode: String,
    val eventName: String,
    val courseId: String,
    val courseName: String,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val elapsedMs: Long,
    val status: String,
    val sectors: List<RaceSectorResult>,
    val reference: List<RaceReferencePoint>,
    val maxSpeedKph: Double,
    val maxGpsAccuracyM: Double,
    val maxOffRouteM: Double
) {
    fun toJson() = JSONObject().apply {
        put("run_id", runId); put("run_number", runNumber); put("event_code", eventCode); put("event_name", eventName)
        put("course_id", courseId); put("course_name", courseName); put("started_at_ms", startedAtMs); put("finished_at_ms", finishedAtMs)
        put("elapsed_ms", elapsedMs); put("status", status); put("max_speed_kph", maxSpeedKph)
        put("max_gps_accuracy_m", maxGpsAccuracyM); put("max_off_route_m", maxOffRouteM)
        put("sectors", JSONArray().apply { sectors.forEach { put(it.toJson()) } })
        put("reference", JSONArray().apply { reference.forEach { put(it.toJson()) } })
    }
    companion object {
        fun fromJson(o: JSONObject): RaceRunSummary {
            val s = o.optJSONArray("sectors") ?: JSONArray(); val r = o.optJSONArray("reference") ?: JSONArray()
            return RaceRunSummary(
                o.optString("run_id"), o.optInt("run_number", 1), o.optString("event_code"), o.optString("event_name"),
                o.optString("course_id"), o.optString("course_name"), o.optLong("started_at_ms"), o.optLong("finished_at_ms"),
                o.optLong("elapsed_ms"), o.optString("status", "REVIEW"),
                (0 until s.length()).mapNotNull { s.optJSONObject(it)?.let(RaceSectorResult::fromJson) },
                (0 until r.length()).mapNotNull { r.optJSONObject(it)?.let(RaceReferencePoint::fromJson) },
                o.optDouble("max_speed_kph", 0.0), o.optDouble("max_gps_accuracy_m", 0.0), o.optDouble("max_off_route_m", 0.0)
            )
        }
    }
}

fun formatRaceTime(ms: Long): String {
    val safe = ms.coerceAtLeast(0L); val minutes = safe / 60000; val sec = (safe % 60000) / 1000; val milli = safe % 1000
    return if (minutes > 0) "%d:%02d.%03d".format(minutes, sec, milli) else "%d.%03d".format(sec, milli)
}
