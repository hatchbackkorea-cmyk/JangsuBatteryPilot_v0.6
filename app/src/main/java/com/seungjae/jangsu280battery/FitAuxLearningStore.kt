package com.seungjae.jangsu280battery

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * B급 보조학습 저장소.
 * FIT 단독 파일에는 신뢰할 수 있는 BLE SOC/선택 모드 정답이 없을 수 있으므로
 * 배터리 소비 factor/pctPerKm는 절대 학습하지 않는다.
 * 거리·고도·속도·Rider/Motor Power·Cadence의 지형별 관계만 보존하고,
 * 정식 A급(FIT+ZIP) 파워 프로필이 없을 때에만 보조 프로필로 사용한다.
 */
data class FitAuxLearningSample(
    val bucket: TerrainBucket,
    val distanceKm: Double,
    val ascentM: Double,
    val descentM: Double,
    val riderWh: Double,
    val motorWh: Double,
    val avgSpeedKph: Double?,
    val avgRiderPowerW: Double?,
    val avgMotorPowerW: Double?,
    val avgActiveMotorPowerW: Double?,
    val avgCadenceRpm: Double?,
    val motorActiveRatio: Double,
    val qualityScore: Int,
    val sessionId: String,
    val timestampMs: Long
)

data class FitAuxRideRecord(
    val id: String,
    val fileHash: String,
    val fileName: String,
    val importedAtMs: Long,
    val distanceKm: Double,
    val ascentM: Double,
    val descentM: Double,
    val durationSec: Long?,
    val sampleCount: Int,
    val telemetryPointCount: Int,
    val dataQualityScore: Int
)

