package com.seungjae.jangsu280battery

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class KakaoPlaceCandidate(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val address: String,
    val phone: String,
    val category: String,
    val confidence: String,
    val confidenceLabel: String,
    val straightDistanceM: Double
)

data class KakaoBicycleRoute(
    val distanceKm: Double,
    val minutes: Double,
    val landingUrl: String
)

data class EvaluatedEmergencyCandidate(
    val place: KakaoPlaceCandidate,
    val outbound: KakaoBicycleRoute,
    val back: KakaoBicycleRoute,
    val predictedArrivalSoc: Double
) {
    val roundTripKm: Double get() = outbound.distanceKm + back.distanceKm
}

/** Kakao Local + Bicycle REST API. 네트워크 호출은 반드시 background thread에서 사용한다. */
class KakaoEmergencyChargeClient(private val apiKey: String) {
    init { require(apiKey.isNotBlank()) { "Kakao REST API 키가 빌드에 주입되지 않았습니다." } }

    fun searchAround(lat: Double, lon: Double, radiusM: Int = 10_000): List<KakaoPlaceCandidate> {
        val searches = listOf(
            SearchSpec("전기자전거 충전", "B", "충전 관련 검색 · 현장 확인"),
            SearchSpec("자전거 충전", "B", "충전 관련 검색 · 현장 확인"),
            SearchSpec("전기자전거 대여", "B", "자전거 시설 · 충전 가능 여부 확인"),
            SearchSpec("편의점", "C", "비상 후보 · 콘센트 사용 문의"),
            SearchSpec("카페", "C", "비상 후보 · 콘센트 사용 문의"),
            SearchSpec("휴게소", "C", "비상 후보 · 콘센트 사용 문의")
        )
        val byId = linkedMapOf<String, KakaoPlaceCandidate>()
        searches.forEach { spec ->
            val query = enc(spec.query)
            val url = "https://dapi.kakao.com/v2/local/search/keyword.json?query=$query&x=$lon&y=$lat&radius=${radiusM.coerceIn(1000, 20000)}&sort=distance&size=15"
            val root = getJson(url)
            val arr = root.optJSONArray("documents") ?: return@forEach
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").ifBlank { "${o.optString("x")},${o.optString("y")}" }
                if (byId.containsKey(id)) continue
                val pLat = o.optString("y").toDoubleOrNull() ?: continue
                val pLon = o.optString("x").toDoubleOrNull() ?: continue
                byId[id] = KakaoPlaceCandidate(
                    id = id,
                    name = o.optString("place_name", spec.query),
                    lat = pLat,
                    lon = pLon,
                    address = o.optString("road_address_name").ifBlank { o.optString("address_name") },
                    phone = o.optString("phone"),
                    category = o.optString("category_name"),
                    confidence = spec.confidence,
                    confidenceLabel = spec.label,
                    straightDistanceM = o.optString("distance").toDoubleOrNull() ?: Geo.distanceMeters(lat, lon, pLat, pLon)
                )
            }
        }
        return byId.values.sortedWith(compareBy<KakaoPlaceCandidate>({ it.confidence }, { it.straightDistanceM })).take(12)
    }

    fun bicycleRoute(startLat: Double, startLon: Double, endLat: Double, endLon: Double): KakaoBicycleRoute {
        val url = "https://dapi.kakao.com/v2/routing/bicycle?start_x=$startLon&start_y=$startLat&end_x=$endLon&end_y=$endLat&input_coord=WGS84&output_coord=WGS84&route_mode=SHORTEST"
        val root = getJson(url)
        val status = root.optString("status", "OK")
        if (status != "OK" && !root.has("route")) throw IllegalStateException("자전거 경로 없음: $status")
        val props = root.optJSONObject("route")?.optJSONObject("properties")
            ?: throw IllegalStateException("자전거 경로 응답에 거리 정보가 없습니다.")
        return KakaoBicycleRoute(
            distanceKm = props.optDouble("totalDistance", 0.0) / 1000.0,
            minutes = props.optDouble("totalTime", 0.0) / 60.0,
            landingUrl = props.optString("landingUrl")
        )
    }

    private fun getJson(urlText: String): JSONObject {
        val conn = (URL(urlText).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 10_000
            setRequestProperty("Authorization", "KakaoAK $apiKey")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("Kakao API HTTP $code · ${body.take(180)}")
            JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8.name())
    private data class SearchSpec(val query: String, val confidence: String, val label: String)
}
