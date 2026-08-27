package com.seungjae.jangsu280battery

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class TerrainBucket(val label: String) { FLAT("평지/완만"), ROLLING("구릉"), CLIMB("업힐") }


data class LearnedAssistProfile(
    val bucket: TerrainBucket,
    val sampleCount: Int,
    val avgSpeedKph: Double?,
    val avgMotorPowerW: Double?,
    val avgRiderPowerW: Double?,
    val avgCadenceRpm: Double?,
    val quality: Int
)

data class AssistModeWindow(
    val startMs: Long,
    val endMs: Long,
    val mode: AvinoxAssistMode,
    val profileId: String?
)

data class BatteryLearningSample(
    val bucket: TerrainBucket,
    val factor: Double,
    val pctPerKm: Double,
    val distanceKm: Double,
    val ascentM: Double,
    val timestampMs: Long,
    val sessionId: String,
    /** v0.18.1부터 Eco/Auto/Trail/Turbo를 섞지 않고 별도 학습한다. */
    val assistMode: AvinoxAssistMode? = null,
    val assistProfileId: String? = null,
    /** FIT에서 보존된 보조 설명 변수. 현재 예측식에 억지로 직접 대입하지 않는다. */
    val riderWh: Double = 0.0,
    val motorWh: Double = 0.0,
    val avgSpeedKph: Double? = null,
    val avgRiderPowerW: Double? = null,
    val avgMotorPowerW: Double? = null,
    val avgActiveMotorPowerW: Double? = null,
    val avgCadenceRpm: Double? = null,
    val motorActiveRatio: Double = 0.0,
    val qualityScore: Int = 100
)

