package com.seungjae.jangsu280battery

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Administrator-phone publish/sync path for locally created RACE courses + trap geometry. */
class RaceCoursePublisher(private val sync: RiderServerSync) {
    data class Result(
        val ok: Boolean,
        val message: String,
        val serverCourseId: Long? = null,
        val raceReady: Boolean = false
    )

    fun publishAsync(meta: CourseMeta, file: File, gates: List<RaceGate>, callback: (Result) -> Unit) {
        publishAsync(meta.id, meta.name, file, gates, callback)
    }

    fun publishDraftAsync(clientKey: String, name: String, file: File, gates: List<RaceGate>, callback: (Result) -> Unit) {
        publishAsync(clientKey, name, file, gates, callback)
    }

    private fun publishAsync(clientKey: String, name: String, file: File, gates: List<RaceGate>, callback: (Result) -> Unit) {
        Thread {
            val result = runCatching {
                require(sync.configured()) { "Rider Control Center 서버 연결이 필요합니다." }
                require(sync.isAdminDeviceCached()) { "관리자 핸드폰에서만 서버에 코스를 등록할 수 있습니다." }
                require(file.exists() && file.length() > 100L) { "저장된 GPX 파일이 없습니다." }
                val x = multipart(
                    "/api/mobile/admin/race-courses/upload",
                    mapOf(
                        "client_key" to clientKey,
                        "name" to name,
                        "traps_json" to JSONArray().apply { gates.sortedBy { it.routeM }.forEach { put(it.toJson()) } }.toString()
                    ),
                    file
                )
                val ready = x.optBoolean("race_ready", gates.any { it.type == "START" } && gates.any { it.type == "FINISH" })
                Result(
                    true,
                    if (ready) "서버 코스 등록 완료 · $name" else "PC 동기화 완료 · START/FINISH 편집 가능 · $name",
                    x.optLong("course_id").takeIf { it > 0L },
                    ready
                )
            }.getOrElse { Result(false, "서버 등록 실패 · ${it.message ?: "연결 확인"}") }
            callback(result)
        }.start()
    }

    private fun multipart(path: String, fields: Map<String, String>, file: File): JSONObject {
        val boundary = "----RACE${UUID.randomUUID()}"
        val c = (URL(sync.serverUrl().trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 10_000; readTimeout = 30_000; doOutput = true
            setRequestProperty("Authorization", "Bearer ${sync.token()}")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        DataOutputStream(c.outputStream).use { out ->
            fun text(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
            fields.forEach { (k, v) ->
                text("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
            }
            text("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${file.name.replace("\"", "")}\"\r\nContent-Type: application/gpx+xml\r\n\r\n")
            file.inputStream().use { it.copyTo(out) }
            text("\r\n--$boundary--\r\n")
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val body = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() } }.orEmpty()
        c.disconnect()
        if (code !in 200..299) error("HTTP $code · ${body.take(220)}")
        return if (body.isBlank()) JSONObject() else JSONObject(body)
    }
}
