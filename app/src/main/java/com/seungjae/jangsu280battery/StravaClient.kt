package com.seungjae.jangsu280battery

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object StravaClient {
    data class TokenResult(
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long,
        val athleteName: String?
    )

    fun exchangeCode(clientSecret: String, code: String): TokenResult {
        val body = form(mapOf(
            "client_id" to StravaSecureStore.CLIENT_ID,
            "client_secret" to clientSecret,
            "code" to code,
            "grant_type" to "authorization_code"
        ))
        return tokenFrom(requestJson("https://www.strava.com/oauth/token", body))
    }

    fun refresh(clientSecret: String, refreshToken: String): TokenResult {
        val body = form(mapOf(
            "client_id" to StravaSecureStore.CLIENT_ID,
            "client_secret" to clientSecret,
            "refresh_token" to refreshToken,
            "grant_type" to "refresh_token"
        ))
        val json = requestJson("https://www.strava.com/oauth/token", body)
        return TokenResult(
            accessToken = json.getString("access_token"),
            refreshToken = json.getString("refresh_token"),
            expiresAt = json.getLong("expires_at"),
            athleteName = null
        )
    }

    fun ensureAccessToken(store: StravaSecureStore): String {
        val access = store.accessToken() ?: error("Strava 연결이 필요합니다.")
        val now = System.currentTimeMillis() / 1000L
        if (store.expiresAt() > now + 300L) return access
        val secret = store.clientSecret() ?: error("Client Secret을 먼저 저장해 주세요.")
        val refreshToken = store.refreshToken() ?: error("Strava 재연결이 필요합니다.")
        val token = refresh(secret, refreshToken)
        store.saveTokens(token.accessToken, token.refreshToken, token.expiresAt, store.athleteName(), store.grantedScope())
        return token.accessToken
    }

    private fun tokenFrom(json: JSONObject): TokenResult {
        val athlete = json.optJSONObject("athlete")
        val name = listOfNotNull(
            athlete?.optString("firstname")?.takeIf { it.isNotBlank() },
            athlete?.optString("lastname")?.takeIf { it.isNotBlank() }
        ).joinToString(" ").takeIf { it.isNotBlank() }
        return TokenResult(
            accessToken = json.getString("access_token"),
            refreshToken = json.getString("refresh_token"),
            expiresAt = json.getLong("expires_at"),
            athleteName = name
        )
    }

    private fun requestJson(url: String, body: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.use { BufferedReader(InputStreamReader(it)).readText() }.orEmpty()
        if (code !in 200..299) {
            val message = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty()
            error("Strava HTTP $code${if (message.isNotBlank()) " · $message" else ""}")
        }
        return JSONObject(text)
    }

    private fun form(values: Map<String, String>): String = values.entries.joinToString("&") {
        "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
    }
}
