package com.seungjae.jangsu280battery

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.min

enum class StravaRideUse { ENDURANCE, PARTIAL, EXCLUDED }

data class StravaGradeBin(
    val minGrade: Double,
    val maxGrade: Double,
    val seconds: Double,
    val speedWeighted: Double,
    val powerSeconds: Double,
    val powerWeighted: Double
) {
    fun avgSpeedKph(): Double? = if (seconds >= 60.0) speedWeighted / seconds else null
    fun avgPowerW(): Double? = if (powerSeconds >= 60.0) powerWeighted / powerSeconds else null
}

data class StravaPowerCurve(
    val p15s: Double? = null,
    val p1m: Double? = null,
    val p2m: Double? = null,
    val p5m: Double? = null,
    val p10m: Double? = null,
    val p20m: Double? = null,
    val p40m: Double? = null,
    val p1h: Double? = null,
    val p2h: Double? = null,
    val p4h: Double? = null
)

data class StravaRideReview(
    val id: Long,
    val name: String,
    val dateText: String,
    val distanceKm: Double,
    val ascentM: Double,
    val movingSec: Double,
    val elapsedSec: Double,
    val totalStopSec: Double,
    val longestStopSec: Double,
    val movingAvgKph: Double,
    val use: StravaRideUse,
    val reason: String,
    val hasPower: Boolean,
    val avgPowerW: Double?,
    val weightedPowerW: Double?,
    val avgHeartRate: Double?,
    val avgCadence: Double?,
    val localPower: StravaPowerCurve = StravaPowerCurve(),
    val gradeBins: List<StravaGradeBin> = emptyList(),
    val streamAnalyzed: Boolean = true
) {
    val year: Int get() = dateText.take(4).toIntOrNull() ?: 0
}

data class StravaPrResult(
    val label: String,
    val seconds: Int,
    val watts: Double,
    val rideId: Long,
    val rideName: String,
    val dateText: String
)

