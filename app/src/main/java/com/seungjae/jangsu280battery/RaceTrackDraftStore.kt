package com.seungjae.jangsu280battery

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Durable raw GPS draft + RaceChrono-style trap store used by the phone track builder. */
class RaceTrackDraftStore(context: Context) {
    data class Draft(
        val id: String,
        val name: String,
        val state: String,
        val startedAtMs: Long,
        val updatedAtMs: Long,
        val distanceM: Double,
        val pointCount: Int
    )

    data class Point(
        val lat: Double,
        val lon: Double,
        val ele: Double,
        val timeMs: Long,
        val accuracyM: Double,
        val bearingDeg: Double,
        val routeM: Double
    )

    companion object {
        const val STATE_RECORDING = "RECORDING"
        const val STATE_PAUSED = "PAUSED"
        const val STATE_STOPPED = "STOPPED"
        private const val PREF = "race_track_builder_v1"
        private const val KEY_ACTIVE = "active_draft_id"
    }

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private val dir = File(app.filesDir, "race/track_drafts").apply { mkdirs() }

    fun start(name: String): Draft {
        val id = "track_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        File(dir, "$id.jsonl").delete()
        val now = System.currentTimeMillis()
        val d = Draft(id, name.trim().ifBlank { "새 RACE 코스" }, STATE_RECORDING, now, now, 0.0, 0)
        writeDraft(d)
        prefs.edit().putString(KEY_ACTIVE, id).apply()
        writeTraps(id, emptyList())
        return d
    }

    fun active(): Draft? = prefs.getString(KEY_ACTIVE, null)?.let(::readDraft)

    fun setState(id: String, state: String): Draft? {
        val old = readDraft(id) ?: return null
        val next = old.copy(state = state, updatedAtMs = System.currentTimeMillis())
        writeDraft(next)
        return next
    }

    fun append(id: String, location: Location, routeM: Double): Point {
        val p = Point(
            lat = location.latitude,
            lon = location.longitude,
            ele = if (location.hasAltitude()) location.altitude else 0.0,
            timeMs = location.time.takeIf { it > 0L } ?: System.currentTimeMillis(),
            accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else 99.0,
            bearingDeg = if (location.hasBearing()) location.bearing.toDouble() else Double.NaN,
            routeM = routeM
        )
        val obj = JSONObject().apply {
            put("lat", p.lat); put("lon", p.lon); put("ele", p.ele); put("time_ms", p.timeMs)
            put("accuracy_m", p.accuracyM); if (p.bearingDeg.isFinite()) put("bearing_deg", p.bearingDeg)
            put("route_m", p.routeM)
        }
        File(dir, "$id.jsonl").appendText(obj.toString() + "\n")
        readDraft(id)?.let { old ->
            writeDraft(old.copy(updatedAtMs = System.currentTimeMillis(), distanceM = routeM, pointCount = old.pointCount + 1))
        }
        return p
    }

    fun points(id: String): List<Point> {
        val f = File(dir, "$id.jsonl")
        if (!f.exists()) return emptyList()
        return f.useLines { lines ->
            lines.mapNotNull { line ->
                runCatching {
                    val o = JSONObject(line)
                    Point(
                        o.getDouble("lat"), o.getDouble("lon"), o.optDouble("ele", 0.0),
                        o.optLong("time_ms", 0L), o.optDouble("accuracy_m", 99.0),
                        if (o.has("bearing_deg")) o.optDouble("bearing_deg") else Double.NaN,
                        o.optDouble("route_m", 0.0)
                    )
                }.getOrNull()
            }.toList()
        }
    }

    fun lastPoint(id: String): Point? = points(id).lastOrNull()

    fun traps(id: String): List<RaceGate> {
        val raw = prefs.getString("traps_$id", "[]") ?: "[]"
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(RaceGate::fromJson) }.sortedBy { it.routeM }
        }.getOrDefault(emptyList())
    }

    fun writeTraps(id: String, gates: List<RaceGate>) {
        val a = JSONArray().apply { gates.sortedBy { it.routeM }.forEach { put(it.toJson()) } }
        prefs.edit().putString("traps_$id", a.toString()).apply()
    }

    fun clearActive(id: String) {
        if (prefs.getString(KEY_ACTIVE, null) == id) prefs.edit().remove(KEY_ACTIVE).apply()
    }

    fun delete(id: String) {
        File(dir, "$id.jsonl").delete()
        prefs.edit().remove("draft_$id").remove("traps_$id").apply()
        clearActive(id)
    }

    private fun readDraft(id: String): Draft? {
        val raw = prefs.getString("draft_$id", null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            Draft(
                id = id,
                name = o.optString("name", "새 RACE 코스"),
                state = o.optString("state", STATE_STOPPED),
                startedAtMs = o.optLong("started_at_ms", 0L),
                updatedAtMs = o.optLong("updated_at_ms", 0L),
                distanceM = o.optDouble("distance_m", 0.0),
                pointCount = o.optInt("point_count", 0)
            )
        }.getOrNull()
    }

    private fun writeDraft(d: Draft) {
        prefs.edit().putString("draft_${d.id}", JSONObject().apply {
            put("name", d.name); put("state", d.state); put("started_at_ms", d.startedAtMs)
            put("updated_at_ms", d.updatedAtMs); put("distance_m", d.distanceM); put("point_count", d.pointCount)
        }.toString()).apply()
    }
}
