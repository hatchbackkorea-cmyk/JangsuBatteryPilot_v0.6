package com.seungjae.jangsu280battery

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Read-only public course discovery used after an event has released its course. */
class PublicCourseClient(context: Context) {
    private val app = context.applicationContext
    private val raceClient = RaceServerClient(app)

    data class NearbyCourse(
        val serverCourseId: Long,
        val name: String,
        val distanceKm: Double,
        val startLat: Double,
        val startLon: Double,
        val distanceFromUserM: Double,
        val sha256: String,
        val eventNames: List<String>
    ) {
        fun toJson() = JSONObject().apply {
            put("course_id", serverCourseId); put("name", name); put("distance_km", distanceKm)
            put("start_lat", startLat); put("start_lon", startLon); put("distance_from_user_m", distanceFromUserM)
            put("sha256", sha256); put("event_names", JSONArray().apply { eventNames.forEach(::put) })
        }

        companion object {
            fun fromJson(o: JSONObject): NearbyCourse {
                val events = o.optJSONArray("event_names") ?: JSONArray()
                return NearbyCourse(
                    o.optLong("course_id"), o.optString("name", "공개 코스"), o.optDouble("distance_km", 0.0),
                    o.optDouble("start_lat", 0.0), o.optDouble("start_lon", 0.0), o.optDouble("distance_from_user_m", 0.0),
                    o.optString("sha256", ""), (0 until events.length()).map { events.optString(it) }.filter { it.isNotBlank() }
                )
            }
        }
    }

    fun available(): Boolean = raceClient.baseUrl().startsWith("http")

    fun nearby(lat: Double, lon: Double, radiusKm: Double = 10.0): List<NearbyCourse> {
        val base = raceClient.baseUrl().trim().trimEnd('/')
        require(base.startsWith("http")) { "Rider Control Center 서버가 연결되지 않았습니다." }
        val r = radiusKm.coerceIn(1.0, 50.0)
        val path = "/api/public/race-courses/nearby?lat=$lat&lon=$lon&radius_km=$r"
        val conn = URL(base + path).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"; conn.connectTimeout = 5000; conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("근처 코스 조회 실패 · HTTP $code")
            val a = JSONObject(text).optJSONArray("courses") ?: JSONArray()
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(NearbyCourse::fromJson) }
        } finally { conn.disconnect() }
    }

    fun download(course: NearbyCourse): File {
        val base = raceClient.baseUrl().trim().trimEnd('/')
        require(base.startsWith("http")) { "Rider Control Center 서버가 연결되지 않았습니다." }
        val id = URLEncoder.encode(course.serverCourseId.toString(), "UTF-8")
        val conn = URL("$base/api/public/race-courses/$id/gpx").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"; conn.connectTimeout = 8000; conn.readTimeout = 20000
            val code = conn.responseCode
            if (code !in 200..299) error("공개 GPX 다운로드 실패 · HTTP $code")
            val target = File(app.cacheDir, "public_course_${course.serverCourseId}_${System.currentTimeMillis()}.gpx")
            conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
            target
        } finally { conn.disconnect() }
    }
}