data class StravaRiderReviewProfile(
    val athleteName: String?,
    val analyzedAtMs: Long,
    val rides: List<StravaRideReview>,
    val totalRoadActivities: Int = rides.size,
    val scanComplete: Boolean = true,
    val stopReason: String? = null,
    val selectedYear: Int = 0,
    val linkedAtMs: Long = 0L
) {
    val availableYears: List<Int>
        get() = rides.map { it.year }.filter { it > 0 }.distinct().sortedDescending()

    fun resolvedYear(): Int = selectedYear.takeIf { it in availableYears } ?: (availableYears.firstOrNull() ?: 0)
    fun ridesForYear(year: Int): List<StravaRideReview> = rides.filter { it.year == year }
    val selectedRides: List<StravaRideReview> get() = ridesForYear(resolvedYear())
    val enduranceRides: List<StravaRideReview> get() = selectedRides.filter { it.use == StravaRideUse.ENDURANCE }
    val partialRides: List<StravaRideReview> get() = selectedRides.filter { it.use == StravaRideUse.PARTIAL }
    val excludedRides: List<StravaRideReview> get() = selectedRides.filter { it.use == StravaRideUse.EXCLUDED }
    val analyzedActivityCount: Int get() = rides.count { it.streamAnalyzed }

    fun referenceMovingSpeedKph(year: Int = resolvedYear()): Double? {
        val valid = ridesForYear(year).filter { it.use == StravaRideUse.ENDURANCE }
        val secs = valid.sumOf { it.movingSec }
        val km = valid.sumOf { it.distanceKm }
        return if (secs >= 3600.0 && km > 0.0) km / (secs / 3600.0) else null
    }

    fun yearPower(year: Int = resolvedYear()): StravaPowerCurve = curveFromRides(ridesForYear(year))
    fun allTimePower(): StravaPowerCurve = curveFromRides(rides)
    val power: StravaPowerCurve get() = yearPower()
    val gradeBins: List<StravaGradeBin> get() = aggregateBins(selectedRides)

    fun prResults(year: Int = resolvedYear()): List<StravaPrResult> = prResultsFromRides(ridesForYear(year))
    fun allTimePrResults(): List<StravaPrResult> = prResultsFromRides(rides)

    private fun curveFromRides(source: List<StravaRideReview>): StravaPowerCurve {
        fun best(get: (StravaPowerCurve) -> Double?): Double? = source.mapNotNull { get(it.localPower) }.maxOrNull()
        return StravaPowerCurve(
            best { it.p15s }, best { it.p1m }, best { it.p2m }, best { it.p5m }, best { it.p10m },
            best { it.p20m }, best { it.p40m }, best { it.p1h }, best { it.p2h }, best { it.p4h }
        )
    }

    private fun prResultsFromRides(source: List<StravaRideReview>): List<StravaPrResult> {
        data class Window(val label: String, val seconds: Int, val value: (StravaPowerCurve) -> Double?)
        val windows = listOf(
            Window("15초", 15) { it.p15s }, Window("1분", 60) { it.p1m }, Window("2분", 120) { it.p2m },
            Window("5분", 300) { it.p5m }, Window("10분", 600) { it.p10m }, Window("20분", 1200) { it.p20m },
            Window("40분", 2400) { it.p40m }, Window("1시간", 3600) { it.p1h }, Window("2시간", 7200) { it.p2h },
            Window("4시간", 14400) { it.p4h }
        )
        return windows.mapNotNull { w ->
            val best = source.mapNotNull { ride -> w.value(ride.localPower)?.let { ride to it } }.maxByOrNull { it.second }
                ?: return@mapNotNull null
            StravaPrResult(w.label, w.seconds, best.second, best.first.id, best.first.name, best.first.dateText)
        }
    }

    private fun aggregateBins(source: List<StravaRideReview>): List<StravaGradeBin> {
        val ranges = listOf(-99.0 to -4.0, -4.0 to -1.0, -1.0 to 1.0, 1.0 to 3.0, 3.0 to 6.0, 6.0 to 99.0)
        return ranges.mapIndexed { index, range ->
            val matching = source.mapNotNull { it.gradeBins.getOrNull(index) }
            StravaGradeBin(
                range.first, range.second,
                matching.sumOf { it.seconds }, matching.sumOf { it.speedWeighted },
                matching.sumOf { it.powerSeconds }, matching.sumOf { it.powerWeighted }
            )
        }
    }
}

class StravaReviewStore(context: Context) {
    companion object {
        private const val PREFS = "strava_review_profile_v1"
        private const val CANDIDATE = "candidate_json"
        private const val ACTIVE = "active_json"
        private const val SCHEMA = 2
    }
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveCandidate(profile: StravaRiderReviewProfile) {
        prefs.edit().putString(CANDIDATE, toJson(profile.copy(linkedAtMs = 0L)).toString()).apply()
    }

