package com.seungjae.jangsu280battery

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * v0.28.9 context model (v2).
 *
 * This is deliberately separated from the long-lived BatteryLearningStore v1 data.
 * Avinox original proto files are re-read and converted into SOC-boundary segments
 * containing terrain + speed + rider power + cadence + actual assist mode.
 *
 * The model is currently used by the page-2 mode strategy simulator only. The live
 * ride SOC prediction core stays on the proven v1 path until the v2 model has enough
 * field validation.
 */
data class ContextualBatterySample(
    val sessionId: String,
    val mode: AvinoxAssistMode,
    val bucket: TerrainBucket,
    val gradePct: Double,
    val pctPerKm: Double,
    val distanceKm: Double,
    val ascentM: Double,
    val avgSpeedKph: Double?,
    val avgRiderPowerW: Double?,
    val avgMotorPowerW: Double?,
    val avgCadenceRpm: Double?,
    val motorActiveRatio: Double,
    val timestampMs: Long,
    val qualityScore: Int
)

data class ContextualModeEstimate(
    val pctPerKm: Double,
    val speedKph: Double?,
    val confidence: Int,
    val matchCount: Int,
    val sessionCount: Int,
    val normalizedContext: Boolean
)

class ContextualBatteryLearningStore(context: Context) {
    companion object {
        private const val PREFS = "battery_context_learning_v2"
        private const val KEY_REV = "revision"
        private const val KEY_TRAINED = "trained_sessions"
        private const val MODEL_FILE = "battery_context_learning_v2.json"
        private const val MAX_SAMPLES = 8000

        @Volatile private var cachedRevision = Int.MIN_VALUE
        @Volatile private var cachedSamples: List<ContextualBatterySample>? = null
        private val lock = Any()
    }

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val file = File(app.filesDir, MODEL_FILE)
    private val estimateCache = HashMap<String, ContextualModeEstimate?>()
    private var estimateCacheRevision = Int.MIN_VALUE

    fun sampleCount(): Int = samples().size

    fun sampleCount(mode: AvinoxAssistMode): Int = samples().count { it.mode == mode && it.qualityScore >= 45 }

    fun trainedSessionCount(): Int = trainedSessions().size

    fun clear() {
        runCatching { file.delete() }
        prefs.edit().clear().apply()
        synchronized(lock) {
            cachedRevision = 0
            cachedSamples = emptyList()
        }
        synchronized(estimateCache) {
            estimateCache.clear()
            estimateCacheRevision = 0
        }
    }

    fun hasSession(sessionId: String): Boolean = trainedSessions().contains(sessionId)

