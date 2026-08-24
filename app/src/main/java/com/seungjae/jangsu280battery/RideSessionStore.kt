package com.seungjae.jangsu280battery

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RidePassEvent(val name: String, val routeKm: Double, val timestampMs: Long)

class RideSessionStore(context: Context) {
    companion object {
        private const val PREFS = "ride_report_state"
        private const val START_MS = "start_ms"
        private const val LAST_MS = "last_ms"
        private const val MAX_KM = "max_km"
        private const val SPEED_SUM = "speed_sum"
        private const val SPEED_COUNT = "speed_count"
        private const val EVENTS = "events"
        private const val LAST_WRITE_MS = "last_write_ms"
        private const val LAST_WRITE_KM = "last_write_km"
    }
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ensureStarted() {
        if (prefs.getLong(START_MS, 0L) == 0L) {
            val now = System.currentTimeMillis()
            prefs.edit().putLong(START_MS, now).putLong(LAST_MS, now).apply()
        }
    }

    fun recordProgress(routeKm: Double, speedKmh: Double) {
        ensureStarted()
        val now = System.currentTimeMillis()
        val oldWriteMs = prefs.getLong(LAST_WRITE_MS, 0L)
        val oldWriteKm = prefs.getFloat(LAST_WRITE_KM, 0f).toDouble()
        if (now - oldWriteMs < 10_000L && routeKm - oldWriteKm < 0.15) return
        val oldMax = prefs.getFloat(MAX_KM, 0f).toDouble()
        val editor = prefs.edit().putLong(LAST_MS, now).putLong(LAST_WRITE_MS, now).putFloat(LAST_WRITE_KM, routeKm.toFloat())
        if (routeKm > oldMax) editor.putFloat(MAX_KM, routeKm.toFloat())
        if (speedKmh in 2.0..60.0) {
            editor.putFloat(SPEED_SUM, prefs.getFloat(SPEED_SUM, 0f) + speedKmh.toFloat())
            editor.putInt(SPEED_COUNT, prefs.getInt(SPEED_COUNT, 0) + 1)
        }
        editor.apply()
    }

    fun recordCheckpoint(name: String, routeKm: Double) {
        ensureStarted()
        val list = events().toMutableList()
        if (list.any { it.name == name }) return
        list += RidePassEvent(name, routeKm, System.currentTimeMillis())
        val arr = JSONArray()
        list.forEach { e -> arr.put(JSONObject().apply {
            put("name", e.name); put("km", e.routeKm); put("time", e.timestampMs)
        }) }
        prefs.edit().putString(EVENTS, arr.toString()).apply()
    }

    fun events(): List<RidePassEvent> {
        val raw = prefs.getString(EVENTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RidePassEvent(o.getString("name"), o.getDouble("km"), o.getLong("time"))
            }
        } catch (_: Exception) { emptyList() }
    }

    fun reset() {
        prefs.edit().clear().apply()
        ensureStarted()
    }

    fun summaryText(actualStore: BatteryActualStore): String {
        val start = prefs.getLong(START_MS, 0L)
        val last = prefs.getLong(LAST_MS, start)
        val maxKm = prefs.getFloat(MAX_KM, 0f).toDouble()
        val count = prefs.getInt(SPEED_COUNT, 0)
        val avgSpeed = if (count > 0) prefs.getFloat(SPEED_SUM, 0f) / count else 0f
        val durationMin = if (start > 0L) ((last - start).coerceAtLeast(0L) / 60000L) else 0L
        val h = durationMin / 60
        val m = durationMin % 60
        val timeFmt = SimpleDateFormat("HH:mm", Locale.KOREA)
        val lines = mutableListOf<String>()
        lines += "진행 거리: ${RideFormatter.one(maxKm)} km"
        lines += "기록 시간: ${if (h > 0) "${h}시간 ${m}분" else "${m}분"}"
        lines += "GPS 이동 평균: ${if (avgSpeed > 0f) RideFormatter.one(avgSpeed.toDouble()) + " km/h" else "-"}"
        if (start > 0) lines += "시작: ${timeFmt.format(Date(start))}"
        if (events().isNotEmpty()) {
            lines += ""
            lines += "주요 지점 통과"
            events().forEach { lines += "• ${it.name}: ${timeFmt.format(Date(it.timestampMs))}" }
        }
        val actuals = actualStore.entries()
        if (actuals.isNotEmpty()) {
            lines += ""
            lines += "실제 배터리 입력"
            actuals.takeLast(12).forEach { e ->
                lines += "• ${RideFormatter.one(e.routeKm)}km · ${e.percent.toInt()}% · ${timeFmt.format(Date(e.timestampMs))}"
            }
        }
        return lines.joinToString("\n")
    }
}
