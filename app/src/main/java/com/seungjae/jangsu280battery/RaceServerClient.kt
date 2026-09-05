package com.seungjae.jangsu280battery

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** RACE HTTP client. Sector/finish stay durable-queued; a QR may temporarily override the event server. */
class RaceServerClient(context: Context) {
    companion object { private const val PREF = "race_server_route_v1"; private const val KEY_EVENT_SERVER = "event_server" }
    private val app = context.applicationContext
    private val sync = RiderServerSync(app)
    private val store = RaceDataStore(app)
    private val prefs = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    data class JoinResult(val config: RaceEventConfig, val participantToken: String, val phase: String)
    data class EventListItem(val config: RaceEventConfig, val status: String, val participants: Int, val joinable: Boolean, val phase: String, val practiceOpenMs: Long, val officialStartMs: Long, val officialEndMs: Long, val notice: String)

    fun setEventServer(url: String) { val clean = url.trim().trimEnd('/'); if (clean.startsWith("http://") || clean.startsWith("https://")) prefs.edit().putString(KEY_EVENT_SERVER, clean).apply() }
    fun eventServerOverride(): String = prefs.getString(KEY_EVENT_SERVER, "").orEmpty().trim().trimEnd('/')
    fun baseUrl(): String = eventServerOverride().ifBlank { sync.serverUrl().trim().trimEnd('/') }
    fun available(): Boolean = baseUrl().startsWith("http://") || baseUrl().startsWith("https://")
    private fun norm(v: String) = v.trim().trimEnd('/').lowercase()

    private fun parseEvent(o: JSONObject): EventListItem {
        val phase = o.optString("phase", o.optString("status", "WAITING")).uppercase()
        return EventListItem(RaceEventConfig.fromJson(o), o.optString("status", phase).uppercase(), o.optInt("participants", 0), o.optBoolean("joinable", phase == "PRACTICE" || phase == "OFFICIAL"), phase, o.optLong("practice_open_ms", 0L), o.optLong("official_start_ms", 0L), o.optLong("official_end_ms", 0L), o.optString("notice", ""))
    }

    fun listEvents(): List<EventListItem> {
        require(available()) { "Rider Control Center 서버가 연결되지 않았습니다." }
        val a = request("GET", "/api/race/events", null, null).optJSONArray("events") ?: JSONArray()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::parseEvent) }
    }

    fun eventState(eventCode: String): EventListItem {
        require(available()) { "Rider Control Center 서버가 연결되지 않았습니다." }
        val code = URLEncoder.encode(eventCode.trim().uppercase(), "UTF-8")
        return parseEvent(request("GET", "/api/race/events/$code", null, null))
    }

    fun join(eventCode: String, profile: RaceProfileStore.Profile): JoinResult {
        require(available()) { "Rider Control Center 서버가 연결되지 않았습니다." }
        val body = JSONObject().apply { put("event_code", eventCode.trim().uppercase()); put("profile_id", profile.profileId); put("name", profile.name); put("nickname", profile.nickname); put("bib", profile.bib); put("platform", "ANDROID"); put("app_version", UpdateManager.currentVersion(app)) }
        val x = request("POST", "/api/race/join", body, null); val eventObj = x.getJSONObject("event")
        return JoinResult(RaceEventConfig.fromJson(eventObj), x.getString("participant_token"), eventObj.optString("phase", eventObj.optString("status", "PRACTICE")).uppercase())
    }

    fun updateParticipantProfile(eventCode: String, token: String, profile: RaceProfileStore.Profile): JSONObject {
        require(available()) { "Rider Control Center 서버가 연결되지 않았습니다." }
        require(eventCode.isNotBlank() && token.isNotBlank()) { "대회 참가 정보가 없습니다." }
        val code = URLEncoder.encode(eventCode.trim().uppercase(), "UTF-8")
        val body = JSONObject().apply { put("name", profile.name); put("nickname", profile.nickname); put("bib", profile.bib) }
        return request("PUT", "/api/race/events/$code/participant-profile", body, token)
    }

    fun downloadCourse(eventCode: String): File {
        require(available()) { "서버 미연결" }; val code = URLEncoder.encode(eventCode.trim().uppercase(), "UTF-8")
        val conn = URL("${baseUrl()}/api/race/events/$code/gpx").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"; conn.connectTimeout = 8000; conn.readTimeout = 20000; val status = conn.responseCode
        if (status !in 200..299) error("대회 GPX 다운로드 실패 · HTTP $status")
        val target = File(app.cacheDir, "race_${eventCode}_${System.currentTimeMillis()}.gpx"); conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }; conn.disconnect(); return target
    }

    fun fetchReference(eventCode: String): List<RaceReferencePoint> {
        if (!available() || eventCode == "PRACTICE") return emptyList(); val code = URLEncoder.encode(eventCode.trim().uppercase(), "UTF-8")
        val a = request("GET", "/api/race/events/$code/reference", null, null).optJSONArray("reference") ?: JSONArray()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(RaceReferencePoint::fromJson) }
    }

    fun sendLive(eventCode: String, token: String, payload: JSONObject): JSONObject = if (!available() || eventCode == "PRACTICE") JSONObject().put("ok", true) else request("POST", "/api/race/live", payload, token)
    fun sendSector(eventCode: String, token: String, payload: JSONObject): JSONObject = if (!available() || eventCode == "PRACTICE") JSONObject().put("ok", true) else request("POST", "/api/race/sector", payload, token)
    fun sendFinish(eventCode: String, token: String, payload: JSONObject): JSONObject = if (!available() || eventCode == "PRACTICE") JSONObject().put("ok", true) else request("POST", "/api/race/finish", payload, token)

    fun flushPending() {
        if (!available()) return
        val currentServer = baseUrl(); val fieldOverrideActive = eventServerOverride().isNotBlank()
        for (item in store.queued()) {
            val queuedServer = item.optString("server_url")
            // Old queue entries were created before server scoping. Never send them into a QR-selected field server.
            if (queuedServer.isBlank() && fieldOverrideActive) continue
            if (queuedServer.isNotBlank() && norm(queuedServer) != norm(currentServer)) continue
            val key = item.optString("key"); val type = item.optString("type"); val eventCode = item.optString("event_code"); val payload = item.optJSONObject("payload") ?: continue
            val token = store.joined(eventCode, currentServer)?.token.orEmpty()
            if (eventCode != "PRACTICE" && token.isBlank()) continue
            val ok = runCatching { when (type) { "SECTOR" -> sendSector(eventCode, token, payload); "FINISH" -> sendFinish(eventCode, token, payload); else -> JSONObject() } }.isSuccess
            if (ok) store.removeQueued(key) else break
        }
    }

    private fun request(method: String, path: String, body: JSONObject?, token: String?): JSONObject {
        val conn = URL(baseUrl() + path).openConnection() as HttpURLConnection
        conn.requestMethod = method; conn.connectTimeout = 8000; conn.readTimeout = 15000; conn.setRequestProperty("Accept", "application/json")
        if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) { conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json; charset=utf-8"); conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) } }
        val code = conn.responseCode; val stream = if (code in 200..299) conn.inputStream else conn.errorStream; val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty(); conn.disconnect()
        if (code !in 200..299) { val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull().orEmpty(); error(detail.ifBlank { "HTTP $code · ${text.take(180)}" }) }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }
}