    /**
     * Reinterpret one Avinox original ride into context-aware SOC segments.
     * Only intervals fully contained inside one stable assist-mode window are accepted.
     */
    fun trainRide(sessionId: String, ride: AvinoxProtoRide): Int {
        if (sessionId.isBlank() || hasSession(sessionId)) return 0
        val course = ride.course()
        val telemetry = ride.telemetry()
        val entries = ride.batteryEntries().sortedBy { it.timestampMs }
        val windows = ride.assistWindows().sortedBy { it.startMs }
        if (entries.size < 2 || windows.isEmpty()) {
            markTrained(sessionId)
            return 0
        }

        fun stableWindow(aMs: Long, bMs: Long): AssistModeWindow? {
            if (aMs <= 0L || bMs <= aMs) return null
            return windows.firstOrNull { aMs >= it.startMs && bMs <= it.endMs }
        }

        val out = mutableListOf<ContextualBatterySample>()
        for (i in 1 until entries.size) {
            val a = entries[i - 1]
            val b = entries[i]
            val used = a.percent - b.percent
            // BMS full-charge plateau is intentionally excluded from both v1 and v2.
            if (a.percent > 98.5) continue
            if (used !in 0.8..2.2) continue
            val mode = stableWindow(a.timestampMs, b.timestampMs)?.mode ?: continue
            val dist = b.routeKm - a.routeKm
            if (dist !in 0.25..20.0) continue
            val durationSec = (b.timestampMs - a.timestampMs) / 1000.0
            if (durationSec !in 10.0..3600.0) continue

            val elev = course.elevationBetween(a.routeKm, b.routeKm)
            val startEle = course.pointAtKm(a.routeKm).ele
            val endEle = course.pointAtKm(b.routeKm).ele
            val grade = ((endEle - startEle) / (dist * 1000.0) * 100.0).coerceIn(-30.0, 35.0)
            val stats = TelemetryMath.segmentStats(telemetry, a.routeKm, b.routeKm)
            val ppk = used / dist
            if (!ppk.isFinite() || ppk !in 0.03..25.0) continue

            var quality = ride.qualityScore.coerceIn(0, 100)
            if (stats.avgSpeedKph == null) quality -= 10
            if (stats.avgRiderPowerW == null || stats.validPowerSeconds < 10.0) quality -= 12
            if (stats.avgCadenceRpm == null || stats.validCadenceSeconds < 10.0) quality -= 8
            if (durationSec > 0.0 && stats.validPowerSeconds < durationSec * 0.35) quality -= 8
            quality = quality.coerceIn(0, 100)
            if (quality < 35) continue

            out += ContextualBatterySample(
                sessionId = sessionId,
                mode = mode,
                bucket = terrainBucket(dist, elev.ascentM),
                gradePct = grade,
                pctPerKm = ppk,
                distanceKm = dist,
                ascentM = elev.ascentM,
                avgSpeedKph = stats.avgSpeedKph?.takeIf { it in 2.0..70.0 },
                avgRiderPowerW = stats.avgRiderPowerW?.takeIf { it in 0.0..1600.0 },
                avgMotorPowerW = (stats.avgActiveMotorPowerW ?: stats.avgMotorPowerW)?.takeIf { it in 0.0..1800.0 },
                avgCadenceRpm = stats.avgCadenceRpm?.takeIf { it in 15.0..180.0 },
                motorActiveRatio = stats.motorActiveRatio.coerceIn(0.0, 1.0),
                timestampMs = b.timestampMs,
                qualityScore = quality
            )
        }

        if (out.isNotEmpty()) writeSamples((samples() + out).takeLast(MAX_SAMPLES))
        markTrained(sessionId)
        return out.size
    }

