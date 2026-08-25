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

data class ActualSaveResult(
    val saved: ActualBatteryEntry,
    val replaced: ActualBatteryEntry?
)

class BatteryActualStore(context: Context) {
    companion object {
        private const val PREFS = "actual_battery_state"
        private const val KEY_HISTORY = "history_json"
        private const val MAX_HISTORY = 200
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

    fun save(percent: Double, routeKm: Double, kind: ActualEntryKind, timestampMs: Long = System.currentTimeMillis()): ActualBatteryEntry {
        val item = ActualBatteryEntry(
            percent = percent.coerceIn(0.0, 100.0),
            routeKm = routeKm.coerceAtLeast(0.0),
            timestampMs = timestampMs,
            kind = kind
        )
        val list = entries().toMutableList().apply {
            add(item)
            while (size > MAX_HISTORY) removeAt(0)
        }
        write(list)
        return item
    }

    /**
     * 일반 주행 중 배터리 재입력 안전장치.
     * 직전 RIDING 입력 후 10초 이내라면 직전 값은 무효화하고 새 값/새 위치/새 시간을 저장한다.
     * 충전 ARRIVAL/POST_CHARGE 이벤트에는 적용하지 않는다.
     */
    fun saveRidingReplacingRecent(
        percent: Double,
        routeKm: Double,
        timestampMs: Long = System.currentTimeMillis(),
        replaceWindowMs: Long = 10_000L
    ): ActualSaveResult {
        val list = entries().toMutableList()
        val previous = list.lastOrNull()
        val replace = previous?.takeIf {
            it.kind == ActualEntryKind.RIDING && timestampMs >= it.timestampMs && timestampMs - it.timestampMs <= replaceWindowMs
        }
        if (replace != null) list.removeAt(list.lastIndex)
        val item = ActualBatteryEntry(
            percent = percent.coerceIn(0.0, 100.0),
            routeKm = routeKm.coerceAtLeast(0.0),
            timestampMs = timestampMs,
            kind = ActualEntryKind.RIDING
        )
        list.add(item)
        while (list.size > MAX_HISTORY) list.removeAt(0)
        write(list)
        return ActualSaveResult(item, replace)
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
