package com.seungjae.jangsu280battery

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

enum class TerrainBucket(val label: String) { FLAT("평지/완만"), ROLLING("구릉"), CLIMB("업힐") }

data class BatteryLearningSample(
    val bucket: TerrainBucket,
    val factor: Double,
    val pctPerKm: Double,
    val distanceKm: Double,
    val ascentM: Double,
    val timestampMs: Long,
    val sessionId: String
)

class BatteryLearningStore(context: Context) {
    companion object {
        private const val PREFS = "battery_learning_v1"
        private const val KEY_SAMPLES = "samples"
        private const val KEY_TRAINED = "trained_sessions"
        private const val MAX_SAMPLES = 60

        const val FLAT_PCT_PER_KM = 0.65
        const val ASCENT_PCT_PER_M = 0.035
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun baseConsumption(distanceKm: Double, ascentM: Double): Double =
        (distanceKm.coerceAtLeast(0.0) * FLAT_PCT_PER_KM + ascentM.coerceAtLeast(0.0) * ASCENT_PCT_PER_M).coerceAtLeast(0.0)

    fun bucket(distanceKm: Double, ascentM: Double): TerrainBucket {
        if (distanceKm <= 0.05) return TerrainBucket.FLAT
        val ascentPerKm = ascentM / distanceKm
        return when {
            ascentPerKm < 12.0 -> TerrainBucket.FLAT
            ascentPerKm < 35.0 -> TerrainBucket.ROLLING
            else -> TerrainBucket.CLIMB
        }
    }

    fun learnedFactor(bucket: TerrainBucket): Double {
        val specific = samples().filter { it.bucket == bucket }.takeLast(12)
        if (specific.isNotEmpty()) return weightedFactor(specific)
        val all = samples().takeLast(15)
        return if (all.isNotEmpty()) weightedFactor(all) else 1.0
    }

    fun estimateConsumption(course: CourseData, fromKm: Double, toKm: Double): Double {
        val start = fromKm.coerceIn(0.0, course.totalKm)
        val end = toKm.coerceIn(start, course.totalKm)
        if (end <= start) return 0.0
        var x = start
        var total = 0.0
        val stepKm = 0.5
        while (x < end - 0.0001) {
            val nx = (x + stepKm).coerceAtMost(end)
            val dist = nx - x
            val ascent = course.elevationBetween(x, nx).ascentM
            val base = baseConsumption(dist, ascent)
            total += base * learnedFactor(bucket(dist, ascent))
            x = nx
        }
        return total
    }

    fun trainFromRide(sessionId: String, course: CourseData, entries: List<ActualBatteryEntry>): Int {
        if (sessionId.isBlank() || isTrained(sessionId)) return 0
        val ordered = entries.sortedBy { it.timestampMs }
        val newSamples = mutableListOf<BatteryLearningSample>()
        for (i in 1 until ordered.size) {
            val a = ordered[i - 1]
            val b = ordered[i]
            val dist = b.routeKm - a.routeKm
            val used = a.percent - b.percent
            if (dist < 0.7 || used < 0.8) continue
            // 충전 직전→충전 후처럼 배터리가 증가한 쌍은 used가 음수라 이미 제외된다.
            val ascent = course.elevationBetween(a.routeKm, b.routeKm).ascentM
            val modeled = baseConsumption(dist, ascent)
            if (modeled < 0.5) continue
            val factor = (used / modeled).coerceIn(0.45, 2.20)
            newSamples += BatteryLearningSample(
                bucket = bucket(dist, ascent),
                factor = factor,
                pctPerKm = used / dist,
                distanceKm = dist,
                ascentM = ascent,
                timestampMs = b.timestampMs.takeIf { it > 0 } ?: System.currentTimeMillis(),
                sessionId = sessionId
            )
        }
        if (newSamples.isNotEmpty()) {
            val merged = (samples() + newSamples).takeLast(MAX_SAMPLES)
            writeSamples(merged)
        }
        markTrained(sessionId)
        return newSamples.size
    }

    fun samples(): List<BatteryLearningSample> {
        val raw = prefs.getString(KEY_SAMPLES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                BatteryLearningSample(
                    bucket = runCatching { TerrainBucket.valueOf(o.optString("bucket")) }.getOrDefault(TerrainBucket.ROLLING),
                    factor = o.optDouble("factor", 1.0),
                    pctPerKm = o.optDouble("pctPerKm", 0.0),
                    distanceKm = o.optDouble("distanceKm", 0.0),
                    ascentM = o.optDouble("ascentM", 0.0),
                    timestampMs = o.optLong("timestampMs", 0L),
                    sessionId = o.optString("sessionId", "")
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun summaryText(): String {
        val s = samples()
        if (s.isEmpty()) return "개인 소비 학습 없음 · 장수 실측 기반 기본 모델 사용"
        val lines = mutableListOf("개인 소비 학습 ${s.size}개 구간")
        TerrainBucket.values().forEach { b ->
            val subset = s.filter { it.bucket == b }.takeLast(12)
            if (subset.isNotEmpty()) {
                val factor = weightedFactor(subset)
                val ppk = subset.map { it.pctPerKm }.average()
                lines += "${b.label}: ${String.format(java.util.Locale.US, "%.2f", ppk)}%/km · 기본모델×${String.format(java.util.Locale.US, "%.2f", factor)} (${subset.size}회)"
            }
        }
        return lines.joinToString("\n")
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun weightedFactor(items: List<BatteryLearningSample>): Double {
        var sum = 0.0
        var weight = 0.0
        items.forEachIndexed { index, s ->
            val recency = 1.0 + index.toDouble() / items.size.coerceAtLeast(1)
            val distanceWeight = s.distanceKm.coerceIn(1.0, 20.0).let { 0.5 + it / 20.0 }
            val w = recency * distanceWeight
            sum += s.factor * w
            weight += w
        }
        return if (weight > 0) (sum / weight).coerceIn(0.55, 1.80) else 1.0
    }

    private fun writeSamples(items: List<BatteryLearningSample>) {
        val arr = JSONArray()
        items.forEach { s -> arr.put(JSONObject().apply {
            put("bucket", s.bucket.name); put("factor", s.factor); put("pctPerKm", s.pctPerKm)
            put("distanceKm", s.distanceKm); put("ascentM", s.ascentM); put("timestampMs", s.timestampMs); put("sessionId", s.sessionId)
        }) }
        prefs.edit().putString(KEY_SAMPLES, arr.toString()).apply()
    }

    private fun trainedSessions(): MutableSet<String> = prefs.getStringSet(KEY_TRAINED, emptySet())?.toMutableSet() ?: mutableSetOf()
    private fun isTrained(id: String): Boolean = trainedSessions().contains(id)
    private fun markTrained(id: String) {
        val set = trainedSessions().apply {
            add(id)
            while (size > 50) remove(first())
        }
        prefs.edit().putStringSet(KEY_TRAINED, set).apply()
    }
}