    /**
     * Nearest-context estimate for one terrain segment.
     * Rider power and cadence reference values come from all modes in the same terrain,
     * preventing a mode from being judged only by the very different situations where it
     * happened to be used historically. Speed remains mode-specific because page-2 also
     * predicts mode-specific travel time.
     */
    fun estimate(bucket: TerrainBucket, gradePct: Double, mode: AvinoxAssistMode): ContextualModeEstimate? {
        val revision = prefs.getInt(KEY_REV, 0)
        val gradeBand = (gradePct.coerceIn(-30.0, 35.0) * 2.0).roundToInt() // 0.5% bands
        val cacheKey = "${bucket.name}|${mode.name}|$gradeBand"
        synchronized(estimateCache) {
            if (estimateCacheRevision != revision) {
                estimateCache.clear()
                estimateCacheRevision = revision
            }
            if (estimateCache.containsKey(cacheKey)) return estimateCache[cacheKey]
        }

        val targetGrade = gradeBand / 2.0
        val all = samples().filter {
            it.bucket == bucket && it.qualityScore >= 45 && it.pctPerKm in 0.03..25.0
        }
        if (all.isEmpty()) return cacheEstimate(cacheKey, null)
        val modeSamples = all.filter { it.mode == mode }
        if (modeSamples.isEmpty()) return cacheEstimate(cacheKey, null)

        fun gradeWeight(sample: ContextualBatterySample): Double {
            val d = abs(sample.gradePct - targetGrade) / 3.0
            return (1.0 / (1.0 + d * d)) * (0.35 + 0.65 * sample.qualityScore / 100.0)
        }

        fun weightedMean(items: List<ContextualBatterySample>, selector: (ContextualBatterySample) -> Double?): Double? {
            var sum = 0.0
            var weight = 0.0
            items.forEach { s ->
                val v = selector(s) ?: return@forEach
                if (!v.isFinite()) return@forEach
                val w = gradeWeight(s) * s.distanceKm.coerceIn(0.25, 4.0)
                sum += v * w
                weight += w
            }
            return if (weight > 0.0) sum / weight else null
        }

        // Shared rider/cadence context: same future terrain, independent of selected assist mode.
        val referenceRider = weightedMean(all) { it.avgRiderPowerW }
        val referenceCadence = weightedMean(all) { it.avgCadenceRpm }
        // Speed is an outcome of mode and is therefore learned per mode, while still grade-matched.
        val targetSpeed = weightedMean(modeSamples) { it.avgSpeedKph }

        data class Ranked(val sample: ContextualBatterySample, val distance: Double)
        val ranked = modeSamples.map { s ->
            var d2 = (abs(s.gradePct - targetGrade) / 3.0).pow(2.0) * 2.2
            d2 += contextDistance(s.avgSpeedKph, targetSpeed, 5.0, 0.30).pow(2.0)
            d2 += contextDistance(s.avgRiderPowerW, referenceRider, 65.0, 0.35).pow(2.0) * 1.6
            d2 += contextDistance(s.avgCadenceRpm, referenceCadence, 12.0, 0.30).pow(2.0)
            Ranked(s, kotlin.math.sqrt(d2.coerceAtLeast(0.0)))
        }.sortedBy { it.distance }.take(16)

        if (ranked.isEmpty()) return cacheEstimate(cacheKey, null)
        var ppkSum = 0.0
        var speedSum = 0.0
        var ppkWeight = 0.0
        var speedWeight = 0.0
        ranked.forEachIndexed { index, r ->
            val s = r.sample
            val proximity = 1.0 / (1.0 + r.distance * r.distance)
            val quality = 0.25 + 0.75 * s.qualityScore.coerceIn(0, 100) / 100.0
            val recency = 0.85 + 0.15 * (index + 1).toDouble() / ranked.size.coerceAtLeast(1)
            val w = proximity * quality * s.distanceKm.coerceIn(0.25, 4.0) * recency
            ppkSum += s.pctPerKm * w
            ppkWeight += w
            s.avgSpeedKph?.let { speed ->
                speedSum += speed * w
                speedWeight += w
            }
        }
        if (ppkWeight <= 0.0) return cacheEstimate(cacheKey, null)

        val avgDistance = ranked.map { it.distance }.average()
        val sessions = ranked.map { it.sample.sessionId }.distinct().size
        val avgQuality = ranked.map { it.sample.qualityScore }.average()
        val confidence = (
            (ranked.size.coerceAtMost(10) * 4) +
                (sessions.coerceAtMost(3) * 8) +
                ((1.0 - (avgDistance / 4.0).coerceIn(0.0, 1.0)) * 26.0).toInt() +
                (avgQuality * 0.10).toInt()
            ).coerceIn(0, 100)

        return cacheEstimate(
            cacheKey,
            ContextualModeEstimate(
                pctPerKm = ppkSum / ppkWeight,
                speedKph = if (speedWeight > 0.0) speedSum / speedWeight else targetSpeed,
                confidence = confidence,
                matchCount = ranked.size,
                sessionCount = sessions,
                normalizedContext = ranked.size >= 4 && sessions >= 2 && avgDistance <= 2.2 && referenceRider != null && referenceCadence != null
            )
        )
    }

    private fun cacheEstimate(key: String, value: ContextualModeEstimate?): ContextualModeEstimate? {
        synchronized(estimateCache) { estimateCache[key] = value }
        return value
    }

    fun summaryText(): String {
        val s = samples()
        if (s.isEmpty()) return "상황기반 v2 재해석 없음"
        val lines = mutableListOf("상황기반 v2 ${s.size}구간 · 원본 ${trainedSessionCount()}회")
        AvinoxAssistMode.values().forEach { mode ->
            val m = s.filter { it.mode == mode }
            if (m.isNotEmpty()) {
                val sessions = m.map { it.sessionId }.distinct().size
                lines += "${mode.label} ${m.size}구간/${sessions}회"
            }
        }
        return lines.joinToString(" · ")
    }

    fun modeSummary(mode: AvinoxAssistMode): String {
        val m = samples().filter { it.mode == mode && it.qualityScore >= 45 }
        if (m.isEmpty()) return "${mode.label}: 학습 없음"
        val ppk = m.map { it.pctPerKm }.average()
        val sessions = m.map { it.sessionId }.distinct().size
        return "${mode.label}: ${m.size}구간/${sessions}회 · 평균 ${String.format(Locale.US, "%.2f", ppk)}%/km"
    }

    private fun contextDistance(value: Double?, target: Double?, scale: Double, missingPenalty: Double): Double {
        if (value == null || target == null || !value.isFinite() || !target.isFinite()) return missingPenalty
        return abs(value - target) / scale.coerceAtLeast(0.001)
    }

