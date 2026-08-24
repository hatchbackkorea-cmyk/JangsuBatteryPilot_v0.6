package com.seungjae.jangsu280battery

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * GPX 코스별 충전 계획 저장소.
 * 모든 충전소는 최종적으로 GPX 진행거리(routeKm)에 매핑되어 배터리 판단 기준으로 사용된다.
 */
data class ChargingStation(
    val id: String,
    val name: String,
    val routeKm: Double,
    val lat: Double,
    val lon: Double,
    val source: String,
    val chargeToPct: Double = 80.0,
    val distanceFromRouteM: Double = 0.0,
    val detourKm: Double = 0.0,
    val address: String = ""
) {
    fun sourceLabel(): String = when (source) {
        SOURCE_WAYPOINT -> "GPX 웨이포인트"
        SOURCE_ADDRESS -> "주소"
        SOURCE_PROFILE -> "고도 프로필"
        SOURCE_CURRENT -> "현재 위치"
        SOURCE_RECOMMENDED -> "자동 권장"
        else -> "거리 직접 입력"
    }

    companion object {
        const val SOURCE_WAYPOINT = "WAYPOINT"
        const val SOURCE_ADDRESS = "ADDRESS"
        const val SOURCE_KM = "KM"
        const val SOURCE_PROFILE = "PROFILE"
        const val SOURCE_CURRENT = "CURRENT"
        const val SOURCE_RECOMMENDED = "RECOMMENDED"

        fun fromPoi(poi: RoutePoi, chargeToPct: Double = 80.0): ChargingStation = ChargingStation(
            id = waypointId(poi),
            name = poi.name.ifBlank { "GPX 포인트" },
            routeKm = poi.routeKm,
            lat = poi.lat,
            lon = poi.lon,
            source = SOURCE_WAYPOINT,
            chargeToPct = chargeToPct
        )

        fun waypointId(poi: RoutePoi): String = "wpt_${poi.name.hashCode()}_${(poi.routeKm * 1000).toLong()}"
        fun newId(): String = "charge_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
    }
}

class ChargingStationStore(context: Context) {
    companion object {
        private const val PREFS = "charging_station_store_v1"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(courseId: String): List<ChargingStation> {
        val raw = prefs.getString(key(courseId), null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ChargingStation(
                    id = o.optString("id", ChargingStation.newId()),
                    name = o.optString("name", "충전소"),
                    routeKm = o.optDouble("routeKm", 0.0),
                    lat = o.optDouble("lat", 0.0),
                    lon = o.optDouble("lon", 0.0),
                    source = o.optString("source", ChargingStation.SOURCE_KM),
                    chargeToPct = o.optDouble("chargeToPct", 80.0).coerceIn(1.0, 100.0),
                    distanceFromRouteM = o.optDouble("distanceFromRouteM", 0.0).coerceAtLeast(0.0),
                    detourKm = o.optDouble("detourKm", 0.0).coerceAtLeast(0.0),
                    address = o.optString("address", "")
                )
            }.filter { it.routeKm >= 0.0 }.sortedBy { it.routeKm }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun replace(courseId: String, items: List<ChargingStation>) {
        val arr = JSONArray()
        items.sortedBy { it.routeKm }.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("routeKm", s.routeKm)
                put("lat", s.lat)
                put("lon", s.lon)
                put("source", s.source)
                put("chargeToPct", s.chargeToPct)
                put("distanceFromRouteM", s.distanceFromRouteM)
                put("detourKm", s.detourKm)
                put("address", s.address)
            })
        }
        prefs.edit().putString(key(courseId), arr.toString()).apply()
    }

    fun upsert(courseId: String, station: ChargingStation) {
        val list = list(courseId).toMutableList()
        val idx = list.indexOfFirst { it.id == station.id }
        if (idx >= 0) list[idx] = station else list += station
        replace(courseId, list)
    }

    fun remove(courseId: String, stationId: String) {
        replace(courseId, list(courseId).filterNot { it.id == stationId })
    }

    fun clear(courseId: String) {
        prefs.edit().remove(key(courseId)).apply()
    }

    private fun key(courseId: String) = "stations_$courseId"
}
