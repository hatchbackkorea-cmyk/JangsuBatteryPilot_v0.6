package com.seungjae.jangsu280battery

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.min

/**
 * Strava의 sport_type=Ride 기록만 읽어 ROAD 능력 프로필을 만든다.
 * MTB / Gravel / e-bike / VirtualRide는 섞지 않는다.
 */
data class StravaRoadAnalysisResult(
    val activityCount: Int,
    val totalDistanceKm: Double,
    val totalMovingSec: Double,
    val bins: List<RoadGradeBin>,
    val activityNames: List<String>,
    val power: RoadPowerProfile,
    val analyzedAtMs: Long
)

object StravaRoadAnalyzer {
    private const val MAX_ROAD_ACTIVITIES = 60
    private const val MAX_ACTIVITY_PAGES = 10
    private const val PER_PAGE = 100
    private val gradeBins = listOf(-99.0 to -4.0, -4.0 to -1.0, -1.0 to 1.0, 1.0 to 3.0, 3.0 to 6.0, 6.0 to 99.0)
    private val powerWindows = intArrayOf(5, 30, 60, 300, 1200, 3600)

    private data class Activity(
        val id: Long,
        val name: String,
        val distanceM: Double,
        val movingTimeSec: Double,
        val trainer: Boolean
    )

    private data class Streams(
        val time: IntArray,
        val watts: DoubleArray?,
        val distance: DoubleArray?,
        val altitude: DoubleArray?,
        val velocity: DoubleArray?,
        val grade: DoubleArray?,
        val moving: BooleanArray?
    )

    fun analyze(accessToken: String, progress: ((done: Int, total: Int, name: String) -> Unit)? = null): StravaRoadAnalysisResult {
        val activities = listRoadActivities(accessToken)
        require(activities.isNotEmpty()) { "Strava에서 sport_type=Ride 로드 기록을 찾지 못했습니다." }

        val bins = gradeBins.map { (a, b) -> RoadGradeBin(a, b, 0.0, 0.0, 0.0, 0.0) }.toMutableList()
        val best = DoubleArray(powerWindows.size)
        val names = mutableListOf<String>()
        var analyzed = 0
        var totalDistanceKm = 0.0
        var totalMovingSec = 0.0

        for ((index, activity) in activities.withIndex()) {
            progress?.invoke(index, activities.size, activity.name)
            val streams = try {
                getStreams(accessToken, activity.id)
            } catch (error: Throwable) {
                // API rate limit이면 같은 요청을 계속 반복하지 않는다.
                if (error.message.orEmpty().contains("HTTP 429")) break
                continue
            }
            analyzed++
            names += "Strava · ${activity.name}"

            streams.watts?.let { watts ->
                val pr = bestPower(streams.time, watts)
                for (i in best.indices) best[i] = max(best[i], pr[i] ?: 0.0)
            }

            // 실내 트레이너 Ride는 파워 PR에는 쓰되 도로 속도/경사 모델에는 섞지 않는다.
            if (!activity.trainer) {
                accumulateRoadBins(streams, bins)
                totalDistanceKm += (activity.distanceM / 1000.0).coerceAtLeast(0.0)
                totalMovingSec += activity.movingTimeSec.coerceAtLeast(0.0)
            }
        }
        progress?.invoke(analyzed, activities.size, "완료")
        require(analyzed > 0) { "Strava ROAD 스트림을 읽지 못했습니다. 연결 권한을 다시 확인해 주세요." }

        fun pr(i: Int) = best[i].takeIf { it >= 20.0 }
        return StravaRoadAnalysisResult(
            activityCount = analyzed,
            totalDistanceKm = totalDistanceKm,
            totalMovingSec = totalMovingSec,
            bins = bins,
            activityNames = names.takeLast(12),
            power = RoadPowerProfile(
                fiveSecondW = pr(0),
                thirtySecondW = pr(1),
                oneMinuteW = pr(2),
                fiveMinuteW = pr(3),
                twentyMinuteW = pr(4),
                sixtyMinuteW = pr(5)
            ),
            analyzedAtMs = System.currentTimeMillis()
        )
    }