    fun loadCandidate(): StravaRiderReviewProfile? =
        prefs.getString(CANDIDATE, null)?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() }

    fun discardCandidate() { prefs.edit().remove(CANDIDATE).apply() }

    fun selectCandidateYear(year: Int): StravaRiderReviewProfile {
        val c = loadCandidate() ?: error("먼저 Strava 기록을 분석해 주세요.")
        require(year in c.availableYears) { "선택할 수 없는 연도입니다." }
        val updated = c.copy(selectedYear = year)
        saveCandidate(updated)
        return updated
    }

    fun applyCandidate(): StravaRiderReviewProfile {
        val c = loadCandidate() ?: error("먼저 Strava 기록을 불러와 분석해 주세요.")
        require(c.scanComplete) { "전체 기록 분석이 아직 끝나지 않았습니다. '계속 분석'으로 완료해 주세요." }
        require(c.resolvedYear() > 0) { "연동할 연도를 선택해 주세요." }
        val linked = c.copy(selectedYear = c.resolvedYear(), linkedAtMs = System.currentTimeMillis())
        prefs.edit().putString(ACTIVE, toJson(linked).toString()).apply()
        return linked
    }

    fun loadActive(): StravaRiderReviewProfile? =
        prefs.getString(ACTIVE, null)?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() }

    fun clearActive() { prefs.edit().remove(ACTIVE).apply() }

    private fun toJson(p: StravaRiderReviewProfile): JSONObject = JSONObject().apply {
        put("schema", SCHEMA)
        put("athleteName", p.athleteName ?: JSONObject.NULL)
        put("analyzedAtMs", p.analyzedAtMs)
        put("linkedAtMs", p.linkedAtMs)
        put("totalRoadActivities", p.totalRoadActivities)
        put("scanComplete", p.scanComplete)
        put("stopReason", p.stopReason ?: JSONObject.NULL)
        put("selectedYear", p.resolvedYear())
        put("rides", JSONArray().apply {
            p.rides.forEach { r ->
                put(JSONObject().apply {
                    put("id", r.id); put("name", r.name); put("dateText", r.dateText)
                    put("distanceKm", r.distanceKm); put("ascentM", r.ascentM)
                    put("movingSec", r.movingSec); put("elapsedSec", r.elapsedSec)
                    put("totalStopSec", r.totalStopSec); put("longestStopSec", r.longestStopSec)
                    put("movingAvgKph", r.movingAvgKph); put("use", r.use.name); put("reason", r.reason)
                    put("hasPower", r.hasPower); put("streamAnalyzed", r.streamAnalyzed)
                    putNullable("avgPowerW", r.avgPowerW); putNullable("weightedPowerW", r.weightedPowerW)
                    putNullable("avgHeartRate", r.avgHeartRate); putNullable("avgCadence", r.avgCadence)
                    put("localPower", powerToJson(r.localPower))
                    put("gradeBins", binsToJson(r.gradeBins))
                })
            }
        })
    }

    private fun fromJson(o: JSONObject): StravaRiderReviewProfile {
        val schema = o.optInt("schema", 1)
        val ridesJ = o.optJSONArray("rides") ?: JSONArray()
        val rides = (0 until ridesJ.length()).mapNotNull { i ->
            val r = ridesJ.optJSONObject(i) ?: return@mapNotNull null
            StravaRideReview(
                id = r.optLong("id"), name = r.optString("name"), dateText = r.optString("dateText"),
                distanceKm = r.optDouble("distanceKm"), ascentM = r.optDouble("ascentM"),
                movingSec = r.optDouble("movingSec"), elapsedSec = r.optDouble("elapsedSec"),
                totalStopSec = r.optDouble("totalStopSec"), longestStopSec = r.optDouble("longestStopSec"),
                movingAvgKph = r.optDouble("movingAvgKph"),
                use = runCatching { StravaRideUse.valueOf(r.optString("use")) }.getOrDefault(StravaRideUse.PARTIAL),
                reason = r.optString("reason"), hasPower = r.optBoolean("hasPower"),
                avgPowerW = r.optNullableDouble("avgPowerW"), weightedPowerW = r.optNullableDouble("weightedPowerW"),
                avgHeartRate = r.optNullableDouble("avgHeartRate"), avgCadence = r.optNullableDouble("avgCadence"),
                localPower = r.optJSONObject("localPower")?.let { powerFromJson(it) } ?: StravaPowerCurve(),
                gradeBins = r.optJSONArray("gradeBins")?.let { binsFromJson(it) } ?: emptyList(),
                streamAnalyzed = if (schema >= 2) r.optBoolean("streamAnalyzed", true) else false
            )
        }
        val years = rides.map { it.year }.filter { it > 0 }.distinct().sortedDescending()
        val selected = o.optInt("selectedYear", 0).takeIf { it in years } ?: (years.firstOrNull() ?: 0)
        return StravaRiderReviewProfile(
            athleteName = o.optString("athleteName").takeIf { it.isNotBlank() },
            analyzedAtMs = o.optLong("analyzedAtMs"),
            rides = rides,
            totalRoadActivities = if (schema >= 2) o.optInt("totalRoadActivities", rides.size) else rides.size,
            scanComplete = if (schema >= 2) o.optBoolean("scanComplete", false) else false,
            stopReason = if (o.has("stopReason") && !o.isNull("stopReason")) o.optString("stopReason") else null,
            selectedYear = selected,
            linkedAtMs = o.optLong("linkedAtMs")
        )
    }

    private fun powerToJson(p: StravaPowerCurve): JSONObject = JSONObject().apply {
        putNullable("p15s", p.p15s); putNullable("p1m", p.p1m); putNullable("p2m", p.p2m)
        putNullable("p5m", p.p5m); putNullable("p10m", p.p10m); putNullable("p20m", p.p20m)
        putNullable("p40m", p.p40m); putNullable("p1h", p.p1h); putNullable("p2h", p.p2h); putNullable("p4h", p.p4h)
    }

    private fun powerFromJson(p: JSONObject) = StravaPowerCurve(
        p.optNullableDouble("p15s"), p.optNullableDouble("p1m"), p.optNullableDouble("p2m"),
        p.optNullableDouble("p5m"), p.optNullableDouble("p10m"), p.optNullableDouble("p20m"),
        p.optNullableDouble("p40m"), p.optNullableDouble("p1h"), p.optNullableDouble("p2h"), p.optNullableDouble("p4h")
    )

    private fun binsToJson(bins: List<StravaGradeBin>) = JSONArray().apply {
        bins.forEach { b ->
            put(JSONObject().put("min", b.minGrade).put("max", b.maxGrade).put("seconds", b.seconds)
                .put("speedWeighted", b.speedWeighted).put("powerSeconds", b.powerSeconds).put("powerWeighted", b.powerWeighted))
        }
    }

    private fun binsFromJson(a: JSONArray): List<StravaGradeBin> = (0 until a.length()).mapNotNull { i ->
        a.optJSONObject(i)?.let { b ->
            StravaGradeBin(b.optDouble("min"), b.optDouble("max"), b.optDouble("seconds"),
                b.optDouble("speedWeighted"), b.optDouble("powerSeconds"), b.optDouble("powerWeighted"))
        }
    }

    private fun JSONObject.putNullable(key: String, v: Double?) { put(key, v ?: JSONObject.NULL) }
    private fun JSONObject.optNullableDouble(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key).takeIf { it.isFinite() }
}

