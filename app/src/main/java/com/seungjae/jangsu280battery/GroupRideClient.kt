package com.seungjae.jangsu280battery

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.UUID

data class GroupRider(
    val riderId: String,
    val nickname: String,
    val courseKey: String,
    val routeKm: Double,
    val lat: Double,
    val lon: Double,
    val speedKph: Double,
    val updatedMs: Long
)

class GroupRideClient(private val baseUrl: String) {
    fun sync(room: String, self: GroupRider): List<GroupRider> {
        require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) { "그룹 릴레이 URL을 확인해 주세요." }
        val root = baseUrl.trimEnd('/')
        val post = (URL("$root/update").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5000
            readTimeout = 5000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        val body = JSONObject()
            .put("room", room)
            .put("riderId", self.riderId)
            .put("nickname", self.nickname)
            .put("courseKey", self.courseKey)
            .put("routeKm", self.routeKm)
            .put("lat", self.lat)
            .put("lon", self.lon)
            .put("speedKph", self.speedKph)
            .put("updatedMs", self.updatedMs)
            .toString()
        post.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val postCode = post.responseCode
        if (postCode !in 200..299) {
            val errorText = runCatching { post.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
            post.disconnect()
            if (postCode == 409 && errorText.contains("room_full")) error("방 정원 20명이 가득 찼습니다.")
            error("그룹 위치 전송 실패 HTTP $postCode")
        }
        post.inputStream.close()
        post.disconnect()

        val encoded = URLEncoder.encode(room, "UTF-8")
        val get = (URL("$root/room?room=$encoded").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
        }
        val code = get.responseCode
        if (code !in 200..299) error("그룹 조회 실패 HTTP $code")
        val text = get.inputStream.bufferedReader().use { it.readText() }
        get.disconnect()
        val arr = JSONObject(text).optJSONArray("riders") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                GroupRider(
                    riderId = o.optString("riderId", UUID.randomUUID().toString()),
                    nickname = o.optString("nickname", "팀원"),
                    courseKey = o.optString("courseKey", ""),
                    routeKm = o.optDouble("routeKm", 0.0),
                    lat = o.optDouble("lat", 0.0),
                    lon = o.optDouble("lon", 0.0),
                    speedKph = o.optDouble("speedKph", 0.0),
                    updatedMs = o.optLong("updatedMs", 0L)
                )
            }.getOrNull()
        }
    }
}
