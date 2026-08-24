package com.seungjae.jangsu280battery

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class ActualEntryKind {
    RIDING,
    ARRIVAL,
    POST_CHARGE
}

data class ActualBatteryEntry(
    val percent: Double,
    val routeKm: Double,
    val timestampMs: Long,
    val kind: ActualEntryKind
)

class BatteryActualStore(context: Context) {
    companion object {
        private const val PREFS = "actual_battery_state"
        private const val KEY_HISTORY = "history_json"
        private const val MAX_HISTORY = 30
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun entries(): List<ActualBatteryEntry> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val result = ArrayList<ActualBatteryEntry>(array.length())
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val kind = runCatching { ActualEntryKind.valueOf(o.optString("kind", "RIDING")) }
                    .getOrDefault(ActualEntryKind.RIDING)
                result += ActualBatteryEntry(
                    percent = o.getDouble("percent").coerceIn(0.0, 100.0),
                    routeKm = o.getDouble("routeKm").coerceAtLeast(0.0),
                    timestampMs = o.optLong("timestampMs", 0L),
                    kind = kind
                )
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun latest(): ActualBatteryEntry? = entries().lastOrNull()

    fun save(percent: Double, routeKm: Double, kind: ActualEntryKind): ActualBatteryEntry {
        val item = ActualBatteryEntry(
            percent = percent.coerceIn(0.0, 100.0),
            routeKm = routeKm.coerceAtLeast(0.0),
            timestampMs = System.currentTimeMillis(),
            kind = kind
        )
        val list = entries().toMutableList().apply {
            add(item)
            while (size > MAX_HISTORY) removeAt(0)
        }
        write(list)
        return item
    }

    fun undoLast(): ActualBatteryEntry? {
        val list = entries().toMutableList()
        if (list.isEmpty()) return null
        val removed = list.removeAt(list.lastIndex)
        write(list)
        return removed
    }

    fun clear() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    private fun write(list: List<ActualBatteryEntry>) {
        val array = JSONArray()
        list.forEach { e ->
            array.put(JSONObject().apply {
                put("percent", e.percent)
                put("routeKm", e.routeKm)
                put("timestampMs", e.timestampMs)
                put("kind", e.kind.name)
            })
        }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }
}
