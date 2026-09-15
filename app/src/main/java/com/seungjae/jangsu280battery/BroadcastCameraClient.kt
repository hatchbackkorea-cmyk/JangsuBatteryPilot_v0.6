package com.seungjae.jangsu280battery

import android.content.Context
import android.location.Location
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

object BroadcastCameraClient {
    data class RegisterResult(
        val eventCode: String,
        val role: String,
        val leaseToken: String,
        val expiresAtMs: Long,
        val routeM: Double,
        val nearestM: Double,
        val accuracyM: Double,
    )

    data class HeartbeatResult(
        val eventCode: String,
        val role: String,
        val routeM: Double,
        val nearestM: Double,
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun register(context: Context, eventCode: String, location: Location): RegisterResult {
        val base = baseUrl(context, "")
        val canonicalCode = eventCode.trim().uppercase(Locale.US)
        require(canonicalCode.isNotBlank()) { "경기코드를 입력해 주세요." }
        val body = JSONObject().apply {
            put("event_code", canonicalCode)
            put("device_id", TimingOperatorStore.deviceId(context))
            put("device_label", TimingOperatorStore.deviceLabel(context))
            put("lat", location.latitude)
            put("lon", location.longitude)
            put("accuracy_m", if (location.hasAccuracy()) location.accuracy.toDouble() else 0.0)
        }
        val json = post(base, "/api/race/broadcast-camera/register", body)
        return parseRegister(json, canonicalCode)
    }

    /** CHASE is a moving default feed, so registration intentionally has no fixed GPX position. */
    fun registerChase(context: Context, eventCode: String): RegisterResult {
        val base = baseUrl(context, "")
        val canonicalCode = eventCode.trim().uppercase(Locale.US)
        require(canonicalCode.isNotBlank()) { "경기코드를 입력해 주세요." }
        val body = JSONObject().apply {
            put("event_code", canonicalCode)
            put("device_id", TimingOperatorStore.deviceId(context))
            put("device_label", TimingOperatorStore.deviceLabel(context))
        }
        val json = post(base, "/api/race/broadcast-director/chase/register", body)
        return parseRegister(json, canonicalCode)
    }

    fun heartbeat(context: Context, assignment: TimingOperatorStore.Assignment, location: Location): HeartbeatResult {
        if (assignment.role.trim().uppercase(Locale.US) == "CHASE") {
            // CHASE online/offline video state comes from the live WebRTC publisher connection.
            // Refresh V2 activation here as well so a server process restart cannot silently put an
            // already-running CHASE phone back under the legacy GPS AUTO director.
            runCatching { BroadcastDirectorClient.activate(context, assignment) }
            return HeartbeatResult(assignment.eventCode, "CHASE", 0.0, 0.0)
        }
        val base = baseUrl(context, assignment.serverUrl)
        val body = JSONObject().apply {
            put("event_code", assignment.eventCode.trim().uppercase(Locale.US))
            put("role", assignment.role.trim().uppercase(Locale.US))
            put("lease_token", assignment.token)
            put("device_id", TimingOperatorStore.deviceId(context))
            put("device_label", TimingOperatorStore.deviceLabel(context))
            put("lat", location.latitude)
            put("lon", location.longitude)
            put("accuracy_m", if (location.hasAccuracy()) location.accuracy.toDouble() else 0.0)
        }
        val json = post(base, "/api/race/broadcast-camera/heartbeat", body)
        return HeartbeatResult(
            eventCode = json.optString("event_code", assignment.eventCode).trim().uppercase(Locale.US),
            role = json.optString("role", assignment.role).trim().uppercase(Locale.US),
            routeM = json.optDouble("route_m", 0.0),
            nearestM = json.optDouble("nearest_m", 0.0),
        )
    }

    private fun parseRegister(json: JSONObject, canonicalCode: String) = RegisterResult(
        eventCode = json.optString("event_code", canonicalCode).trim().uppercase(Locale.US),
        role = json.getString("role").trim().uppercase(Locale.US),
        leaseToken = json.getString("lease_token"),
        expiresAtMs = json.getLong("lease_expires_at_ms"),
        routeM = json.optDouble("route_m", 0.0),
        nearestM = json.optDouble("nearest_m", 0.0),
        accuracyM = json.optDouble("accuracy_m", 0.0),
    )

    private fun baseUrl(context: Context, preferred: String): String {
        val base = preferred.trim().trimEnd('/').ifBlank {
            RaceServerClient(context).baseUrl().trim().trimEnd('/')
        }
        require(base.startsWith("http://") || base.startsWith("https://")) {
            "RACE 서버 주소를 먼저 설정해 주세요."
        }
        return base
    }

    private fun post(base: String, path: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(base + path)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Cache-Control", "no-cache")
            .build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(raw).optString("detail") }.getOrDefault("")
                error(detail.ifBlank { "HTTP ${response.code}" })
            }
            return JSONObject(raw)
        }
    }
}
