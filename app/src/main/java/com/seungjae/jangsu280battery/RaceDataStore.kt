package com.seungjae.jangsu280battery

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Durable local-first RACE storage. Completed runs are saved at FINISH before any network upload. */
class RaceDataStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("race_runtime_v1", Context.MODE_PRIVATE)
    private val dir = File(app.filesDir, "race").apply { mkdirs() }
    private val rawDir = File(dir, "raw").apply { mkdirs() }
    private val completedFile = File(dir, "completed_runs.json")

    data class Snapshot(
        val state: String = "STOPPED", val eventCode: String = "", val eventName: String = "", val courseId: String = "", val courseName: String = "",
        val runId: String = "", val runNumber: Int = 0, val startedAtMs: Long = 0L, val lastGateAtMs: Long = 0L, val elapsedMs: Long = 0L,
        val routeM: Double = 0.0, val totalM: Double = 0.0, val deltaMs: Long? = null, val nextGateIndex: Int = 0, val currentSector: String = "",
        val gpsAccuracyM: Double = 0.0, val maxSpeedKph: Double = 0.0, val maxGpsAccuracyM: Double = 0.0, val maxOffRouteM: Double = 0.0,
        val jumpCount: Int = 0, val validation: String = "REVIEW", val sectors: List<RaceSectorResult> = emptyList(), val finishRank: Int? = null, val serverStatus: String = ""
    ) {
        fun toJson() = JSONObject().apply {
            put("state", state); put("event_code", eventCode); put("event_name", eventName); put("course_id", courseId); put("course_name", courseName)
            put("run_id", runId); put("run_number", runNumber); put("started_at_ms", startedAtMs); put("last_gate_at_ms", lastGateAtMs)
            put("elapsed_ms", elapsedMs); put("route_m", routeM); put("total_m", totalM); deltaMs?.let { put("delta_ms", it) }
            put("next_gate_index", nextGateIndex); put("current_sector", currentSector); put("gps_accuracy_m", gpsAccuracyM)
            put("max_speed_kph", maxSpeedKph); put("max_gps_accuracy_m", maxGpsAccuracyM); put("max_off_route_m", maxOffRouteM)
            put("jump_count", jumpCount); put("validation", validation); finishRank?.let { put("finish_rank", it) }; put("server_status", serverStatus)
            put("sectors", JSONArray().apply { sectors.forEach { put(it.toJson()) } })
        }
        companion object {
            fun fromJson(o: JSONObject): Snapshot {
                val a = o.optJSONArray("sectors") ?: JSONArray()
                return Snapshot(
                    o.optString("state", "STOPPED"), o.optString("event_code"), o.optString("event_name"), o.optString("course_id"), o.optString("course_name"),
                    o.optString("run_id"), o.optInt("run_number", 0), o.optLong("started_at_ms"), o.optLong("last_gate_at_ms"), o.optLong("elapsed_ms"),
                    o.optDouble("route_m", 0.0), o.optDouble("total_m", 0.0), if (o.has("delta_ms") && !o.isNull("delta_ms")) o.optLong("delta_ms") else null,
                    o.optInt("next_gate_index", 0), o.optString("current_sector"), o.optDouble("gps_accuracy_m", 0.0), o.optDouble("max_speed_kph", 0.0),
                    o.optDouble("max_gps_accuracy_m", 0.0), o.optDouble("max_off_route_m", 0.0), o.optInt("jump_count", 0), o.optString("validation", "REVIEW"),
                    (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(RaceSectorResult::fromJson) },
                    if (o.has("finish_rank") && !o.isNull("finish_rank")) o.optInt("finish_rank") else null, o.optString("server_status", "")
                )
            }
        }
    }

    @Synchronized fun writeSnapshot(s: Snapshot) { prefs.edit().putString("snapshot", s.toJson().toString()).apply() }
    fun snapshot(): Snapshot = runCatching { Snapshot.fromJson(JSONObject(prefs.getString("snapshot", "{}"))) }.getOrDefault(Snapshot())

    fun saveActiveConfig(config: RaceEventConfig, courseId: String, reference: List<RaceReferencePoint>) {
        val o = config.copy(reference = reference).toJson().apply { put("local_course_id", courseId) }
        prefs.edit().putString("active_config", o.toString()).apply()
    }
    fun activeConfig(): Pair<RaceEventConfig, String>? = runCatching { val o = JSONObject(prefs.getString("active_config", "")); RaceEventConfig.fromJson(o) to o.optString("local_course_id") }.getOrNull()
    fun clearActiveConfig() { prefs.edit().remove("active_config").apply() }

    fun appendRaw(runId: String, location: Location, routeM: Double, offRouteM: Double) {
        if (runId.isBlank()) return
        val o = JSONObject().apply {
            put("t", location.time); put("lat", location.latitude); put("lon", location.longitude)
            if (location.hasAltitude()) put("alt", location.altitude); if (location.hasSpeed()) put("speed_mps", location.speed.toDouble()); if (location.hasAccuracy()) put("accuracy_m", location.accuracy.toDouble())
            put("route_m", routeM); put("off_route_m", offRouteM); put("provider", location.provider ?: "")
        }
        File(rawDir, "$runId.jsonl").appendText(o.toString() + "\n", Charsets.UTF_8)
    }

    @Synchronized fun saveCompleted(summary: RaceRunSummary) {
        val current = runCatching { JSONArray(completedFile.readText(Charsets.UTF_8)) }.getOrDefault(JSONArray()); val next = JSONArray(); var replaced = false
        for (i in 0 until current.length()) { val old = current.optJSONObject(i) ?: continue; if (old.optString("run_id") == summary.runId) { next.put(summary.toJson()); replaced = true } else next.put(old) }
        if (!replaced) next.put(summary.toJson()); val tmp = File(dir, "completed_runs.tmp"); tmp.writeText(next.toString(), Charsets.UTF_8); tmp.copyTo(completedFile, overwrite = true); tmp.delete()
    }
    fun completed(): List<RaceRunSummary> { val a = runCatching { JSONArray(completedFile.readText(Charsets.UTF_8)) }.getOrDefault(JSONArray()); return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> runCatching { RaceRunSummary.fromJson(o) }.getOrNull() } } }
    fun nextRunNumber(eventCode: String): Int = completed().count { it.eventCode == eventCode } + 1

    private fun normServer(value: String): String = value.trim().trimEnd('/').lowercase()

    fun saveJoined(config: RaceEventConfig, token: String, localCourseId: String, serverUrl: String = "") {
        prefs.edit().putString("joined_${config.eventCode}", JSONObject().apply {
            put("config", config.toJson()); put("token", token); put("local_course_id", localCourseId); put("server_url", serverUrl.trim().trimEnd('/'))
        }.toString()).putString("last_event_code", config.eventCode).apply()
    }

    data class Joined(val config: RaceEventConfig, val token: String, val localCourseId: String, val serverUrl: String = "")

    fun joined(eventCode: String, expectedServerUrl: String? = null): Joined? = runCatching {
        val o = JSONObject(prefs.getString("joined_${eventCode.uppercase()}", "")); val storedServer = o.optString("server_url")
        if (!expectedServerUrl.isNullOrBlank() && normServer(storedServer) != normServer(expectedServerUrl)) return null
        Joined(RaceEventConfig.fromJson(o.getJSONObject("config")), o.optString("token"), o.optString("local_course_id"), storedServer)
    }.getOrNull()
    fun lastJoined(expectedServerUrl: String? = null): Joined? = prefs.getString("last_event_code", null)?.let { joined(it, expectedServerUrl) }

    @Synchronized fun enqueue(type: String, eventCode: String, payload: JSONObject, serverUrl: String = "") {
        val a = runCatching { JSONArray(prefs.getString("network_queue", "[]")) }.getOrDefault(JSONArray())
        val key = "$type:${payload.optString("run_id")}:${payload.optInt("sector_index", -1)}"; val out = JSONArray(); var replaced = false
        fun item() = JSONObject().apply { put("key", key); put("type", type); put("event_code", eventCode); put("server_url", serverUrl.trim().trimEnd('/')); put("payload", payload) }
        for (i in 0 until a.length()) { val o = a.optJSONObject(i) ?: continue; if (o.optString("key") == key) { out.put(item()); replaced = true } else out.put(o) }
        if (!replaced) out.put(item()); prefs.edit().putString("network_queue", out.toString()).apply()
    }

    @Synchronized fun queued(): List<JSONObject> { val a = runCatching { JSONArray(prefs.getString("network_queue", "[]")) }.getOrDefault(JSONArray()); return (0 until a.length()).mapNotNull(a::optJSONObject) }
    @Synchronized fun removeQueued(key: String) { val a = runCatching { JSONArray(prefs.getString("network_queue", "[]")) }.getOrDefault(JSONArray()); val out = JSONArray(); for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { it.optString("key") != key }?.let(out::put); prefs.edit().putString("network_queue", out.toString()).apply() }
}