class FitAuxLearningStore(context: Context) {
    companion object {
        private const val PREFS = "fit_aux_learning_v1"
        private const val KEY_SAMPLES = "samples"
        private const val KEY_RECORDS = "records"
        private const val MAX_SAMPLES = 1200
        private const val MAX_RECORDS = 160
        private const val BLOCK_KM = 1.0
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun trainFit(analysis: HistoricalRideAnalysis): Int {
        if (analysis.sourceType != HistoricalSourceType.FIT) return 0
        if (analysis.fileHash.isBlank() || analysis.distanceKm < 0.5 || analysis.telemetry.size < 20) return 0

        val sessionId = "fitaux_${analysis.fileHash.take(32)}"
        removeSession(sessionId)
        val added = mutableListOf<FitAuxLearningSample>()
        var from = 0.0
        while (from < analysis.distanceKm - 0.05) {
            val to = (from + BLOCK_KM).coerceAtMost(analysis.distanceKm)
            val dist = to - from
            if (dist >= 0.30) {
                val elevation = analysis.course.elevationBetween(from, to)
                val stats = TelemetryMath.segmentStats(analysis.telemetry, from, to)
                val hasUsefulTelemetry = stats.avgSpeedKph != null || stats.avgRiderPowerW != null ||
                    stats.avgMotorPowerW != null || stats.avgCadenceRpm != null
                if (hasUsefulTelemetry) {
                    added += FitAuxLearningSample(
                        bucket = bucket(dist, elevation.ascentM),
                        distanceKm = dist,
                        ascentM = elevation.ascentM,
                        descentM = elevation.descentM,
                        riderWh = stats.riderWh,
                        motorWh = stats.motorWh,
                        avgSpeedKph = stats.avgSpeedKph,
                        avgRiderPowerW = stats.avgRiderPowerW,
                        avgMotorPowerW = stats.avgMotorPowerW,
                        avgActiveMotorPowerW = stats.avgActiveMotorPowerW,
                        avgCadenceRpm = stats.avgCadenceRpm,
                        motorActiveRatio = stats.motorActiveRatio,
                        qualityScore = analysis.dataQualityScore.coerceIn(0, 100),
                        sessionId = sessionId,
                        timestampMs = analysis.timestampMs
                    )
                }
            }
            from = to
        }
        if (added.isEmpty()) return 0

        writeSamples((samples() + added).takeLast(MAX_SAMPLES))
        val record = FitAuxRideRecord(
            id = sessionId,
            fileHash = analysis.fileHash,
            fileName = analysis.displayName,
            importedAtMs = System.currentTimeMillis(),
            distanceKm = analysis.distanceKm,
            ascentM = analysis.ascentM,
            descentM = analysis.descentM,
            durationSec = analysis.durationSec,
            sampleCount = added.size,
            telemetryPointCount = analysis.telemetry.size,
            dataQualityScore = analysis.dataQualityScore.coerceIn(0, 100)
        )
        val merged = (records().filterNot { it.id == sessionId || it.fileHash == analysis.fileHash } + record)
            .sortedBy { it.importedAtMs }
            .takeLast(MAX_RECORDS)
        writeRecords(merged)
        return added.size
    }

    /** A급 모드별 파워 프로필이 없을 때만 쓰는 모드 미지정 보조 프로필. */
    fun profile(bucket: TerrainBucket): LearnedAssistProfile? {
        val subset = samples().filter { it.bucket == bucket && it.qualityScore >= 25 }.takeLast(80)
        if (subset.isEmpty()) return null

        fun weighted(selector: (FitAuxLearningSample) -> Double?): Double? {
            var sum = 0.0
            var weight = 0.0
            subset.forEachIndexed { index, s ->
                val value = selector(s) ?: return@forEachIndexed
                if (!value.isFinite()) return@forEachIndexed
                val recency = 0.8 + (index + 1).toDouble() / subset.size.coerceAtLeast(1)
                val distanceWeight = s.distanceKm.coerceIn(0.3, 2.0)
                val qualityWeight = (s.qualityScore.coerceIn(0, 100) / 100.0).coerceAtLeast(0.20)
                val w = recency * distanceWeight * qualityWeight
                sum += value * w
                weight += w
            }
            return if (weight > 0.0) sum / weight else null
        }

        // B급 데이터는 A급보다 영향력이 낮다는 의미로 품질 상한을 55로 둔다.
        val quality = subset.map { it.qualityScore }.average().roundToInt().coerceIn(0, 55)
        return LearnedAssistProfile(
            bucket = bucket,
            sampleCount = subset.size,
            avgSpeedKph = weighted { it.avgSpeedKph }?.takeIf { it in 3.0..60.0 },
            avgMotorPowerW = weighted { it.avgActiveMotorPowerW ?: it.avgMotorPowerW }?.takeIf { it in 0.0..1500.0 },
            avgRiderPowerW = weighted { it.avgRiderPowerW }?.takeIf { it in 0.0..1500.0 },
            avgCadenceRpm = weighted { it.avgCadenceRpm }?.takeIf { it in 20.0..180.0 },
            quality = quality
        )
    }

    fun samples(): List<FitAuxLearningSample> {
        val raw = prefs.getString(KEY_SAMPLES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                FitAuxLearningSample(
                    bucket = runCatching { TerrainBucket.valueOf(o.optString("bucket")) }.getOrDefault(TerrainBucket.ROLLING),
                    distanceKm = o.optDouble("distanceKm", 0.0),
                    ascentM = o.optDouble("ascentM", 0.0),
                    descentM = o.optDouble("descentM", 0.0),
                    riderWh = o.optDouble("riderWh", 0.0),
                    motorWh = o.optDouble("motorWh", 0.0),
                    avgSpeedKph = nullableDouble(o, "avgSpeedKph"),
                    avgRiderPowerW = nullableDouble(o, "avgRiderPowerW"),
                    avgMotorPowerW = nullableDouble(o, "avgMotorPowerW"),
                    avgActiveMotorPowerW = nullableDouble(o, "avgActiveMotorPowerW"),
                    avgCadenceRpm = nullableDouble(o, "avgCadenceRpm"),
                    motorActiveRatio = o.optDouble("motorActiveRatio", 0.0).coerceIn(0.0, 1.0),
                    qualityScore = o.optInt("qualityScore", 0).coerceIn(0, 100),
                    sessionId = o.optString("sessionId", ""),
                    timestampMs = o.optLong("timestampMs", 0L)
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun records(): List<FitAuxRideRecord> {
        val raw = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                FitAuxRideRecord(
                    id = o.optString("id", ""),
                    fileHash = o.optString("fileHash", ""),
                    fileName = o.optString("fileName", "FIT"),
                    importedAtMs = o.optLong("importedAtMs", 0L),
                    distanceKm = o.optDouble("distanceKm", 0.0),
                    ascentM = o.optDouble("ascentM", 0.0),
                    descentM = o.optDouble("descentM", 0.0),
                    durationSec = if (o.has("durationSec") && !o.isNull("durationSec")) o.optLong("durationSec") else null,
                    sampleCount = o.optInt("sampleCount", 0),
                    telemetryPointCount = o.optInt("telemetryPointCount", 0),
                    dataQualityScore = o.optInt("dataQualityScore", 0).coerceIn(0, 100)
                )
            }.sortedByDescending { it.importedAtMs }
        } catch (_: Exception) { emptyList() }
    }

    fun removeSession(sessionId: String): Int {
        if (sessionId.isBlank()) return 0
        val before = samples()
        val after = before.filterNot { it.sessionId == sessionId }
        if (before.size != after.size) writeSamples(after)
        val rec = records().filterNot { it.id == sessionId }
        writeRecords(rec)
        return before.size - after.size
    }

    fun clear() = prefs.edit().clear().apply()

    fun summaryText(): String {
        val rec = records()
        val s = samples()
        if (rec.isEmpty()) return "FIT 단독 보조학습 없음"
        val terrain = TerrainBucket.values().mapNotNull { b ->
            val count = s.count { it.bucket == b }
            if (count == 0) null else "${b.label} ${count}"
        }.joinToString(" · ")
        return "B급 FIT ${rec.size}개 · 보조구간 ${s.size}개${if (terrain.isNotBlank()) "\n$terrain" else ""}"
    }

    private fun bucket(distanceKm: Double, ascentM: Double): TerrainBucket {
        if (distanceKm <= 0.05) return TerrainBucket.FLAT
        val ascentPerKm = ascentM / distanceKm
        return when {
            ascentPerKm < 12.0 -> TerrainBucket.FLAT
            ascentPerKm < 35.0 -> TerrainBucket.ROLLING
            else -> TerrainBucket.CLIMB
        }
    }

    private fun writeSamples(items: List<FitAuxLearningSample>) {
        val arr = JSONArray()
        items.forEach { s ->
            arr.put(JSONObject().apply {
                put("bucket", s.bucket.name)
                put("distanceKm", s.distanceKm)
                put("ascentM", s.ascentM)
                put("descentM", s.descentM)
                put("riderWh", s.riderWh)
                put("motorWh", s.motorWh)
                nullablePut(this, "avgSpeedKph", s.avgSpeedKph)
                nullablePut(this, "avgRiderPowerW", s.avgRiderPowerW)
                nullablePut(this, "avgMotorPowerW", s.avgMotorPowerW)
                nullablePut(this, "avgActiveMotorPowerW", s.avgActiveMotorPowerW)
                nullablePut(this, "avgCadenceRpm", s.avgCadenceRpm)
                put("motorActiveRatio", s.motorActiveRatio)
                put("qualityScore", s.qualityScore)
                put("sessionId", s.sessionId)
                put("timestampMs", s.timestampMs)
            })
        }
        prefs.edit().putString(KEY_SAMPLES, arr.toString()).apply()
    }

    private fun writeRecords(items: List<FitAuxRideRecord>) {
        val arr = JSONArray()
        items.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("fileHash", r.fileHash)
                put("fileName", r.fileName)
                put("importedAtMs", r.importedAtMs)
                put("distanceKm", r.distanceKm)
                put("ascentM", r.ascentM)
                put("descentM", r.descentM)
                if (r.durationSec == null) put("durationSec", JSONObject.NULL) else put("durationSec", r.durationSec)
                put("sampleCount", r.sampleCount)
                put("telemetryPointCount", r.telemetryPointCount)
                put("dataQualityScore", r.dataQualityScore)
            })
        }
        prefs.edit().putString(KEY_RECORDS, arr.toString()).apply()
    }

    private fun nullableDouble(o: JSONObject, key: String): Double? =
        if (!o.has(key) || o.isNull(key)) null else o.optDouble(key).takeIf { !it.isNaN() }

    private fun nullablePut(o: JSONObject, key: String, value: Double?) {
        if (value == null || !value.isFinite()) o.put(key, JSONObject.NULL) else o.put(key, value)
    }
}
