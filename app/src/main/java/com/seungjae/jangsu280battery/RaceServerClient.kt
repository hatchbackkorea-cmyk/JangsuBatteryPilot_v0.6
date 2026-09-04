package com.seungjae.jangsu280battery

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Small cross-platform-compatible RACE HTTP client. Live state is ephemeral; sector/finish are durable-queued. */
class RaceServerClient(context: Context) {
    private val app = context.applicationContext
    private val sync = RiderServerSync(app)
    private val store = RaceDataStore(app)
    data class JoinResult(val config: RaceEventConfig, val participantToken: String)

    fun baseUrl(): String = sync.serverUrl().trim().trimEnd('/')
    fun available(): Boolean = baseUrl().startsWith("http://") || baseUrl().startsWith("https://")

    fun join(eventCode: String, profile: RaceProfileStore.Profile): JoinResult {
        require(available()) { "Rider Control Center 서버가 연결되지 않았습니다." }
        val body = JSONObject().apply {
            put("event_code", eventCode.trim().uppercase())
            put("profile_id", profile.profileId); put("name", profile.name); put("nickname", profile.nickname)
            put("platform", "ANDROID"); put("app_version", UpdateManager.currentVersion(app))
        }
        val x = request("POST", "/api/race/join", body, null)
        val event = RaceEventConfig.fromJson(x.getJSONObject("event"))
        return JoinResult(event, x.getString("participant_token"))
    }

    fun downloadCourse(eventCode: String): File {
        require(available()) { "서버 미연결" }
        val code = URLEncoder.encode(eventCode.trim().uppercase(), "UTF-8")
        val conn = URL("${baseUrl()}/api/race/events/$code/gpx").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"; conn.connectTimeout = 8000; conn.readTimeout = 20000
        if (conn.responseCode !in 200..299) error("대회 GPX 다운로드 실패 · HTTP ${conn.responseCode}")
        val target = File(app.cacheDir, "race_${eventCode}_${System.currentTimeMillis()}.gpx")
        conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        conn.disconnect(); return target
    }

    fun fetchReference(eventCode: String): List<RaceReferencePoint> {
        if (!available() || eventCode == "PRACTICE") return emptyList()
        val code = URLEncoder.encode(eventCode.trim().uppercase(), "UTF-8")
        val x = request("GET", "/api/race/events/$code/reference", null, null)
        val a = x.optJSONArray("reference") ?: JSONArray()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(RaceReferencePoint::fromJson) }
    }

    fun sendLive(eventCode: String, token: String, payload: JSONObject): JSONObject {
        if (!available() || eventCode == "PRACTICE") return JSONObject().put("ok", true)
        return request("POST", "/api/race/live", payload, token)
    }

    fun sendSector(eventCode: String, token: String, payload: JSONObject): JSONObject {
        if (!available() || eventCode == "PRACTICE") return JSONObject().put("ok", true)
        return request("POST", "/api/race/sector", payload, token)
    }

    fun sendFinish(eventCode: String, token: String, payload: JSONObject): JSONObject {
        if (!available() || eventCode == "PRACTICE") return JSONObject().put("ok", true)
        return request("POST", "/api/race/finish", payload, token)
    }

    fun flushPending() {
        if (!available()) return
        for (item in store.queued()) {
            val key = item.optString("key"); val type = item.optString("type"); val eventCode = item.optString("event_code")
            val payload = item.optJSONObject("payload") ?: continue
            val token = store.joined(eventCode)?.token.orEmpty()
            if (eventCode != "PRACTICE" && token.isBlank()) continue
            val ok = runCatching {
                when (type) { "SECTOR" -> sendSector(eventCode, token, payload); "FINISH" -> sendFinish(eventCode, token, payload); else -> JSONObject() }
            }.isSuccess
            if (ok) store.removeQueued(key) else break
        }
    }

    private fun request(method: String, path: String, body: JSONObject?, token: String?): JSONObject {
        val conn = URL(baseUrl() + path).openConnection() as HttpURLConnection
        conn.requestMethod = method; conn.connectTimeout = 8000; conn.readTimeout = 15000
        conn.setRequestProperty("Accept", "application/json")
        if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) error("HTTP $code · ${text.take(180)}")
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }
}
