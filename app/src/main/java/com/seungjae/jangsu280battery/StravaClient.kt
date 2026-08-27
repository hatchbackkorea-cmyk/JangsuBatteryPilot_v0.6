package com.seungjae.jangsu280battery

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

object StravaClient {
    data class TokenResult(
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long,
        val athleteName: String?
    )

    data class UploadResult(
        val uploadId: String,
        val activityId: String?,
        val status: String,
        val error: String?
    )

    fun exchangeCode(clientSecret: String, code: String): TokenResult {
        val body = form(mapOf(
            "client_id" to StravaSecureStore.CLIENT_ID,
            "client_secret" to clientSecret,
            "code" to code,
            "grant_type" to "authorization_code"
        ))
        val json = requestJson("https://www.strava.com/oauth/token", "POST", body.toByteArray(), "application/x-www-form-urlencoded")
        return tokenFrom(json)
    }

    fun refresh(clientSecret: String, refreshToken: String): TokenResult {
        val body = form(mapOf(
            "client_id" to StravaSecureStore.CLIENT_ID,
            "client_secret" to clientSecret,
            "refresh_token" to refreshToken,
            "grant_type" to "refresh_token"
        ))
        val json = requestJson("https://www.strava.com/oauth/token", "POST", body.toByteArray(), "application/x-www-form-urlencoded")
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
        if (store.expiresAt() > now + 3600L) return access
        val secret = store.clientSecret() ?: error("Client Secret을 먼저 저장해 주세요.")
        val refresh = store.refreshToken() ?: error("Strava 재연결이 필요합니다.")
        val token = refresh(secret, refresh)
        store.saveTokens(token.accessToken, token.refreshToken, token.expiresAt, store.athleteName())
        return token.accessToken
    }

    fun uploadFit(
        context: Context,
        uri: Uri,
        accessToken: String,
        name: String,
        description: String
    ): UploadResult {
        val filename = displayName(context, uri).ifBlank { "avinox.fit" }
        return uploadFitStream(
            accessToken = accessToken,
            filename = filename,
            name = name,
            description = description,
            inputProvider = {
                context.contentResolver.openInputStream(uri) ?: error("FIT 파일을 열 수 없습니다.")
            }
        )
    }

    fun uploadFitFile(
        file: File,
        accessToken: String,
        name: String,
        description: String
    ): UploadResult {
        require(file.exists() && file.length() > 0L) { "업로드할 클린 FIT을 찾지 못했습니다." }
        return uploadFitStream(
            accessToken = accessToken,
            filename = file.name.ifBlank { "jangsu_clean.fit" },
            name = name,
            description = description,
            inputProvider = { file.inputStream() }
        )
    }

    private fun uploadFitStream(
        accessToken: String,
        filename: String,
        name: String,
        description: String,
        inputProvider: () -> InputStream
    ): UploadResult {
        val boundary = "----JangsuBatteryPilot${UUID.randomUUID()}"
        val conn = (URL("https://www.strava.com/api/v3/uploads").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        conn.outputStream.use { out ->
            fun field(key: String, value: String) {
                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"$key\"\r\n\r\n".toByteArray())
                out.write(value.toByteArray(Charsets.UTF_8))
                out.write("\r\n".toByteArray())
            }
            field("data_type", "fit")
            field("sport_type", "EMountainBikeRide")
            field("name", name)
            field("description", description)
            field("external_id", "jangsu-${System.currentTimeMillis()}-${filename.takeLast(80)}")
            out.write("--$boundary\r\n".toByteArray())
            out.write("Content-Disposition: form-data; name=\"file\"; filename=\"${filename.replace("\"", "")}\"\r\n".toByteArray())
            out.write("Content-Type: application/octet-stream\r\n\r\n".toByteArray())
            inputProvider().use { input -> input.copyTo(out) }
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        val json = readJsonResponse(conn)
        return UploadResult(
            uploadId = json.optString("id_str").ifBlank { json.optLong("id", 0L).toString() },
            activityId = json.opt("activity_id")?.toString()?.takeUnless { it == "null" || it == "0" },
            status = json.optString("status"),
            error = json.optString("error").takeIf { it.isNotBlank() && it != "null" }
        )
    }

    fun uploadStatus(accessToken: String, uploadId: String): UploadResult {
        val conn = (URL("https://www.strava.com/api/v3/uploads/$uploadId").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("Authorization", "Bearer $accessToken")
        }
        val json = readJsonResponse(conn)
        return UploadResult(
            uploadId = json.optString("id_str").ifBlank { uploadId },
            activityId = json.opt("activity_id")?.toString()?.takeUnless { it == "null" || it == "0" },
            status = json.optString("status"),
            error = json.optString("error").takeIf { it.isNotBlank() && it != "null" }
        )
    }

    private fun tokenFrom(json: JSONObject): TokenResult {
        val athlete = json.optJSONObject("athlete")
        val name = listOfNotNull(athlete?.optString("firstname")?.takeIf { it.isNotBlank() }, athlete?.optString("lastname")?.takeIf { it.isNotBlank() })
            .joinToString(" ").takeIf { it.isNotBlank() }
        return TokenResult(
            accessToken = json.getString("access_token"),
            refreshToken = json.getString("refresh_token"),
            expiresAt = json.getLong("expires_at"),
            athleteName = name
        )
    }

    private fun requestJson(url: String, method: String, body: ByteArray, contentType: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Content-Type", contentType)
            setRequestProperty("Accept", "application/json")
        }
        conn.outputStream.use { it.write(body) }
        return readJsonResponse(conn)
    }

    private fun readJsonResponse(conn: HttpURLConnection): JSONObject {
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
        "${URLEncoder.encode(it.key, "UTF-8") }=${URLEncoder.encode(it.value, "UTF-8") }"
    }

    private fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) return c.getString(i) ?: "avinox.fit"
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "avinox.fit"
    }
}
