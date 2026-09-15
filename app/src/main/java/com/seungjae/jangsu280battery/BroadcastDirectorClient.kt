package com.seungjae.jangsu280battery

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Tiny control-plane client for Broadcast Director V2.
 *
 * No rider GPS, route position, speed, rank, or timing result is sent here. A camera phone only
 * requests/releases the temporary broadcast token based on its own local camera event.
 */
object BroadcastDirectorClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun requestLive(context: Context, assignment: TimingOperatorStore.Assignment, reason: String = "local-camera-event") {
        if (!eligible(assignment)) return
        post(context, assignment, "request", reason)
    }

    fun releaseLive(context: Context, assignment: TimingOperatorStore.Assignment, reason: String = "local-camera-clear") {
        if (!eligible(assignment)) return
        post(context, assignment, "release", reason)
    }

    private fun eligible(assignment: TimingOperatorStore.Assignment): Boolean {
        if (!assignment.isValid()) return false
        val role = assignment.role.trim().uppercase(Locale.US)
        return role != "CHASE" && role in TimingOperatorStore.ROLES
    }

    private fun post(context: Context, assignment: TimingOperatorStore.Assignment, action: String, reason: String) {
        val base = assignment.serverUrl.trim().trimEnd('/').ifBlank {
            RaceServerClient(context).baseUrl().trim().trimEnd('/')
        }
        if (!base.startsWith("http://") && !base.startsWith("https://")) return
        val code = enc(assignment.eventCode.trim().uppercase(Locale.US))
        val role = enc(assignment.role.trim().uppercase(Locale.US))
        val body = JSONObject().apply {
            put("device_id", TimingOperatorStore.deviceId(context))
            put("token", assignment.token)
            put("reason", reason.take(80))
        }
        val request = Request.Builder()
            .url("$base/api/race/broadcast-director/$code/$role/$action")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Cache-Control", "no-cache")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val raw = response.body?.string().orEmpty()
                val detail = runCatching { JSONObject(raw).optString("detail") }.getOrDefault("")
                error(detail.ifBlank { "Broadcast Director HTTP ${response.code}" })
            }
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