object StravaRoadReviewAnalyzer {
    private const val PER_PAGE = 100
    private val GRADE_BINS = listOf(-99.0 to -4.0, -4.0 to -1.0, -1.0 to 1.0, 1.0 to 3.0, 3.0 to 6.0, 6.0 to 99.0)
    private val POWER_WINDOWS = intArrayOf(15, 60, 120, 300, 600, 1200, 2400, 3600, 7200, 14400)

    private data class Activity(
        val id: Long, val name: String, val dateText: String,
        val distanceM: Double, val movingSec: Double, val elapsedSec: Double, val ascentM: Double,
        val trainer: Boolean, val manual: Boolean,
        val avgPowerW: Double?, val weightedPowerW: Double?, val avgHr: Double?, val avgCadence: Double?
    )

    private data class Streams(
        val time: IntArray, val distance: DoubleArray?, val velocity: DoubleArray?,
        val grade: DoubleArray?, val altitude: DoubleArray?, val moving: BooleanArray?,
        val watts: DoubleArray?, val heartrate: DoubleArray?, val cadence: DoubleArray?
    )

    data class StopMetrics(val totalStopSec: Double, val longestStopSec: Double)

    fun analyze(
        accessToken: String,
        athleteName: String?,
        existing: StravaRiderReviewProfile? = null,
        progress: ((done: Int, total: Int, name: String) -> Unit)? = null
    ): StravaRiderReviewProfile {
        progress?.invoke(0, 0, "전체 ROAD 활동 목록 불러오는 중")
        val activities = listRoadActivities(accessToken)
        require(activities.isNotEmpty()) { "Strava에서 ROAD sport_type=Ride 기록을 찾지 못했습니다." }

        val reusable = existing?.takeIf { it.athleteName == null || athleteName == null || it.athleteName == athleteName }
            ?.rides?.filter { it.streamAnalyzed }?.associateBy { it.id }.orEmpty()
        val reviews = mutableListOf<StravaRideReview>()
        var stopReason: String? = null

        for ((index, activity) in activities.withIndex()) {
            val cached = reusable[activity.id]
            if (cached != null) {
                reviews += cached
                progress?.invoke(index + 1, activities.size, "캐시 · ${activity.name}")
                continue
            }
            if (activity.trainer || activity.manual) {
                val reason = if (activity.trainer) "실내/트레이너 · ROAD 모델 제외" else "수동 활동 · 실제 스트림 검증 불가"
                reviews += reviewSummaryExcluded(activity, reason)
                progress?.invoke(index + 1, activities.size, "제외 · ${activity.name}")
                continue
            }

            progress?.invoke(index, activities.size, activity.name)
            val streamResult = runCatching { getStreams(accessToken, activity.id) }
            if (streamResult.isFailure) {
                val msg = streamResult.exceptionOrNull()?.message.orEmpty()
                if (msg.contains("HTTP 429")) {
                    stopReason = "Strava API 요청 한도 도달 · 다음에 '계속 분석'을 누르면 여기서 이어집니다."
                    progress?.invoke(index, activities.size, "API 요청 한도 도달 · 분석 저장")
                    break
                }
                reviews += reviewWithoutStreams(activity, msg)
                progress?.invoke(index + 1, activities.size, "제외 · ${activity.name}")
                continue
            }

            val s = streamResult.getOrThrow()
            val stop = stopMetrics(s)
            val totalStop = max((activity.elapsedSec - activity.movingSec).coerceAtLeast(0.0), stop.totalStopSec)
            val longestStop = stop.longestStopSec
            val useAndReason = classify(activity, totalStop, longestStop, s)
            val movingAvg = if (activity.movingSec > 0.0) activity.distanceM / 1000.0 / (activity.movingSec / 3600.0) else 0.0
            val hasPower = s.watts?.any { it.isFinite() && it > 0.0 } == true || activity.avgPowerW != null
            val bins = if (useAndReason.first != StravaRideUse.EXCLUDED) roadBins(s) else emptyList()
            val localPower = if (useAndReason.first != StravaRideUse.EXCLUDED) {
                s.watts?.let { watts ->
                    val raw = bestPower(s.time, watts)
                    fun p(i: Int): Double? {
                        if (POWER_WINDOWS[i] >= 3600 && useAndReason.first != StravaRideUse.ENDURANCE) return null
                        return raw[i]?.takeIf { it >= 20.0 }
                    }
                    StravaPowerCurve(p(0), p(1), p(2), p(3), p(4), p(5), p(6), p(7), p(8), p(9))
                } ?: StravaPowerCurve()
            } else StravaPowerCurve()

            reviews += StravaRideReview(
                id = activity.id, name = activity.name, dateText = activity.dateText,
                distanceKm = activity.distanceM / 1000.0, ascentM = activity.ascentM,
                movingSec = activity.movingSec, elapsedSec = activity.elapsedSec,
                totalStopSec = totalStop, longestStopSec = longestStop, movingAvgKph = movingAvg,
                use = useAndReason.first, reason = useAndReason.second, hasPower = hasPower,
                avgPowerW = activity.avgPowerW, weightedPowerW = activity.weightedPowerW,
                avgHeartRate = activity.avgHr, avgCadence = activity.avgCadence,
                localPower = localPower, gradeBins = bins, streamAnalyzed = true
            )
            progress?.invoke(index + 1, activities.size, activity.name)
        }

        val complete = reviews.size == activities.size
        val years = reviews.map { it.year }.filter { it > 0 }.distinct().sortedDescending()
        val previousYear = existing?.resolvedYear()?.takeIf { it in years }
        val selectedYear = previousYear ?: (years.firstOrNull() ?: 0)
        return StravaRiderReviewProfile(
            athleteName = athleteName ?: existing?.athleteName,
            analyzedAtMs = System.currentTimeMillis(),
            rides = reviews,
            totalRoadActivities = activities.size,
            scanComplete = complete,
            stopReason = if (complete) null else stopReason ?: "전체 분석이 아직 완료되지 않았습니다.",
            selectedYear = selectedYear
        )
    }

