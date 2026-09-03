package com.seungjae.jangsu280battery

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class RoomCourseDescriptor(
    val id: Int,
    val name: String,
    val fileName: String,
    val sha256: String,
    val downloadUrl: String,
    val distanceKm: Double
) {
    val fingerprint: String get() = if (sha256.isNotBlank()) sha256 else "course:$id"
}

/** Downloads the GPX assigned to a group room using the existing device token. */
class RoomCourseAutoSync(private val context: Context) {
    fun descriptor(snapshot: JSONObject): RoomCourseDescriptor? {
        val c = snapshot.optJSONObject("course") ?: return null
        val id = c.optInt("id", 0)
        val path = c.optString("downloadUrl", "")
        if (id <= 0 || path.isBlank()) return null
        return RoomCourseDescriptor(
            id = id,
            name = c.optString("name", "그룹방 GPX"),
            fileName = c.optString("fileName", "room_course.gpx"),
            sha256 = c.optString("sha256", ""),
            downloadUrl = path,
            distanceKm = c.optDouble("distanceKm", 0.0)
        )
    }

    fun download(baseUrl: String, token: String, room: String, desc: RoomCourseDescriptor): File {
        val root = baseUrl.trim().trimEnd('/')
        require(root.startsWith("http")) { "Rider Control Center 주소가 없습니다." }
        require(token.isNotBlank()) { "기기 연결 토큰이 없습니다." }
        val full = if (desc.downloadUrl.startsWith("http")) desc.downloadUrl else root + desc.downloadUrl
        val conn = URL(full).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 8_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/gpx+xml, application/xml, application/octet-stream")
        val code = conn.responseCode
        if (code !in 200..299) {
            val msg = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
            conn.disconnect()
            error("그룹방 GPX 다운로드 실패 HTTP $code ${msg.take(100)}")
        }
        val out = File(context.cacheDir, "room_${room}_${desc.fingerprint.take(16)}.gpx")
        conn.inputStream.use { input -> out.outputStream().use { output -> input.copyTo(output) } }
        conn.disconnect()
        require(out.length() > 0) { "그룹방 GPX가 비어 있습니다." }
        return out
    }
}
