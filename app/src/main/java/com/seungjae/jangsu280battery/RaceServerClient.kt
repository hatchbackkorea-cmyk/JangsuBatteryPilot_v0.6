package com.seungjae.jangsu280battery

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * RACE HTTP client.
 *
 * Timing authority rule:
 * - the rider phone owns START/CP/FINISH measurement and elapsed_ms;
 * - the server receives/stores/displays the phone values and never recomputes official elapsed time;
 * - a tiny terminal LIVE packet is sent before the durable FINISH upload so the monitor freezes fast;
 * - Sector/finish stay durable-queued and stale QR/field servers recover automatically.
 */
class RaceServerClient(context: Context) {
    companion object {
        private const val PREF = "race_server_route_v1"
        private const val KEY_EVENT_SERVER = "event_server"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private val app = context.applicationContext
    private val sync = RiderServerSync(app)
    private val store = RaceDataStore(app)
    private val prefs = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    data class JoinResult(val config: RaceEventConfig, val participantToken: String, val phase: String)
    data class EventListItem(
        val config: RaceEventConfig,
        val status: String,
        val participants: Int,
        val joinable: Boolean,
        val phase: String,
        val practiceOpenMs: Long,
        val officialStartMs: Long,
        val officialEndMs: Long,
        val notice: String
    )

    private class HttpFailure(val status: Int, messageText: String) : IllegalStateException(messageText)

    fun setEventServer(url: String) {
        val clean = url.trim().trimEnd('/')
        if (clean.startsWith("http://") || clean.startsWith("https://")) {
            prefs.edit().putString(KEY_EVENT_SERVER, clean).apply()
        }
    }

    fun clearEventServer() {
        prefs.edit().remove(KEY_EVENT_SERVER).apply()
    }

    fun eventServerOverride(): String = prefs.getString(KEY_EVENT_SERVER, "").orEmpty().trim().trimEnd('/')

    fun baseUrl(): String = eventServerOverride()
        .ifBlank { BuildConfig.DEFAULT_RACE_SERVER_URL.trim().trimEnd('/') }
        .ifBlank { sync.serverUrl().trim().trimEnd('/') }

    fun available(): Boolean = candidateBaseUrls().isNotEmpty()
    fun debugCandidates(): String = candidateBaseUrls().joinToString(" -> ")
    private fun norm(v: String) = v.trim().trimEnd('/').lowercase()

    private fun parseEvent(o: JSONObject): EventListItem {
        val phase = o.optString("phase", o.optString("status", "WAITING")).uppercase()
        return EventListItem(
            RaceEventConfig.fromJson(o),
            o.optString("status", phase).uppercase(),
            o.optInt("participants", 0),
            o.optBoolean("joinable", phase == "PRACTICE" || phase == "OFFICIAL"),
            phase,
            o.optLong("practice_open_ms", 0L),
            o.optLong("official_start_ms", 0L),
            o.optLong("official_end_ms", 0L),
            o.optString("notice", "")
        )
    }

    fun listEvents(): List<EventListItem> {
        val payload = request("GET", "/api/race/events", null, null)
        val a = payload.optJSONArray("events") ?: JSONArray()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::parseEvent) }
    }

    fun eventState(eventCode: String): EventListItem {
        val code = URLEncoder.encode(eventCode.trim().uppercase(), "UTF-8")
        return parseEvent(request("GET", "/api/race/events/$code", null, null))
    }

    fun join(eventCode: String, profile: RaceProfileStore.Profile): JoinResult {
        val body = JSONObject().apply {
            put("event_code", eventCode.trim().uppercase())
            put("profile_id", profile.profileId)
            put("name", profile.name)
            put("nickname", profile.nickname)
            put("bib", profile.bib)
            put("platform", "ANDROID")
            put("app_version", UpdateManager.currentVersion(app))
        }
        val x = request("POST", "/api/race/join", body, null)
        val eventObj = x.getJSONObject("event")
        return JoinResult(
            RaceEventConfig.fromJson(eventObj),
            x.getString("participant_token"),
            eventObj.optString("phase", eventObj.optString("status", "PRACTICE")).uppercase()
        )
    }

    fun updateParticipantProfile(eventCode: String, token: String, profile: RaceProfileStore.Profile): JSONObject {
        require(eventCode.isNotBlank() && token.isNotBlank()) { "대회 참가 정보가 없습니다." }
        val code = URLEncoder.encode(eventCode.trim().uppercase(), "UTF-8")
        val body = JSONObject().apply {
            put("profile_id", profile.profileId)
            put("name", profile.name)
            put("nickname", profile.nickname)
            put("bib", profile.bib)
            put("platform", "ANDROID")
            put("app_version", UpdateManager.currentVersion(app))
        }
        return request("PUT", "/api/race/events/$code/participant-profile", body, token)
    }

    fun recoverLocalRuns(eventCode: String, token: String, payload: JSONObject): JSONObject {
        require(eventCode.isNotBlank() && token.isNotBlank()) { "대회 참가 정보가 없습니다." }
        val code = URLEncoder.encode(eventCode.trim().uppercase(), "UTF-8")
        return request("POST", "/api/race/events/$code/recover-local-runs", payload, token)
    }

    fun downloadCourse(eventCode: String): File {
        val cleanEventCode = eventCode.trim().uppercase()
        val code = URLEncoder.encode(cleanEventCode, "UTF-8")
        RaceGpxDownloadStatus.markChecking(app, cleanEventCode)
        var last: Throwable? = null
        for (base in candidateBaseUrls()) {
            try {
                val conn = URL("$base/api/race/events/$code/gpx").openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 8000
                conn.readTimeout = 20000
                conn.setRequestProperty("User-Agent", "TimeGate/${UpdateManager.currentVersion(app)}")
                val status = conn.responseCode
                if (status !in 200..299) {
                    conn.disconnect()
                    throw HttpFailure(status, "대회 GPX 다운로드 실패 · HTTP $status")
                }
                val serverFileName = responseFileName(conn, cleanEventCode)
                RaceGpxDownloadStatus.markDownloading(app, cleanEventCode, serverFileName)
                val target = File(app.cacheDir, serverFileName).apply { if (exists()) delete() }
                conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
                conn.disconnect()
                val sha = RaceGpxDownloadStatus.sha256(target)
                RaceGpxDownloadStatus.markDownloaded(app, cleanEventCode, serverFileName, sha, target.length())
                adoptWorkingBase(base)
                return target
            } catch (e: Throwable) {
                last = e
                if (e is HttpFailure && e.status in setOf(400, 409, 422)) {
                    RaceGpxDownloadStatus.markFailed(app, cleanEventCode, e.message ?: "GPX 다운로드 실패")
                    throw e
                }
            }
        }
        val message = "대회 GPX 다운로드 실패 · ${last?.javaClass?.simpleName ?: "오류"}: ${last?.message ?: "RACE 서버 연결을 확인하세요."}"
        RaceGpxDownloadStatus.markFailed(app, cleanEventCode, message)
        error(message)
    }

    private fun responseFileName(conn: HttpURLConnection, eventCode: String): String {
        val disposition = conn.getHeaderField("Content-Disposition").orEmpty()
        val utf8 = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE)
            .find(disposition)?.groupValues?.getOrNull(1)
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        val normal = Regex("filename=\\\"?([^\\\";]+)\\\"?", RegexOption.IGNORE_CASE)
            .find(disposition)?.groupValues?.getOrNull(1)
        val raw = utf8 ?: normal ?: "race_${eventCode}.gpx"
        val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        val safe = base.replace(Regex("[^0-9A-Za-z가-힣._ -]"), "_").take(120).ifBlank { "race_${eventCode}.gpx" }
        return if (safe.lowercase().endsWith(".gpx")) safe else "$safe.gpx"
    }

    fun fetchReference(eventCode: String): List<RaceReferencePoint> {
        if (eventCode == "PRACTICE") return emptyList()
        val code = URLEncoder.encode(eventCode.trim().uppercase(), "UTF-8")
        val a = request("GET", "/api/race/events/$code/reference", null, null).optJSONArray("reference") ?: JSONArray()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(RaceReferencePoint::fromJson) }
    }

    fun sendLive(eventCode: String, token: String, payload: JSONObject): JSONObject =
        if (eventCode == "PRACTICE") JSONObject().put("ok", true) else request("POST", "/api/race/live", payload, token)

    fun sendSector(eventCode: String, token: String, payload: JSONObject): JSONObject =
        if (eventCode == "PRACTICE") JSONObject().put("ok", true) else request("POST", "/api/race/sector", payload, token)

    fun sendFinish(eventCode: String, token: String, payload: JSONObject): JSONObject =
        if (eventCode == "PRACTICE") JSONObject().put("ok", true) else request("POST", "/api/race/finish", payload, token)

    private fun terminalLivePayload(eventCode: String, payload: JSONObject): JSONObject {
        val isDnf = payload.optString("status").equals("DNF", ignoreCase = true) || payload.optBoolean("dnf", false)
        val state = if (isDnf) "DNF" else "FINISHED"
        val finishedAt = payload.optLong("finished_at_ms", 0L).takeIf { it > 0L }
            ?: payload.optLong("timestamp_ms", 0L).takeIf { it > 0L }
            ?: System.currentTimeMillis()
        return JSONObject().apply {
            put("event_code", eventCode.trim().uppercase())
            put("run_id", payload.optString("run_id"))
            put("run_number", payload.optInt("run_number", 1))
            put("state", state)
            put("status", state)
            put("dnf", isDnf)
            put("started_at_ms", payload.optLong("started_at_ms", 0L))
            put("finished_at_ms", finishedAt)
            put("timestamp_ms", finishedAt)
            put("elapsed_ms", payload.optLong("elapsed_ms", 0L))
            put("route_m", payload.optDouble("route_m", payload.optDouble("total_m", 0.0)))
            put("sector_index", (payload.optJSONArray("sectors")?.length() ?: 0) + 1)
            put("timing_authority", "PHONE")
            put("phone_time_frozen", true)
        }
    }

    fun flushPending() {
        if (!available()) return
        val currentServer = baseUrl()
        val fieldOverrideActive = eventServerOverride().isNotBlank()
        val pending = store.queued().sortedBy { if (it.optString("type") == "FINISH") 0 else 1 }
        for (item in pending) {
            val queuedServer = item.optString("server_url")
            if (queuedServer.isBlank() && fieldOverrideActive) continue
            if (queuedServer.isNotBlank() && norm(queuedServer) != norm(currentServer)) continue
            val key = item.optString("key")
            val type = item.optString("type")
            val eventCode = item.optString("event_code")
            val payload = item.optJSONObject("payload") ?: continue
            val runId = payload.optString("run_id")
            val token = store.joined(eventCode, currentServer)?.token.orEmpty()
            if (eventCode != "PRACTICE" && token.isBlank()) continue

            payload.put("timing_authority", "PHONE")
            RaceTimingTransportLog.write(app, "QUEUE_TX", eventCode, runId, payload, type)
            val result = runCatching {
                when (type) {
                    "START" -> sendLive(eventCode, token, payload)
                    "SECTOR" -> sendSector(eventCode, token, payload)
                    "FINISH" -> {
                        val terminal = terminalLivePayload(eventCode, payload)
                        RaceTimingTransportLog.write(app, "TERMINAL_TX", eventCode, runId, terminal)
                        runCatching { sendLive(eventCode, token, terminal) }
                            .onSuccess { RaceTimingTransportLog.write(app, "TERMINAL_ACK", eventCode, runId, terminal) }
                            .onFailure { RaceTimingTransportLog.write(app, "TERMINAL_FAIL", eventCode, runId, terminal, it.message.orEmpty()) }
                        sendFinish(eventCode, token, payload)
                    }
                    else -> JSONObject()
                }
            }
            if (result.isSuccess) {
                RaceTimingTransportLog.write(app, "QUEUE_ACK", eventCode, runId, payload, type)
                store.removeQueued(key)
            } else {
                RaceTimingTransportLog.write(app, "QUEUE_FAIL", eventCode, runId, payload, result.exceptionOrNull()?.message.orEmpty())
                break
            }
        }
    }

    private fun candidateBaseUrls(): List<String> {
        val out = LinkedHashSet<String>()
        fun add(v: String?) {
            val clean = v.orEmpty().trim().trimEnd('/')
            if (clean.startsWith("http://") || clean.startsWith("https://")) out += clean
        }
        // Deep-link/event override remains first for field servers. The APK-published address is
        // always included immediately after it so a stale local/admin URL can never strand a fresh phone.
        add(eventServerOverride())
        add(BuildConfig.DEFAULT_RACE_SERVER_URL)
        add(fetchPublishedPcServer())
        add(sync.serverUrl())
        return out.toList()
    }

    private fun fetchPublishedPcServer(): String? {
        val repo = BuildConfig.UPDATE_REPOSITORY.trim()
        if (!repo.contains('/')) return null
        val connection = URL("https://raw.githubusercontent.com/$repo/main/rcc-server.json")
            .openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 2500
            connection.readTimeout = 2500
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache")
            if (connection.responseCode !in 200..299) return null
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONObject(text).optString("url", "").trim().trimEnd('/').takeIf { it.startsWith("http") }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun adoptWorkingBase(base: String) {
        val clean = base.trim().trimEnd('/')
        if (clean.startsWith("http")) setEventServer(clean)
    }

    private fun request(method: String, path: String, body: JSONObject?, token: String?): JSONObject {
        val candidates = candidateBaseUrls()
        require(candidates.isNotEmpty()) { "Rider Control Center 서버가 연결되지 않았습니다." }
        val errors = mutableListOf<String>()
        var last: Throwable? = null
        for (base in candidates) {
            try {
                val result = requestAt(base, method, path, body, token)
                adoptWorkingBase(base)
                return result
            } catch (e: Throwable) {
                last = e
                errors += "${base.substringAfter("://")}=${e.javaClass.simpleName}:${e.message.orEmpty().take(100)}"
                // Invalid request data should not be retried against another server. Authentication/network
                // failures are retried because stale field/admin routes are common on participant phones.
                if (e is HttpFailure && e.status in setOf(400, 409, 422)) throw e
            }
        }
        val detail = errors.joinToString(" | ").take(700)
        error("RACE 서버 연결 실패 · ${last?.javaClass?.simpleName ?: "오류"}: ${last?.message ?: "서버 주소 또는 네트워크를 확인하세요."}\n시도: $detail")
    }

    private fun requestAt(base: String, method: String, path: String, body: JSONObject?, token: String?): JSONObject {
        val url = base.trim().trimEnd('/') + path
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .header("User-Agent", "TimeGate/${UpdateManager.currentVersion(app)} Android")
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")

        val requestBody = body?.toString()?.toRequestBody(JSON)
        when (method.uppercase()) {
            "GET" -> builder.get()
            "POST" -> builder.post(requestBody ?: "{}".toRequestBody(JSON))
            "PUT" -> builder.put(requestBody ?: "{}".toRequestBody(JSON))
            "DELETE" -> if (requestBody != null) builder.delete(requestBody) else builder.delete()
            else -> builder.method(method.uppercase(), requestBody)
        }

        return http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull().orEmpty()
                throw HttpFailure(response.code, detail.ifBlank { "HTTP ${response.code} · ${text.take(180)}" })
            }
            if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }
}