    private fun terrainBucket(distanceKm: Double, ascentM: Double): TerrainBucket {
        if (distanceKm <= 0.05) return TerrainBucket.FLAT
        val ascentPerKm = ascentM / distanceKm
        return when {
            ascentPerKm < 12.0 -> TerrainBucket.FLAT
            ascentPerKm < 35.0 -> TerrainBucket.ROLLING
            else -> TerrainBucket.CLIMB
        }
    }

    private fun samples(): List<ContextualBatterySample> {
        val revision = prefs.getInt(KEY_REV, 0)
        cachedSamples?.takeIf { cachedRevision == revision }?.let { return it }
        synchronized(lock) {
            cachedSamples?.takeIf { cachedRevision == revision }?.let { return it }
            val parsed = if (!file.exists()) emptyList() else runCatching {
                val arr = JSONArray(file.readText())
                (0 until arr.length()).mapNotNull { i -> fromJson(arr.getJSONObject(i)) }
            }.getOrDefault(emptyList())
            cachedSamples = parsed
            cachedRevision = revision
            return parsed
        }
    }

    private fun writeSamples(items: List<ContextualBatterySample>) {
        val arr = JSONArray()
        items.forEach { s -> arr.put(toJson(s)) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        if (file.exists() && !file.delete()) error("기존 상황학습 파일 교체 실패")
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
        val revision = prefs.getInt(KEY_REV, 0) + 1
        prefs.edit().putInt(KEY_REV, revision).apply()
        synchronized(lock) {
            cachedSamples = items.toList()
            cachedRevision = revision
        }
        synchronized(estimateCache) {
            estimateCache.clear()
            estimateCacheRevision = revision
        }
    }

    private fun toJson(s: ContextualBatterySample): JSONObject = JSONObject().apply {
        put("sessionId", s.sessionId)
        put("mode", s.mode.name)
        put("bucket", s.bucket.name)
        put("gradePct", s.gradePct)
        put("pctPerKm", s.pctPerKm)
        put("distanceKm", s.distanceKm)
        put("ascentM", s.ascentM)
        if (s.avgSpeedKph == null) put("avgSpeedKph", JSONObject.NULL) else put("avgSpeedKph", s.avgSpeedKph)
        if (s.avgRiderPowerW == null) put("avgRiderPowerW", JSONObject.NULL) else put("avgRiderPowerW", s.avgRiderPowerW)
        if (s.avgMotorPowerW == null) put("avgMotorPowerW", JSONObject.NULL) else put("avgMotorPowerW", s.avgMotorPowerW)
        if (s.avgCadenceRpm == null) put("avgCadenceRpm", JSONObject.NULL) else put("avgCadenceRpm", s.avgCadenceRpm)
        put("motorActiveRatio", s.motorActiveRatio)
        put("timestampMs", s.timestampMs)
        put("qualityScore", s.qualityScore)
    }

    private fun fromJson(o: JSONObject): ContextualBatterySample? = runCatching {
        ContextualBatterySample(
            sessionId = o.getString("sessionId"),
            mode = AvinoxAssistMode.valueOf(o.getString("mode")),
            bucket = TerrainBucket.valueOf(o.getString("bucket")),
            gradePct = o.getDouble("gradePct"),
            pctPerKm = o.getDouble("pctPerKm"),
            distanceKm = o.getDouble("distanceKm"),
            ascentM = o.optDouble("ascentM", 0.0),
            avgSpeedKph = nullableDouble(o, "avgSpeedKph"),
            avgRiderPowerW = nullableDouble(o, "avgRiderPowerW"),
            avgMotorPowerW = nullableDouble(o, "avgMotorPowerW"),
            avgCadenceRpm = nullableDouble(o, "avgCadenceRpm"),
            motorActiveRatio = o.optDouble("motorActiveRatio", 0.0).coerceIn(0.0, 1.0),
            timestampMs = o.optLong("timestampMs", 0L),
            qualityScore = o.optInt("qualityScore", 100).coerceIn(0, 100)
        )
    }.getOrNull()

    private fun nullableDouble(o: JSONObject, key: String): Double? =
        if (!o.has(key) || o.isNull(key)) null else o.optDouble(key).takeIf { it.isFinite() }

    private fun trainedSessions(): MutableSet<String> =
        prefs.getStringSet(KEY_TRAINED, emptySet())?.toMutableSet() ?: mutableSetOf()

    private fun markTrained(sessionId: String) {
        val set = trainedSessions().apply { add(sessionId) }
        prefs.edit().putStringSet(KEY_TRAINED, set.toSet()).apply()
    }
}

class AvinoxContextReanalysisManager(context: Context) {
    companion object {
        private val rebuilding = AtomicBoolean(false)
        private const val META_PREFS = "avinox_context_reanalysis_meta"
        private const val KEY_SCHEMA = "schema_version"
        private const val SCHEMA_VERSION = 2
    }