    private fun listRoadActivities(token: String): List<Activity> {
        val out = mutableListOf<Activity>()
        for (page in 1..MAX_ACTIVITY_PAGES) {
            val url = "https://www.strava.com/api/v3/athlete/activities?page=$page&per_page=$PER_PAGE"
            val arr = requestArray(url, token)
            if (arr.length() == 0) break
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                // 정확히 Ride만. MountainBikeRide / GravelRide / EBikeRide / EMountainBikeRide / VirtualRide 제외.
                if (o.optString("sport_type") != "Ride") continue
                val id = o.optLong("id", 0L)
                if (id <= 0L) continue
                out += Activity(
                    id = id,
                    name = o.optString("name").ifBlank { "Ride $id" },
                    distanceM = o.optDouble("distance", 0.0),
                    movingTimeSec = o.optDouble("moving_time", 0.0),
                    trainer = o.optBoolean("trainer", false)
                )
                if (out.size >= MAX_ROAD_ACTIVITIES) return out
            }
            if (arr.length() < PER_PAGE) break
        }
        return out
    }

    private fun getStreams(token: String, activityId: Long): Streams {
        val keys = "time,watts,distance,altitude,velocity_smooth,grade_smooth,moving"
        val json = requestObject("https://www.strava.com/api/v3/activities/$activityId/streams?keys=$keys&key_by_type=true", token)
        val time = json.streamInts("time") ?: error("time stream 없음")
        return Streams(
            time = time,
            watts = json.streamDoubles("watts"),
            distance = json.streamDoubles("distance"),
            altitude = json.streamDoubles("altitude"),
            velocity = json.streamDoubles("velocity_smooth"),
            grade = json.streamDoubles("grade_smooth"),
            moving = json.streamBooleans("moving")
        )
    }

    private fun accumulateRoadBins(s: Streams, bins: MutableList<RoadGradeBin>) {
        val n = s.time.size
        if (n < 2) return
        for (i in 1 until n) {
            val dt = (s.time[i] - s.time[i - 1]).toDouble()
            if (dt !in 0.2..30.0) continue
            if (s.moving != null && i < s.moving.size && !s.moving[i]) continue

            val dkM = if (s.distance != null && i < s.distance.size) s.distance[i] - s.distance[i - 1] else Double.NaN
            val speedKph = when {
                s.velocity != null && i < s.velocity.size -> ((s.velocity[i - 1] + s.velocity[i]) * 0.5 * 3.6)
                dkM.isFinite() && dkM > 0.0 -> dkM / dt * 3.6
                else -> Double.NaN
            }
            if (!speedKph.isFinite() || speedKph !in 2.0..100.0) continue

            val grade = when {
                s.grade != null && i < s.grade.size -> ((s.grade[i - 1] + s.grade[i]) * 0.5).coerceIn(-20.0, 25.0)
                s.altitude != null && i < s.altitude.size && dkM.isFinite() && dkM > 5.0 -> ((s.altitude[i] - s.altitude[i - 1]) / dkM * 100.0).coerceIn(-20.0, 25.0)
                else -> 0.0
            }
            val idx = gradeBins.indexOfFirst { grade >= it.first && grade < it.second }.coerceAtLeast(0)
            val old = bins[idx]
            val power = s.watts?.takeIf { i < it.size }?.let { ((it[i - 1] + it[i]) * 0.5).takeIf { w -> w in 0.0..2000.0 } }
            bins[idx] = old.copy(
                seconds = old.seconds + dt,
                speedWeighted = old.speedWeighted + speedKph * dt,
                powerSeconds = old.powerSeconds + if (power != null) dt else 0.0,
                powerWeighted = old.powerWeighted + (power ?: 0.0) * dt
            )
        }
    }

    /** 시간 스트림 간격을 반영한 time-weighted rolling average. */
    private fun bestPower(time: IntArray, watts: DoubleArray): Array<Double?> {
        val n = min(time.size, watts.size)
        if (n < 2) return Array(powerWindows.size) { null }
        val prefix = DoubleArray(n)
        for (i in 1 until n) {
            val dt = (time[i] - time[i - 1]).coerceAtLeast(0)
            val p = if (dt in 1..10) ((watts[i - 1] + watts[i]) * 0.5).coerceIn(0.0, 2500.0) else 0.0
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
            val p = if (gap in 1..10) ((watts[lo] + watts[hi]) * 0.5).coerceIn(0.0, 2500.0) else 0.0
            return prefix[lo] + p * (t - time[lo])
        }

        return Array(powerWindows.size) { wi ->
            val window = powerWindows[wi].toDouble()
            var best = 0.0
            for (i in 1 until n) {
                val end = time[i].toDouble()
                if (end - time[0] < window) continue
                val avg = (prefix[i] - energyAt(end - window)) / window
                if (avg.isFinite() && avg > best) best = avg
            }
            best.takeIf { it > 0.0 }
        }
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

    private fun requestObject(url: String, token: String): JSONObject = JSONObject(requestText(url, token))
    private fun requestArray(url: String, token: String): JSONArray = JSONArray(requestText(url, token))

    private fun requestText(url: String, token: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.use { BufferedReader(InputStreamReader(it)).readText() }.orEmpty()
        if (code !in 200..299) {
            val message = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty()
            error("Strava HTTP $code${if (message.isNotBlank()) " · $message" else ""}")
        }
        return text
    }
}