class BatteryLearningStore(context: Context) {
    companion object {
        private const val PREFS = "battery_learning_v1"
        private const val KEY_SAMPLES = "samples"
        private const val KEY_TRAINED = "trained_sessions"
        private const val KEY_REV = "samples_revision"
        private const val MAX_SAMPLES = 1600

        @Volatile private var cachedRevision: Int = Int.MIN_VALUE
        @Volatile private var cachedSamples: List<BatteryLearningSample>? = null
        private val cacheLock = Any()
        private val factorCache = HashMap<String, Double>()

        // v0.11.0 중립 seed. 장수280 실측값으로 미리 보정한 계수가 아니다.
        const val FLAT_PCT_PER_KM = 0.45
        const val ASCENT_PCT_PER_M = 0.028
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val assistProfiles = AvinoxAssistProfileStore(appContext)
    private val fitAuxStore = FitAuxLearningStore(appContext)

    private fun selectedMode(): AvinoxAssistMode? =
        assistProfiles.preferredMode().takeIf { assistProfiles.hasPreferredMode() }

    private fun selectedProfileId(mode: AvinoxAssistMode?): String? = mode?.let { assistProfiles.get(it).profileId }

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

    fun learnedFactor(bucket: TerrainBucket, mode: AvinoxAssistMode? = selectedMode()): Double {
        val profileId = mode?.let { selectedProfileId(it) }
        val revision = prefs.getInt(KEY_REV, 0)
        val cacheKey = "$revision|${bucket.name}|${mode?.name ?: "-"}|${profileId ?: "-"}"
        synchronized(cacheLock) {
            factorCache[cacheKey]?.let { return it }
        }

        val allSamples = samples()
        val value = if (mode != null) {
            val exactProfile = allSamples.filter { it.bucket == bucket && it.assistMode == mode && it.assistProfileId == profileId }.takeLast(16)
            when {
                exactProfile.isNotEmpty() -> weightedFactor(exactProfile)
                else -> {
                    val modeWithoutProfile = allSamples.filter { it.bucket == bucket && it.assistMode == mode && it.assistProfileId == null }.takeLast(12)
                    when {
                        modeWithoutProfile.isNotEmpty() -> weightedFactor(modeWithoutProfile)
                        else -> {
                            // 프로필이 바뀌면 같은 모드라도 다른 세팅 학습을 섞지 않는다.
                            val legacy = allSamples.filter { it.bucket == bucket && it.assistMode == null }.takeLast(12)
                            if (legacy.isNotEmpty()) weightedFactor(legacy) else 1.0
                        }
                    }
                }
            }
        } else {
            val specific = allSamples.filter { it.bucket == bucket }.takeLast(16)
            if (specific.isNotEmpty()) weightedFactor(specific) else 1.0
        }

        synchronized(cacheLock) {
            factorCache[cacheKey] = value
            // Revision is part of the key; keep the map bounded across learning updates.
            if (factorCache.size > 64) {
                val prefix = "$revision|"
                factorCache.keys.removeAll { !it.startsWith(prefix) }
            }
        }
        return value
    }



    /** 현재 선택된 Avinox 프로필 기준으로 이 모드의 A급 배터리 학습 샘플 수를 반환한다. */
    fun batterySampleCountForMode(mode: AvinoxAssistMode): Int {
        val all = samples().filter { it.assistMode == mode && it.qualityScore >= 45 }
        val profileId = selectedProfileId(mode)
        val exact = all.filter { it.assistProfileId == profileId }
        if (exact.isNotEmpty()) return exact.size
        return all.count { it.assistProfileId == null }
    }

    /**
     * 자유주행 HUD용 모드별 평균 소비율(%/km).
     * FIT+ZIP으로 검증된 A급 SOC/모드 샘플만 사용하며 B급 FIT 단독 자료는 섞지 않는다.
     */
    fun learnedPctPerKmForMode(mode: AvinoxAssistMode): Double? {
        val all = samples().filter {
            it.assistMode == mode && it.qualityScore >= 45 &&
                it.pctPerKm.isFinite() && it.pctPerKm in 0.03..25.0 && it.distanceKm >= 0.3
        }
        val profileId = selectedProfileId(mode)
        val exact = all.filter { it.assistProfileId == profileId }
        val subset = (if (exact.isNotEmpty()) exact else all.filter { it.assistProfileId == null }).takeLast(24)
        if (subset.isEmpty()) return null
        var sum = 0.0
        var weight = 0.0
        subset.forEachIndexed { index, sample ->
            val recency = 0.8 + (index + 1).toDouble() / subset.size.coerceAtLeast(1)
            val distanceWeight = sample.distanceKm.coerceIn(0.5, 12.0)
            val qualityWeight = (sample.qualityScore.coerceIn(0, 100) / 100.0).coerceAtLeast(0.25)
            val w = recency * distanceWeight * qualityWeight
            sum += sample.pctPerKm * w
            weight += w
        }
        return (sum / weight).takeIf { weight > 0.0 && it.isFinite() && it > 0.0 }
    }

    fun assistProfile(bucket: TerrainBucket, mode: AvinoxAssistMode? = selectedMode()): LearnedAssistProfile? {
        val all = samples()
        val subset = if (mode != null) {
            val profileId = selectedProfileId(mode)
            val exactProfile = all.filter { it.bucket == bucket && it.assistMode == mode && it.assistProfileId == profileId && it.qualityScore >= 45 }.takeLast(24)
            if (exactProfile.isNotEmpty()) exactProfile
            else all.filter { it.bucket == bucket && it.assistMode == mode && it.assistProfileId == null && it.qualityScore >= 45 }.takeLast(24)
        } else {
            all.filter { it.bucket == bucket && it.qualityScore >= 45 }.takeLast(24)
        }
        if (subset.isEmpty()) return fitAuxStore.profile(bucket)

        fun weighted(selector: (BatteryLearningSample) -> Double?): Double? {
            var sum = 0.0
            var weight = 0.0
            subset.forEachIndexed { index, sample ->
                val value = selector(sample) ?: return@forEachIndexed
                if (!value.isFinite()) return@forEachIndexed
                val recency = 0.75 + (index + 1).toDouble() / subset.size.coerceAtLeast(1)
                val distanceWeight = sample.distanceKm.coerceIn(0.5, 15.0)
                val qualityWeight = (sample.qualityScore.coerceIn(0, 100) / 100.0).coerceAtLeast(0.2)
                val w = recency * distanceWeight * qualityWeight
                sum += value * w
                weight += w
            }
            return if (weight > 0.0) sum / weight else null
        }

        val quality = subset.map { it.qualityScore }.average().toInt().coerceIn(0, 100)
        return LearnedAssistProfile(
            bucket = bucket,
            sampleCount = subset.size,
            avgSpeedKph = weighted { it.avgSpeedKph }?.takeIf { it in 3.0..60.0 },
            avgMotorPowerW = weighted { it.avgActiveMotorPowerW ?: it.avgMotorPowerW }?.takeIf { it in 0.0..1500.0 },
            avgRiderPowerW = weighted { it.avgRiderPowerW }?.takeIf { it in 0.0..1500.0 },
            avgCadenceRpm = weighted { it.avgCadenceRpm }?.takeIf { it in 20.0..150.0 },
            quality = quality
        )
    }

    fun assistProfileFor(course: CourseData, fromKm: Double, spanKm: Double = 1.0): LearnedAssistProfile? {
        val end = (fromKm + spanKm.coerceIn(0.3, 3.0)).coerceAtMost(course.totalKm)
        val dist = (end - fromKm.coerceIn(0.0, course.totalKm)).coerceAtLeast(0.0)
        if (dist < 0.05) return null
        val ascent = course.elevationBetween(fromKm, end).ascentM
        return assistProfile(bucket(dist, ascent))
    }

    fun estimateConsumption(course: CourseData, fromKm: Double, toKm: Double, mode: AvinoxAssistMode? = selectedMode()): Double {
        val start = fromKm.coerceIn(0.0, course.totalKm)
        val end = toKm.coerceIn(start, course.totalKm)
        if (end <= start) return 0.0

        // v0.26.2: resolve terrain factors once. Do not parse/filter the learning set
        // again for every 0.5 km course block.
        val factors = TerrainBucket.values().associateWith { learnedFactor(it, mode) }

        var x = start
        var total = 0.0
        while (x < end - 0.0001) {
            val nx = (x + 0.5).coerceAtMost(end)
            val dist = nx - x
            val ascent = course.elevationBetween(x, nx).ascentM
            val terrain = bucket(dist, ascent)
            total += baseConsumption(dist, ascent) * (factors[terrain] ?: 1.0)
            x = nx
        }
        return total
    }

    /** 앱 실주행에서 확정된 배터리 체크포인트만 학습한다. */
    fun trainFromRide(sessionId: String, course: CourseData, entries: List<ActualBatteryEntry>): Int {
        if (sessionId.isBlank() || isTrained(sessionId)) return 0
        val ordered = entries.sortedBy { it.timestampMs }
        val newSamples = mutableListOf<BatteryLearningSample>()
        for (i in 1 until ordered.size) {
            val a = ordered[i - 1]
            val b = ordered[i]
            val dist = b.routeKm - a.routeKm
            val used = a.percent - b.percent
            // 충전 전→후는 배터리가 증가하므로 자동 제외된다.
            if (dist < 0.7 || used < 0.8) continue
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
        if (newSamples.isNotEmpty()) writeSamples((samples() + newSamples).takeLast(MAX_SAMPLES))
        markTrained(sessionId)
        return newSamples.size
    }

    /**
     * FIT/GPX 과거 라이딩을 학습한다. 실제 배터리 지점 사이의 총 소비량을 0.5km 블록에 배분한다.
     * 모터 파워가 충분한 FIT은 모터 출력 에너지 분포 75% + 중립 지형식 25%로 배분하고,
     * 파워가 없거나 결측이면 중립 지형식만 사용한다. rider/motor 에너지는 샘플에도 보존한다.
     */
    fun trainHistoricalRide(
        sessionId: String,
        course: CourseData,
        entries: List<ActualBatteryEntry>,
        telemetry: List<HistoricalTelemetryPoint> = emptyList(),
        qualityScore: Int = 100
    ): Int {
        if (sessionId.isBlank() || isTrained(sessionId)) return 0
        val ordered = entries.withIndex()
            .sortedWith(compareBy<IndexedValue<ActualBatteryEntry>> { it.value.routeKm }.thenBy { it.index })
            .map { it.value }
        val newSamples = mutableListOf<BatteryLearningSample>()

        for (i in 1 until ordered.size) {
            val a = ordered[i - 1]
            val b = ordered[i]
            val dist = b.routeKm - a.routeKm
            val used = a.percent - b.percent
            if (dist < 0.7 || used < 0.8) continue

            data class Block(
                val bucket: TerrainBucket,
                val distance: Double,
                val ascent: Double,
                val base: Double,
                val telemetry: TelemetrySegmentStats
            )
            data class Agg(
                var base: Double = 0.0,
                var allocatedUse: Double = 0.0,
                var distance: Double = 0.0,
                var ascent: Double = 0.0,
                var riderWh: Double = 0.0,
                var motorWh: Double = 0.0,
                var speedWeighted: Double = 0.0,
                var speedDistance: Double = 0.0,
                var activeWeighted: Double = 0.0,
                var activeDistance: Double = 0.0,
                var riderPowerWeighted: Double = 0.0,
                var riderPowerSeconds: Double = 0.0,
                var motorPowerWeighted: Double = 0.0,
                var motorPowerSeconds: Double = 0.0,
                var activeMotorPowerWeighted: Double = 0.0,
                var activeMotorPowerSeconds: Double = 0.0,
                var cadenceWeighted: Double = 0.0,
                var cadenceSeconds: Double = 0.0
            )

            val blocks = mutableListOf<Block>()
            var x = a.routeKm.coerceIn(0.0, course.totalKm)
            val end = b.routeKm.coerceIn(x, course.totalKm)
            while (x < end - 0.0001) {
                val nx = (x + 0.5).coerceAtMost(end)
                val d = nx - x
                val ascent = course.elevationBetween(x, nx).ascentM
                val ts = if (telemetry.isNotEmpty()) {
                    TelemetryMath.segmentStats(telemetry, x, nx)
                } else {
                    TelemetrySegmentStats(0.0, 0.0, null, 0.0, 0.0, 0.0, 0.0)
                }
                blocks += Block(
                    bucket = bucket(d, ascent),
                    distance = d,
                    ascent = ascent,
                    base = baseConsumption(d, ascent),
                    telemetry = ts
                )
                x = nx
            }

            val modeled = blocks.sumOf { it.base }
            if (modeled < 0.5) continue
            val totalMotorWh = blocks.sumOf { it.telemetry.motorWh }
            val totalValidMotorSec = blocks.sumOf { it.telemetry.validPowerSeconds }
            // Avinox처럼 모터 파워가 충분히 기록된 FIT은 실제 모터 출력 에너지 분포를 주된 배분 근거로 쓴다.
            // motor_power는 배터리 입력전력과 동일하지 않으므로 1:1 환산하지 않고, 지형 기본식 25%를 섞어 과적합을 막는다.
            val useMotorAllocation = totalMotorWh >= 5.0 && totalValidMotorSec >= 30.0
            val aggs = TerrainBucket.values().associateWith { Agg() }.toMutableMap()

            blocks.forEach { block ->
                val baseShare = (block.base / modeled).coerceIn(0.0, 1.0)
                val motorShare = if (useMotorAllocation && totalMotorWh > 0.0) {
                    (block.telemetry.motorWh / totalMotorWh).coerceIn(0.0, 1.0)
                } else baseShare
                val allocationShare = if (useMotorAllocation) motorShare * 0.75 + baseShare * 0.25 else baseShare
                val agg = aggs.getValue(block.bucket)
                agg.base += block.base
                agg.allocatedUse += used * allocationShare
                agg.distance += block.distance
                agg.ascent += block.ascent
                agg.riderWh += block.telemetry.riderWh
                agg.motorWh += block.telemetry.motorWh
                block.telemetry.avgSpeedKph?.let {
                    agg.speedWeighted += it * block.distance
                    agg.speedDistance += block.distance
                }
                if (block.telemetry.validPowerSeconds > 0.0) {
                    agg.activeWeighted += block.telemetry.motorActiveRatio * block.distance
                    agg.activeDistance += block.distance
                    block.telemetry.avgRiderPowerW?.let {
                        agg.riderPowerWeighted += it * block.telemetry.validPowerSeconds
                        agg.riderPowerSeconds += block.telemetry.validPowerSeconds
                    }
                    block.telemetry.avgMotorPowerW?.let {
                        agg.motorPowerWeighted += it * block.telemetry.validPowerSeconds
                        agg.motorPowerSeconds += block.telemetry.validPowerSeconds
                    }
                    block.telemetry.avgActiveMotorPowerW?.let { active ->
                        val activeSeconds = block.telemetry.validPowerSeconds * block.telemetry.motorActiveRatio
                        if (activeSeconds > 0.0) {
                            agg.activeMotorPowerWeighted += active * activeSeconds
                            agg.activeMotorPowerSeconds += activeSeconds
                        }
                    }
                }
                if (block.telemetry.validCadenceSeconds > 0.0) {
                    block.telemetry.avgCadenceRpm?.let {
                        agg.cadenceWeighted += it * block.telemetry.validCadenceSeconds
                        agg.cadenceSeconds += block.telemetry.validCadenceSeconds
                    }
                }
            }

            aggs.forEach { (bkt, agg) ->
                if (agg.distance < 0.25 || agg.base < 0.15 || agg.allocatedUse <= 0.0) return@forEach
                val factor = (agg.allocatedUse / agg.base).coerceIn(0.45, 2.20)
                newSamples += BatteryLearningSample(
                    bucket = bkt,
                    factor = factor,
                    pctPerKm = agg.allocatedUse / agg.distance,
                    distanceKm = agg.distance,
                    ascentM = agg.ascent,
                    timestampMs = b.timestampMs.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    sessionId = sessionId,
                    riderWh = agg.riderWh,
                    motorWh = agg.motorWh,
                    avgSpeedKph = if (agg.speedDistance > 0.0) agg.speedWeighted / agg.speedDistance else null,
                    avgRiderPowerW = if (agg.riderPowerSeconds > 0.0) agg.riderPowerWeighted / agg.riderPowerSeconds else null,
                    avgMotorPowerW = if (agg.motorPowerSeconds > 0.0) agg.motorPowerWeighted / agg.motorPowerSeconds else null,
                    avgActiveMotorPowerW = if (agg.activeMotorPowerSeconds > 0.0) agg.activeMotorPowerWeighted / agg.activeMotorPowerSeconds else null,
                    avgCadenceRpm = if (agg.cadenceSeconds > 0.0) agg.cadenceWeighted / agg.cadenceSeconds else null,
                    motorActiveRatio = if (agg.activeDistance > 0.0) agg.activeWeighted / agg.activeDistance else 0.0,
                    qualityScore = qualityScore.coerceIn(0, 100)
                )
            }
        }

        if (newSamples.isNotEmpty()) writeSamples((samples() + newSamples).takeLast(MAX_SAMPLES))
        markTrained(sessionId)
        return newSamples.size
    }

    /**
     * v0.18.1 클린 학습 경로.
     * BLE SOC 1% 경계 사이에 모드 전환이 없고, 그 전체 구간이 하나의 assist window 안에 있을 때만 학습한다.
     * 따라서 Eco/Auto/Trail/Turbo 소비가 서로 섞이지 않는다.
     * 완충 직후 100% plateau는 BMS 표시 비선형성이 커 98% 이하부터 학습한다.
     */
    fun trainModeSeparatedRide(
        sessionId: String,
        course: CourseData,
        entries: List<ActualBatteryEntry>,
        telemetry: List<HistoricalTelemetryPoint>,
        modeWindows: List<AssistModeWindow>,
        qualityScore: Int = 100
    ): Int {
        if (sessionId.isBlank() || isTrained(sessionId) || modeWindows.isEmpty()) return 0
        val ordered = entries.sortedBy { it.timestampMs }
        val windows = modeWindows.sortedBy { it.startMs }
        val newSamples = mutableListOf<BatteryLearningSample>()

        fun stableWindow(aMs: Long, bMs: Long): AssistModeWindow? {
            if (aMs <= 0L || bMs <= aMs) return null
            return windows.firstOrNull { aMs >= it.startMs && bMs <= it.endMs }
        }

        for (i in 1 until ordered.size) {
            val a = ordered[i - 1]
            val b = ordered[i]
            val used = a.percent - b.percent
            // 100→99 및 99→98은 완충 plateau/표시 보정 영향이 커 학습에서 제외.
            if (a.percent > 98.5) continue
            if (used !in 0.8..2.2) continue
            val modeWindow = stableWindow(a.timestampMs, b.timestampMs) ?: continue
            val dist = b.routeKm - a.routeKm
            if (dist < 0.25) continue
            val ascent = course.elevationBetween(a.routeKm, b.routeKm).ascentM
            val modeled = baseConsumption(dist, ascent)
            if (modeled < 0.18) continue
            val factor = (used / modeled).coerceIn(0.45, 2.20)
            val ts = if (telemetry.isNotEmpty()) TelemetryMath.segmentStats(telemetry, a.routeKm, b.routeKm)
            else TelemetrySegmentStats(0.0, 0.0, null, 0.0, 0.0, 0.0, 0.0)

            newSamples += BatteryLearningSample(
                bucket = bucket(dist, ascent),
                factor = factor,
                pctPerKm = used / dist,
                distanceKm = dist,
                ascentM = ascent,
                timestampMs = b.timestampMs.takeIf { it > 0 } ?: System.currentTimeMillis(),
                sessionId = sessionId,
                assistMode = modeWindow.mode,
                assistProfileId = modeWindow.profileId,
                riderWh = ts.riderWh,
                motorWh = ts.motorWh,
                avgSpeedKph = ts.avgSpeedKph,
                avgRiderPowerW = ts.avgRiderPowerW,
                avgMotorPowerW = ts.avgMotorPowerW,
                avgActiveMotorPowerW = ts.avgActiveMotorPowerW,
                avgCadenceRpm = ts.avgCadenceRpm,
                motorActiveRatio = ts.motorActiveRatio,
                qualityScore = qualityScore.coerceIn(0, 100)
            )
        }
        if (newSamples.isNotEmpty()) {
            writeSamples((samples() + newSamples).takeLast(MAX_SAMPLES))
            markTrained(sessionId)
        }
        return newSamples.size
    }

    fun hasSession(sessionId: String): Boolean = isTrained(sessionId)

    fun removeSession(sessionId: String): Int {
        if (sessionId.isBlank()) return 0
        val before = samples()
        val after = before.filterNot { it.sessionId == sessionId }
        if (after.size != before.size) writeSamples(after)
        prefs.edit().putStringSet(KEY_TRAINED, trainedSessions().apply { remove(sessionId) }).apply()
        return before.size - after.size
    }

    fun samples(): List<BatteryLearningSample> {
        val revision = prefs.getInt(KEY_REV, 0)
        cachedSamples?.takeIf { cachedRevision == revision }?.let { return it }

        synchronized(cacheLock) {
            cachedSamples?.takeIf { cachedRevision == revision }?.let { return it }
            val raw = prefs.getString(KEY_SAMPLES, null)
            val parsed = if (raw.isNullOrBlank()) {
                emptyList()
            } else {
                try {
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
                            sessionId = o.optString("sessionId", ""),
                            assistMode = if (o.has("assistMode") && !o.isNull("assistMode")) runCatching { AvinoxAssistMode.valueOf(o.optString("assistMode")) }.getOrNull() else null,
                            assistProfileId = if (o.has("assistProfileId") && !o.isNull("assistProfileId")) o.optString("assistProfileId").takeIf { it.isNotBlank() } else null,
                            riderWh = o.optDouble("riderWh", 0.0),
                            motorWh = o.optDouble("motorWh", 0.0),
                            avgSpeedKph = if (o.has("avgSpeedKph") && !o.isNull("avgSpeedKph")) o.optDouble("avgSpeedKph") else null,
                            avgRiderPowerW = if (o.has("avgRiderPowerW") && !o.isNull("avgRiderPowerW")) o.optDouble("avgRiderPowerW") else null,
                            avgMotorPowerW = if (o.has("avgMotorPowerW") && !o.isNull("avgMotorPowerW")) o.optDouble("avgMotorPowerW") else null,
                            avgActiveMotorPowerW = if (o.has("avgActiveMotorPowerW") && !o.isNull("avgActiveMotorPowerW")) o.optDouble("avgActiveMotorPowerW") else null,
                            avgCadenceRpm = if (o.has("avgCadenceRpm") && !o.isNull("avgCadenceRpm")) o.optDouble("avgCadenceRpm") else null,
                            motorActiveRatio = o.optDouble("motorActiveRatio", 0.0),
                            qualityScore = o.optInt("qualityScore", 100).coerceIn(0, 100)
                        )
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }
            cachedSamples = parsed
            cachedRevision = revision
            return parsed
        }
    }

    fun summaryText(): String {
        val s = samples()
        if (s.isEmpty()) return "개인 소비 학습 없음 · 중립 초기 모델 사용"
        val lines = mutableListOf("개인 소비 학습 ${s.size}개 구간")
        AvinoxAssistMode.values().forEach { mode ->
            val modeSamples = s.filter { it.assistMode == mode }
            if (modeSamples.isNotEmpty()) {
                val parts = TerrainBucket.values().mapNotNull { b ->
                    val subset = modeSamples.filter { it.bucket == b }.takeLast(16)
                    if (subset.isEmpty()) null else {
                        val ppk = subset.map { it.pctPerKm }.average()
                        "${b.label} ${String.format(java.util.Locale.US, "%.2f", ppk)}%/km(${subset.size})"
                    }
                }
                lines += "${mode.label}: ${parts.joinToString(" · ")}"
            }
        }
        val legacy = s.count { it.assistMode == null }
        if (legacy > 0) lines += "이전 모드미기록 학습: ${legacy}개 · 모드별 값이 없을 때만 보조 fallback"
        return lines.joinToString("\n")
    }

    fun clear() {
        prefs.edit().clear().apply()
        synchronized(cacheLock) {
            cachedSamples = emptyList()
            cachedRevision = 0
            factorCache.clear()
        }
    }

    private fun weightedFactor(items: List<BatteryLearningSample>): Double {
        var sum = 0.0
        var weight = 0.0
        items.forEachIndexed { index, s ->
            val recency = 1.0 + index.toDouble() / items.size.coerceAtLeast(1)
            val distanceWeight = 0.5 + s.distanceKm.coerceIn(1.0, 20.0) / 20.0
            val qualityWeight = 0.25 + 0.75 * (s.qualityScore.coerceIn(0, 100) / 100.0)
            val w = recency * distanceWeight * qualityWeight
            sum += s.factor * w
            weight += w
        }
        return if (weight > 0.0) (sum / weight).coerceIn(0.55, 1.80) else 1.0
    }

    private fun writeSamples(items: List<BatteryLearningSample>) {
        val arr = JSONArray()
        items.forEach { s ->
            arr.put(JSONObject().apply {
                put("bucket", s.bucket.name)
                put("factor", s.factor)
                put("pctPerKm", s.pctPerKm)
                put("distanceKm", s.distanceKm)
                put("ascentM", s.ascentM)
                put("timestampMs", s.timestampMs)
                put("sessionId", s.sessionId)
                if (s.assistMode == null) put("assistMode", JSONObject.NULL) else put("assistMode", s.assistMode.name)
                if (s.assistProfileId == null) put("assistProfileId", JSONObject.NULL) else put("assistProfileId", s.assistProfileId)
                put("riderWh", s.riderWh)
                put("motorWh", s.motorWh)
                if (s.avgSpeedKph == null) put("avgSpeedKph", JSONObject.NULL) else put("avgSpeedKph", s.avgSpeedKph)
                if (s.avgRiderPowerW == null) put("avgRiderPowerW", JSONObject.NULL) else put("avgRiderPowerW", s.avgRiderPowerW)
                if (s.avgMotorPowerW == null) put("avgMotorPowerW", JSONObject.NULL) else put("avgMotorPowerW", s.avgMotorPowerW)
                if (s.avgActiveMotorPowerW == null) put("avgActiveMotorPowerW", JSONObject.NULL) else put("avgActiveMotorPowerW", s.avgActiveMotorPowerW)
                if (s.avgCadenceRpm == null) put("avgCadenceRpm", JSONObject.NULL) else put("avgCadenceRpm", s.avgCadenceRpm)
                put("motorActiveRatio", s.motorActiveRatio)
                put("qualityScore", s.qualityScore)
            })
        }
        val nextRevision = prefs.getInt(KEY_REV, 0) + 1
        prefs.edit()
            .putString(KEY_SAMPLES, arr.toString())
            .putInt(KEY_REV, nextRevision)
            .apply()
        synchronized(cacheLock) {
            cachedSamples = items.toList()
            cachedRevision = nextRevision
            factorCache.clear()
        }
    }

    private fun trainedSessions(): MutableSet<String> =
        prefs.getStringSet(KEY_TRAINED, emptySet())?.toMutableSet() ?: mutableSetOf()

    private fun isTrained(id: String): Boolean = trainedSessions().contains(id)

    private fun markTrained(id: String) {
        val set = trainedSessions().apply {
            add(id)
            while (size > 80) remove(first())
        }
        prefs.edit().putStringSet(KEY_TRAINED, set).apply()
    }
}