    fun classifyForTest(
        distanceKm: Double, movingSec: Double, elapsedSec: Double,
        longestStopSec: Double, trainer: Boolean = false, manual: Boolean = false
    ): StravaRideUse {
        val a = Activity(1L, "test", "", distanceKm * 1000, movingSec, elapsedSec, 0.0, trainer, manual, null, null, null, null)
        return classify(a, (elapsedSec - movingSec).coerceAtLeast(0.0), longestStopSec,
            Streams(intArrayOf(0, 1), null, null, null, null, booleanArrayOf(true, true), null, null, null)).first
    }

    private fun classify(a: Activity, totalStop: Double, longestStop: Double, s: Streams): Pair<StravaRideUse, String> {
        if (a.trainer) return StravaRideUse.EXCLUDED to "실내/트레이너 · ROAD 모델 제외"
        if (a.manual) return StravaRideUse.EXCLUDED to "수동 활동 · 실제 스트림 검증 불가"
        if (s.time.size < 2) return StravaRideUse.EXCLUDED to "활동 스트림 부족"
        val stopRatio = if (a.elapsedSec > 0.0) totalStop / a.elapsedSec else 0.0
        val longRest = longestStop >= 15 * 60.0 || totalStop >= 30 * 60.0 || stopRatio >= 0.20
        if (longRest) return StravaRideUse.PARTIAL to "긴 휴식 · 장거리 지속능력 제외, 움직인 구간/파워만 사용"
        val endurance = a.distanceM >= 50_000.0 && a.movingSec >= 2 * 3600.0 &&
            longestStop < 10 * 60.0 && totalStop < 25 * 60.0 && stopRatio < 0.15
        if (endurance) return StravaRideUse.ENDURANCE to "연속 장거리 · 지속능력/구간/파워 사용"
        return StravaRideUse.PARTIAL to "일반 ROAD · 구간/파워 사용, 장거리 지속능력에는 미사용"
    }