    data class Result(
        val sourceFiles: Int,
        val parsedRides: Int,
        val samples: Int,
        val duplicates: Int,
        val failed: Int,
        val message: String
    )

    private val app = context.applicationContext
    private val store = ContextualBatteryLearningStore(app)
    private val metaPrefs = app.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)

    fun needsAutomaticRebuild(): Boolean =
        metaPrefs.getInt(KEY_SCHEMA, 0) < SCHEMA_VERSION && hasStoredOriginals()

    fun hasStoredOriginals(): Boolean {
        val direct = File(app.filesDir, "avinox_proto").listFiles()?.any { it.isFile && it.extension.equals("proto", true) } == true
        if (direct) return true
        val historical = File(app.filesDir, "historical_ride_data")
        return historical.exists() && historical.walkTopDown().any {
            it.isFile && it.extension.equals("proto", true) && it.name.startsWith("original")
        }
    }

    fun rebuildAsync(onDone: (Result) -> Unit) {
        if (!rebuilding.compareAndSet(false, true)) {
            onDone(Result(0, 0, 0, 0, 0, "Avinox 원본 재해석이 이미 진행 중입니다."))
            return
        }
        Thread {
            val result = runCatching { rebuildNow() }
                .getOrElse { e -> Result(0, 0, 0, 0, 1, "Avinox 원본 재해석 실패 · ${e.message ?: e.javaClass.simpleName}") }
            // Automatic migration is one-shot per schema. Manual rebuild remains available at any time.
            metaPrefs.edit().putInt(KEY_SCHEMA, SCHEMA_VERSION).apply()
            rebuilding.set(false)
            onDone(result)
        }.start()
    }

    private fun rebuildNow(): Result {
        val candidates = mutableListOf<File>()
        File(app.filesDir, "avinox_proto").takeIf { it.exists() }?.listFiles()
            ?.filterTo(candidates) { it.isFile && it.extension.equals("proto", true) }
        File(app.filesDir, "historical_ride_data").takeIf { it.exists() }?.walkTopDown()
            ?.filter { it.isFile && it.extension.equals("proto", true) && it.name.startsWith("original") }
            ?.forEach { candidates += it }

        val valid = candidates.filter { it.length() in 256L..(64L * 1024L * 1024L) }
        if (valid.isEmpty()) {
            store.clear()
            return Result(0, 0, 0, 0, 0, "저장된 Avinox 원본 .proto가 없습니다.")
        }

        val unique = LinkedHashMap<String, File>()
        valid.forEach { file -> unique.putIfAbsent(sha256(file), file) }
        val duplicateCount = valid.size - unique.size

        // v1 remains untouched. Only the v2 context model is rebuilt from raw originals.
        store.clear()
        var parsed = 0
        var samples = 0
        var failed = 0
        unique.forEach { (hash, file) ->
            val ok = runCatching {
                val ride = AvinoxProtoParser.parse(file)
                val sessionId = "context_v2_proto_${ride.header.rideId}_${ride.header.startUnixSec}_${hash.take(10)}"
                samples += store.trainRide(sessionId, ride)
                parsed += 1
            }.isSuccess
            if (!ok) failed += 1
        }
        val message = buildString {
            append("Avinox 원본 ").append(parsed).append("개 재해석 · 상황기반 v2 ").append(samples).append("구간")
            if (duplicateCount > 0) append(" · 중복 ").append(duplicateCount).append("개 제외")
            if (failed > 0) append(" · 실패 ").append(failed).append("개")
        }
        return Result(valid.size, parsed, samples, duplicateCount, failed, message)
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
