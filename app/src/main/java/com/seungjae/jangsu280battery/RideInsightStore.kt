package com.seungjae.jangsu280battery

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Rider/e-MTB insight store.
 *
 * Human fitness metrics use Rider Power only. Motor Power is always kept separate.
 * FIT-only records are allowed because these metrics do not alter SOC/mode battery learning.
 */
data class RideInsightRecord(
    val id: String,
    val fileName: String,
    val rideStartMs: Long,
    val importedAtMs: Long,
    val distanceKm: Double,
    val ascentM: Double,
    val durationSec: Long?,
    val riderWh: Double,
    val motorWh: Double,
    val humanSharePct: Double?,
    val motorSharePct: Double?,
    val motorOutputWhPerKm: Double?,
    val avgRiderPowerW: Double?,
    val avgMotorPowerW: Double?,
    val avgCadenceRpm: Double?,
    val estimatedFtpW: Double?,
    val ftpConfidence: String,
    val powerPeaks: Map<Int, Double>,
    val cadenceWhPerKm: Map<String, Double>
)

class RideInsightStore(context: Context) {
    companion object {
        private const val PREF = "ride_insight_store_v1"
        private const val KEY = "records"
        private const val MAX_RECORDS = 180
        private val WINDOWS = intArrayOf(1, 5, 10, 30, 60, 180, 300, 600, 1200, 2400, 3600)
    }

    private val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun analyzeAndStore(analysis: HistoricalRideAnalysis): RideInsightRecord? {
        if (analysis.sourceType != HistoricalSourceType.FIT || analysis.telemetry.size < 2) return null
        val stats = TelemetryMath.segmentStats(analysis.telemetry, 0.0, analysis.distanceKm)
        val riderWh = stats.riderWh.coerceAtLeast(0.0)
        val motorWh = stats.motorWh.coerceAtLeast(0.0)
        val combined = riderWh + motorWh
        val humanPct = if (combined > 0.1) riderWh / combined * 100.0 else null
        val motorPct = if (combined > 0.1) motorWh / combined * 100.0 else null
        val motorWhKm = if (analysis.distanceKm > 0.1 && motorWh > 0.0) motorWh / analysis.distanceKm else null
        val peaks = powerCurve(analysis.telemetry)
        val ftp = estimateFtp(peaks)
        val rideStart = analysis.telemetry.mapNotNull { it.timestampMs }.minOrNull() ?: analysis.timestampMs
        val record = RideInsightRecord(
            id = analysis.fileHash,
            fileName = analysis.displayName,
            rideStartMs = rideStart,
            importedAtMs = System.currentTimeMillis(),
            distanceKm = analysis.distanceKm,
            ascentM = analysis.ascentM,
            durationSec = analysis.durationSec,
            riderWh = riderWh,
            motorWh = motorWh,
            humanSharePct = humanPct,
            motorSharePct = motorPct,
            motorOutputWhPerKm = motorWhKm,
            avgRiderPowerW = stats.avgRiderPowerW,
            avgMotorPowerW = stats.avgMotorPowerW,
            avgCadenceRpm = stats.avgCadenceRpm,
            estimatedFtpW = ftp.first,
            ftpConfidence = ftp.second,
            powerPeaks = peaks,
            cadenceWhPerKm = cadenceEfficiency(analysis.telemetry)
        )
        val merged = (records().filterNot { it.id == record.id } + record)
            .sortedBy { it.rideStartMs }
            .takeLast(MAX_RECORDS)
        write(merged)
        return record
    }

