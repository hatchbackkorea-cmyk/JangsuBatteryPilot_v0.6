package com.seungjae.jangsu280battery

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject


data class HistoricalRideRecord(
    val id: String,
    val fileHash: String,
    val fileName: String,
    val sourceType: HistoricalSourceType,
    val importedAtMs: Long,
    val distanceKm: Double,
    val ascentM: Double,
    val descentM: Double,
    val durationSec: Long?,
    val usedBatteryPct: Double,
    val avgSpeedKph: Double?,
    val sampleCount: Int
)

class HistoricalRideStore(context: Context) {
    companion object {
        private const val PREFS = "historical_ride_learning_v1"
        private const val KEY_RECORDS = "records"
        private const val MAX_RECORDS = 80
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun records(): List<HistoricalRideRecord> {
        val raw = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HistoricalRideRecord(
                    id = o.getString("id"),
                    fileHash = o.optString("fileHash", ""),
                    fileName = o.optString("fileName", "과거 라이딩"),
                    sourceType = runCatching { HistoricalSourceType.valueOf(o.optString("sourceType")) }.getOrDefault(HistoricalSourceType.GPX),
                    importedAtMs = o.optLong("importedAtMs", 0L),
                    distanceKm = o.optDouble("distanceKm", 0.0),
                    ascentM = o.optDouble("ascentM", 0.0),
                    descentM = o.optDouble("descentM", 0.0),
                    durationSec = if (o.has("durationSec") && !o.isNull("durationSec")) o.optLong("durationSec") else null,
                    usedBatteryPct = o.optDouble("usedBatteryPct", 0.0),
                    avgSpeedKph = nullableDouble(o, "avgSpeedKph"),
                    sampleCount = o.optInt("sampleCount", 0)
                )
            }.sortedByDescending { it.importedAtMs }
        } catch (_: Exception) { emptyList() }
    }

    fun findByHash(hash: String): HistoricalRideRecord? = records().firstOrNull { it.fileHash == hash && hash.isNotBlank() }

    fun add(record: HistoricalRideRecord) {
        val merged = (records().filterNot { it.id == record.id || (record.fileHash.isNotBlank() && it.fileHash == record.fileHash) } + record)
            .sortedBy { it.importedAtMs }
            .takeLast(MAX_RECORDS)
        write(merged)
    }

    fun remove(id: String): HistoricalRideRecord? {
        val all = records()
        val target = all.firstOrNull { it.id == id } ?: return null
        write(all.filterNot { it.id == id })
        return target
    }

    fun clear() = prefs.edit().clear().apply()

    private fun write(items: List<HistoricalRideRecord>) {
        val arr = JSONArray()
        items.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("fileHash", r.fileHash)
                put("fileName", r.fileName)
                put("sourceType", r.sourceType.name)
                put("importedAtMs", r.importedAtMs)
                put("distanceKm", r.distanceKm)
                put("ascentM", r.ascentM)
                put("descentM", r.descentM)
                if (r.durationSec == null) put("durationSec", JSONObject.NULL) else put("durationSec", r.durationSec)
                put("usedBatteryPct", r.usedBatteryPct)
                putNullable("avgSpeedKph", r.avgSpeedKph)
                put("sampleCount", r.sampleCount)
            })
        }
        prefs.edit().putString(KEY_RECORDS, arr.toString()).apply()
    }

    private fun JSONObject.putNullable(key: String, value: Double?) {
        if (value == null) put(key, JSONObject.NULL) else put(key, value)
    }

    private fun nullableDouble(o: JSONObject, key: String): Double? =
        if (!o.has(key) || o.isNull(key)) null else o.optDouble(key).takeIf { !it.isNaN() }
}