    private fun reviewSummaryExcluded(a: Activity, reason: String): StravaRideReview {
        val stop = (a.elapsedSec - a.movingSec).coerceAtLeast(0.0)
        return StravaRideReview(
            a.id, a.name, a.dateText, a.distanceM / 1000.0, a.ascentM, a.movingSec, a.elapsedSec, stop, 0.0,
            if (a.movingSec > 0) a.distanceM / 1000.0 / (a.movingSec / 3600.0) else 0.0,
            StravaRideUse.EXCLUDED, reason, a.avgPowerW != null, a.avgPowerW, a.weightedPowerW, a.avgHr, a.avgCadence,
            streamAnalyzed = true
        )
    }

    private fun reviewWithoutStreams(a: Activity, detail: String): StravaRideReview {
        val stop = (a.elapsedSec - a.movingSec).coerceAtLeast(0.0)
        return StravaRideReview(
            a.id, a.name, a.dateText, a.distanceM / 1000.0, a.ascentM, a.movingSec, a.elapsedSec, stop, 0.0,
            if (a.movingSec > 0) a.distanceM / 1000.0 / (a.movingSec / 3600.0) else 0.0,
            StravaRideUse.EXCLUDED,
            "스트림을 읽지 못해 연동 자료에서 제외${detail.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}",
            a.avgPowerW != null, a.avgPowerW, a.weightedPowerW, a.avgHr, a.avgCadence,
            streamAnalyzed = true
        )
    }