    fun records(): List<RideInsightRecord> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RideInsightRecord(
                    id = o.optString("id"),
                    fileName = o.optString("fileName", "FIT"),
                    rideStartMs = o.optLong("rideStartMs"),
                    importedAtMs = o.optLong("importedAtMs"),
                    distanceKm = o.optDouble("distanceKm"),
                    ascentM = o.optDouble("ascentM"),
                    durationSec = if (o.isNull("durationSec")) null else o.optLong("durationSec"),
                    riderWh = o.optDouble("riderWh"),
                    motorWh = o.optDouble("motorWh"),
                    humanSharePct = nullable(o, "humanSharePct"),
                    motorSharePct = nullable(o, "motorSharePct"),
                    motorOutputWhPerKm = nullable(o, "motorOutputWhPerKm"),
                    avgRiderPowerW = nullable(o, "avgRiderPowerW"),
                    avgMotorPowerW = nullable(o, "avgMotorPowerW"),
                    avgCadenceRpm = nullable(o, "avgCadenceRpm"),
                    estimatedFtpW = nullable(o, "estimatedFtpW"),
                    ftpConfidence = o.optString("ftpConfidence", "데이터 부족"),
                    powerPeaks = jsonMap(o.optJSONObject("powerPeaks")),
                    cadenceWhPerKm = jsonStringMap(o.optJSONObject("cadenceWhPerKm"))
                )
            }.sortedByDescending { it.rideStartMs }
        } catch (_: Exception) { emptyList() }
    }

    fun clear() = prefs.edit().clear().apply()

    fun allTimePeaks(): Map<Int, Double> = combinePeaks(records())

    fun recent12WeekPeaks(nowMs: Long = System.currentTimeMillis()): Map<Int, Double> {
        val min = nowMs - 84L * 24L * 3600L * 1000L
        return combinePeaks(records().filter { it.rideStartMs >= min })
    }

    fun estimatedFtp(): Pair<Double?, String> {
        val peaks = recent12WeekPeaks().ifEmpty { allTimePeaks() }
        return estimateFtp(peaks)
    }

    fun summaryText(): String {
        val r = records()
        if (r.isEmpty()) return "라이더 분석 데이터 없음 · FIT을 보조학습하면 자동 누적"
        val ftp = estimatedFtp()
        val peaks = recent12WeekPeaks().ifEmpty { allTimePeaks() }
        val p5m = peaks[300]?.roundToInt()
        val p20m = peaks[1200]?.roundToInt()
        return buildString {
            append("FIT 분석 ${r.size}개")
            if (ftp.first != null) append(" · 추정 FTP ${ftp.first!!.roundToInt()}W (${ftp.second})")
            if (p5m != null || p20m != null) {
                append("\n")
                if (p5m != null) append("5분 ${p5m}W")
                if (p5m != null && p20m != null) append(" · ")
                if (p20m != null) append("20분 ${p20m}W")
                append(" · 최근12주 우선")
            }
        }
    }

    fun recordSummary(record: RideInsightRecord): String = buildString {
        append("${record.fileName}\n")
        append("거리 ${one(record.distanceKm)}km · 상승 ${record.ascentM.roundToInt()}m")
        record.humanSharePct?.let { h ->
            append("\n🧍 사람 ${one(h)}% · ⚡ 모터 ${one(record.motorSharePct ?: (100.0 - h))}%")
        }
        append("\nRider ${record.riderWh.roundToInt()}Wh (${(record.riderWh * 3.6).roundToInt()}kJ)")
        append(" · Motor ${record.motorWh.roundToInt()}Wh")
        record.motorOutputWhPerKm?.let { append(" · ${one(it)} Wh/km") }
        record.avgCadenceRpm?.let { append("\n평균 케이던스 ${it.roundToInt()}rpm") }
        record.estimatedFtpW?.let { append(" · 추정 FTP ${it.roundToInt()}W (${record.ftpConfidence})") }
        val short = listOf(5 to "5초", 60 to "1분", 300 to "5분", 1200 to "20분", 2400 to "40분")
            .mapNotNull { (s, label) -> record.powerPeaks[s]?.let { "$label ${it.roundToInt()}W" } }
        if (short.isNotEmpty()) append("\n파워커브 · ${short.joinToString(" · ")}")
        bestCadence(record.cadenceWhPerKm)?.let { (bin, whkm) ->
            append("\n페달링 효율 참고 · $bin 구간 Motor ${one(whkm)} Wh/km")
            append(" (지형 혼합값)")
        }
    }

    private fun powerCurve(points: List<HistoricalTelemetryPoint>): Map<Int, Double> {
        val bySec = points.mapNotNull { p ->
            val t = p.timestampMs ?: return@mapNotNull null
            val w = p.riderPowerW?.takeIf { it.isFinite() && it in 0.0..2500.0 } ?: return@mapNotNull null
            (t / 1000L) to w
        }.groupBy({ it.first }, { it.second }).mapValues { it.value.average() }.toSortedMap()
        if (bySec.isEmpty()) return emptyMap()
        val secs = bySec.keys.toList()
        val watts = secs.map { bySec[it] ?: 0.0 }
        val out = linkedMapOf<Int, Double>()
        WINDOWS.forEach { window ->
            if (window == 1) {
                watts.maxOrNull()?.let { out[1] = it }
                return@forEach
            }
            var best = 0.0
            var sum = 0.0
            var left = 0
            var segmentStart = 0
            for (right in secs.indices) {
                if (right > 0 && secs[right] - secs[right - 1] > 2L) {
                    sum = 0.0
                    left = right
                    segmentStart = right
                }
                sum += watts[right]
                while (left <= right && secs[right] - secs[left] >= window) {
                    sum -= watts[left]
                    left++
                }
                val count = right - left + 1
                val span = secs[right] - secs[left] + 1L
                if (left >= segmentStart && span >= window * 0.90 && count >= (window * 0.85).roundToInt()) {
                    best = max(best, sum / count.coerceAtLeast(1))
                }
            }
            if (best > 0.0) out[window] = best
        }
        return out
    }

    private fun estimateFtp(peaks: Map<Int, Double>): Pair<Double?, String> {
        val p20 = peaks[1200]
        val p40 = peaks[2400]
        val p60 = peaks[3600]
        if (p20 == null) return null to "20분 데이터 부족"
        val candidates = mutableListOf(p20 * 0.95)
        if (p40 != null) candidates += p40 * 0.99
        if (p60 != null) candidates += p60
        val value = candidates.average().coerceAtLeast(0.0)
        val confidence = when {
            p60 != null -> "높음"
            p40 != null -> "중상"
            else -> "중"
        }
        return value to confidence
    }

    private fun cadenceEfficiency(points: List<HistoricalTelemetryPoint>): Map<String, Double> {
        data class Acc(var km: Double = 0.0, var motorWh: Double = 0.0)
        val acc = linkedMapOf<String, Acc>()
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val ta = a.timestampMs ?: continue
            val tb = b.timestampMs ?: continue
            val dt = (tb - ta) / 1000.0
            if (dt !in 0.05..5.0) continue
            val km = (b.routeKm - a.routeKm).takeIf { it in 0.0..0.20 } ?: continue
            if (km <= 0.0005) continue
            val cad = listOfNotNull(a.cadenceRpm, b.cadenceRpm).averageOrNull() ?: continue
            val motor = listOfNotNull(a.motorPowerW, b.motorPowerW).averageOrNull()?.coerceAtLeast(0.0) ?: continue
            val label = cadenceBin(cad) ?: continue
            val x = acc.getOrPut(label) { Acc() }
            x.km += km
            x.motorWh += motor * dt / 3600.0
        }
        return acc.mapNotNull { (label, a) ->
            if (a.km < 0.8 || a.motorWh <= 0.0) null else label to (a.motorWh / a.km)
        }.toMap()
    }

    private fun cadenceBin(rpm: Double): String? = when {
        rpm < 35.0 || rpm > 130.0 -> null
        rpm < 50.0 -> "35~49rpm"
        rpm < 60.0 -> "50~59rpm"
        rpm < 70.0 -> "60~69rpm"
        rpm < 80.0 -> "70~79rpm"
        rpm < 90.0 -> "80~89rpm"
        rpm < 100.0 -> "90~99rpm"
        else -> "100rpm+"
    }

    private fun bestCadence(values: Map<String, Double>): Pair<String, Double>? {
        val e = values.minByOrNull { entry -> entry.value } ?: return null
        return e.key to e.value
    }

    private fun combinePeaks(records: List<RideInsightRecord>): Map<Int, Double> {
        val out = linkedMapOf<Int, Double>()
        records.forEach { r -> r.powerPeaks.forEach { (k, v) -> if (v > (out[k] ?: 0.0)) out[k] = v } }
        return out
    }

    private fun write(items: List<RideInsightRecord>) {
        val arr = JSONArray()
        items.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id); put("fileName", r.fileName); put("rideStartMs", r.rideStartMs); put("importedAtMs", r.importedAtMs)
                put("distanceKm", r.distanceKm); put("ascentM", r.ascentM)
                if (r.durationSec == null) put("durationSec", JSONObject.NULL) else put("durationSec", r.durationSec)
                put("riderWh", r.riderWh); put("motorWh", r.motorWh)
                nput(this, "humanSharePct", r.humanSharePct); nput(this, "motorSharePct", r.motorSharePct)
                nput(this, "motorOutputWhPerKm", r.motorOutputWhPerKm); nput(this, "avgRiderPowerW", r.avgRiderPowerW)
                nput(this, "avgMotorPowerW", r.avgMotorPowerW); nput(this, "avgCadenceRpm", r.avgCadenceRpm)
                nput(this, "estimatedFtpW", r.estimatedFtpW); put("ftpConfidence", r.ftpConfidence)
                put("powerPeaks", JSONObject().apply { r.powerPeaks.forEach { (k, v) -> put(k.toString(), v) } })
                put("cadenceWhPerKm", JSONObject().apply { r.cadenceWhPerKm.forEach { (k, v) -> put(k, v) } })
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun nullable(o: JSONObject, key: String): Double? = if (!o.has(key) || o.isNull(key)) null else o.optDouble(key).takeIf { it.isFinite() }
    private fun nput(o: JSONObject, key: String, v: Double?) { if (v == null || !v.isFinite()) o.put(key, JSONObject.NULL) else o.put(key, v) }
    private fun jsonMap(o: JSONObject?): Map<Int, Double> {
        if (o == null) return emptyMap()
        val out = linkedMapOf<Int, Double>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val number = key.toIntOrNull() ?: continue
            out[number] = o.optDouble(key)
        }
        return out
    }
    private fun jsonStringMap(o: JSONObject?): Map<String, Double> {
        if (o == null) return emptyMap()
        val out = linkedMapOf<String, Double>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = o.optDouble(key)
        }
        return out
    }
    private fun one(v: Double): String = String.format(Locale.US, "%.1f", v)
    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
}