    private fun listRoadActivities(token: String): List<Activity> {
        val out = mutableListOf<Activity>()
        var page = 1
        while (true) {
            val arr = requestArray("https://www.strava.com/api/v3/athlete/activities?page=$page&per_page=$PER_PAGE", token)
            if (arr.length() == 0) break
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                // 정확히 Ride만: MTB/Gravel/e-bike/VirtualRide는 자동 제외.
                if (o.optString("sport_type") != "Ride") continue
                val id = o.optLong("id", 0L)
                if (id <= 0) continue
                out += Activity(
                    id = id, name = o.optString("name").ifBlank { "Ride $id" },
                    dateText = o.optString("start_date_local").take(10),
                    distanceM = o.optDouble("distance", 0.0),
                    movingSec = o.optDouble("moving_time", 0.0),
                    elapsedSec = o.optDouble("elapsed_time", o.optDouble("moving_time", 0.0)),
                    ascentM = o.optDouble("total_elevation_gain", 0.0),
                    trainer = o.optBoolean("trainer", false), manual = o.optBoolean("manual", false),
                    avgPowerW = o.optNullablePositive("average_watts"),
                    weightedPowerW = o.optNullablePositive("weighted_average_watts"),
                    avgHr = o.optNullablePositive("average_heartrate"),
                    avgCadence = o.optNullablePositive("average_cadence")
                )
            }
            if (arr.length() < PER_PAGE) break
            page += 1
            require(page <= 5000) { "활동 목록이 비정상적으로 많아 안전 한도에서 중단했습니다." }
        }
        return out
    }

    private fun getStreams(token: String, id: Long): Streams {
        val keys = "time,distance,velocity_smooth,grade_smooth,altitude,moving,watts,heartrate,cadence"
        val o = requestObject("https://www.strava.com/api/v3/activities/$id/streams?keys=$keys&key_by_type=true", token)
        val time = o.streamInts("time") ?: error("time stream 없음")
        return Streams(time, o.streamDoubles("distance"), o.streamDoubles("velocity_smooth"), o.streamDoubles("grade_smooth"),
            o.streamDoubles("altitude"), o.streamBooleans("moving"), o.streamDoubles("watts"), o.streamDoubles("heartrate"), o.streamDoubles("cadence"))
    }

    private fun stopMetrics(s: Streams): StopMetrics {
        if (s.time.size < 2) return StopMetrics(0.0, 0.0)
        var total = 0.0
        var current = 0.0
        var longest = 0.0
        for (i in 1 until s.time.size) {
            val dt = (s.time[i] - s.time[i - 1]).toDouble()
            if (dt <= 0.0 || dt > 3600.0) continue
            val dk = s.distance?.takeIf { i < it.size }?.let { it[i] - it[i - 1] }
            val speed = s.velocity?.takeIf { i < it.size }?.let { ((it[i] + it[i - 1]) * 0.5 * 3.6) }
                ?: if (dk != null && dk >= 0) dk / dt * 3.6 else null
            val movingFlag = s.moving?.takeIf { i < it.size }?.get(i)
            val stopped = movingFlag == false || (speed != null && speed < 1.5 && (dk ?: 0.0) < 3.0)
            if (stopped) {
                current += dt
                total += dt
                if (current > longest) longest = current
            } else current = 0.0
        }
        return StopMetrics(total, longest)
    }

    private fun roadBins(s: Streams): List<StravaGradeBin> {
        val bins = GRADE_BINS.map { (a, b) -> StravaGradeBin(a, b, 0.0, 0.0, 0.0, 0.0) }.toMutableList()
        for (i in 1 until s.time.size) {
            val dt = (s.time[i] - s.time[i - 1]).toDouble()
            if (dt !in 0.2..30.0) continue
            if (s.moving != null && i < s.moving.size && !s.moving[i]) continue
            val dk = s.distance?.takeIf { i < it.size }?.let { it[i] - it[i - 1] } ?: Double.NaN
            val speed = when {
                s.velocity != null && i < s.velocity.size -> (s.velocity[i - 1] + s.velocity[i]) * 0.5 * 3.6
                dk.isFinite() && dk > 0 -> dk / dt * 3.6
                else -> Double.NaN
            }
            if (!speed.isFinite() || speed !in 2.0..100.0) continue
            val grade = when {
                s.grade != null && i < s.grade.size -> ((s.grade[i - 1] + s.grade[i]) * 0.5).coerceIn(-20.0, 25.0)
                s.altitude != null && i < s.altitude.size && dk.isFinite() && dk > 5 -> ((s.altitude[i] - s.altitude[i - 1]) / dk * 100).coerceIn(-20.0, 25.0)
                else -> 0.0
            }
            val idx = GRADE_BINS.indexOfFirst { grade >= it.first && grade < it.second }.coerceAtLeast(0)
            val old = bins[idx]
            val power = s.watts?.takeIf { i < it.size }?.let { ((it[i - 1] + it[i]) * 0.5).takeIf { w -> w in 0.0..2000.0 } }
            bins[idx] = old.copy(
                seconds = old.seconds + dt, speedWeighted = old.speedWeighted + speed * dt,
                powerSeconds = old.powerSeconds + (if (power != null) dt else 0.0),
                powerWeighted = old.powerWeighted + (power ?: 0.0) * dt
            )
        }
        return bins
    }

    private fun bestPower(time: IntArray, watts: DoubleArray): Array<Double?> {
        val n = min(time.size, watts.size)
        if (n < 2) return Array(POWER_WINDOWS.size) { null }
        val prefix = DoubleArray(n)
        for (i in 1 until n) {
            val dt = (time[i] - time[i - 1]).coerceAtLeast(0)
            val p = if (dt in 1..15) ((watts[i - 1] + watts[i]) * 0.5).coerceIn(0.0, 2500.0) else 0.0
            prefix[i] = prefix[i - 1] + p * dt
        }
        fun energyAt(t: Double): Double {
            if (t <= time[0]) return 0.0
            if (t >= time[n - 1]) return prefix[n - 1]
            var lo = 0
            var hi = n - 1
            while (lo + 1 < hi) {
                val mid = (lo + hi) ushr 1
                if (time[mid] <= t) lo = mid else hi = mid
            }
            val gap = time[hi] - time[lo]
            val p = if (gap in 1..15) ((watts[lo] + watts[hi]) * 0.5).coerceIn(0.0, 2500.0) else 0.0
            return prefix[lo] + p * (t - time[lo])
        }
        return Array(POWER_WINDOWS.size) { wi ->
            val w = POWER_WINDOWS[wi].toDouble()
            var best = 0.0
            for (i in 1 until n) {
                val end = time[i].toDouble()
                if (end - time[0] < w) continue
                val avg = (prefix[i] - energyAt(end - w)) / w
                if (avg.isFinite() && avg > best) best = avg
            }
            best.takeIf { it > 0 }
        }
    }

    private fun requestObject(url: String, token: String) = JSONObject(requestText(url, token))
    private fun requestArray(url: String, token: String) = JSONArray(requestText(url, token))
    private fun requestText(url: String, token: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 25000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.use { BufferedReader(InputStreamReader(it)).readText() }.orEmpty()
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty()
            error("Strava HTTP $code${if (msg.isNotBlank()) " · $msg" else ""}")
        }
        return text
    }

    private fun JSONObject.streamDoubles(key: String): DoubleArray? {
        val a = optJSONObject(key)?.optJSONArray("data") ?: return null
        return DoubleArray(a.length()) { i -> a.optDouble(i, Double.NaN) }
    }
    private fun JSONObject.streamInts(key: String): IntArray? {
        val a = optJSONObject(key)?.optJSONArray("data") ?: return null
        return IntArray(a.length()) { i -> a.optInt(i, 0) }
    }
    private fun JSONObject.streamBooleans(key: String): BooleanArray? {
        val a = optJSONObject(key)?.optJSONArray("data") ?: return null
        return BooleanArray(a.length()) { i -> a.optBoolean(i, true) }
    }
    private fun JSONObject.optNullablePositive(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key).takeIf { it.isFinite() && it > 0 }
}
