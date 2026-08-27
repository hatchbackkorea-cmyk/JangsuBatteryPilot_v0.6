package com.seungjae.jangsu280battery

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

enum class EmergencyPhase { OUTBOUND, CHARGING, RETURN }

data class EmergencyDetourSession(
    val courseId: String,
    val anchorRouteKm: Double,
    val anchorLat: Double,
    val anchorLon: Double,
    val candidateId: String,
    val candidateName: String,
    val candidateLat: Double,
    val candidateLon: Double,
    val candidateAddress: String,
    val candidateConfidence: String,
    val outboundKm: Double,
    val outboundMinutes: Double,
    val returnKm: Double,
    val returnMinutes: Double,
    val outboundUrl: String,
    val returnUrl: String,
    val phase: EmergencyPhase,
    val startedMs: Long,
    val phaseStartMs: Long
)

data class ConfirmedEmergencyChargePlace(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val address: String,
    val successCount: Int,
    val lastUsedMs: Long
)

/**
 * v0.26.6 실시간 충전 재계획 상태.
 * - 사용자가 생략 확정한 계획 충전소
 * - 경기 규정상 반드시 돌아와야 하는 GPX 이탈 앵커
 * - 비상 충전 왕복 상태/경로
 * - 실제 충전 성공 장소
 * 를 로컬에만 저장한다.
 */
class RideReplanStore(context: Context) {
    companion object {
        private const val PREFS = "ride_replan_v1"
        private const val KEY_SESSION = "emergency_session"
        private const val KEY_HISTORY = "emergency_charge_history"
        private const val KEY_BREADCRUMB = "emergency_breadcrumb"
        private const val MAX_BREADCRUMB = 1200
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun skippedKey(courseId: String) = "skipped_$courseId"
    private fun kmKey(km: Double) = (km * 100.0).roundToInt()

    fun skippedKms(courseId: String): Set<Int> = prefs.getStringSet(skippedKey(courseId), emptySet())
        .orEmpty().mapNotNull { it.toIntOrNull() }.toSet()

    fun isSkipped(courseId: String, stationKm: Double): Boolean {
        val key = kmKey(stationKm)
        return skippedKms(courseId).any { kotlin.math.abs(it - key) <= 5 }
    }

    fun markSkipped(courseId: String, stationKm: Double) {
        val set = prefs.getStringSet(skippedKey(courseId), emptySet()).orEmpty().toMutableSet()
        set += kmKey(stationKm).toString()
        prefs.edit().putStringSet(skippedKey(courseId), set).apply()
    }

    fun unskip(courseId: String, stationKm: Double) {
        val key = kmKey(stationKm)
        val set = prefs.getStringSet(skippedKey(courseId), emptySet()).orEmpty().filterNot {
            val k = it.toIntOrNull() ?: return@filterNot false
            abs(k - key) <= 5
        }.toSet()
        prefs.edit().putStringSet(skippedKey(courseId), set).apply()
    }

    fun clearCourse(courseId: String) {
        prefs.edit().remove(skippedKey(courseId)).remove(KEY_SESSION).remove(KEY_BREADCRUMB).apply()
    }

    fun active(courseId: String? = null): EmergencyDetourSession? {
        val raw = prefs.getString(KEY_SESSION, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            EmergencyDetourSession(
                courseId = o.getString("courseId"),
                anchorRouteKm = o.getDouble("anchorRouteKm"),
                anchorLat = o.getDouble("anchorLat"),
                anchorLon = o.getDouble("anchorLon"),
                candidateId = o.optString("candidateId"),
                candidateName = o.optString("candidateName", "비상 충전 후보"),
                candidateLat = o.getDouble("candidateLat"),
                candidateLon = o.getDouble("candidateLon"),
                candidateAddress = o.optString("candidateAddress"),
                candidateConfidence = o.optString("candidateConfidence", "C"),
                outboundKm = o.optDouble("outboundKm", 0.0),
                outboundMinutes = o.optDouble("outboundMinutes", 0.0),
                returnKm = o.optDouble("returnKm", 0.0),
                returnMinutes = o.optDouble("returnMinutes", 0.0),
                outboundUrl = o.optString("outboundUrl"),
                returnUrl = o.optString("returnUrl"),
                phase = runCatching { EmergencyPhase.valueOf(o.optString("phase", EmergencyPhase.OUTBOUND.name)) }.getOrDefault(EmergencyPhase.OUTBOUND),
                startedMs = o.optLong("startedMs", System.currentTimeMillis()),
                phaseStartMs = o.optLong("phaseStartMs", System.currentTimeMillis())
            )
        }.getOrNull()?.takeIf { courseId == null || it.courseId == courseId }
    }

    fun start(session: EmergencyDetourSession) {
        prefs.edit()
            .putString(KEY_SESSION, encode(session).toString())
            .putString(KEY_BREADCRUMB, JSONArray().toString())
            .apply()
    }

    fun setPhase(courseId: String, phase: EmergencyPhase, nowMs: Long = System.currentTimeMillis()) {
        val s = active(courseId) ?: return
        prefs.edit().putString(KEY_SESSION, encode(s.copy(phase = phase, phaseStartMs = nowMs)).toString()).apply()
    }

    fun cancelEmergency(courseId: String) {
        val s = active(courseId) ?: return
        prefs.edit().remove(KEY_SESSION).remove(KEY_BREADCRUMB).apply()
    }

    fun appendBreadcrumb(courseId: String, lat: Double, lon: Double, timestampMs: Long = System.currentTimeMillis()) {
        if (active(courseId) == null || !lat.isFinite() || !lon.isFinite()) return
        runCatching {
            val arr = JSONArray(prefs.getString(KEY_BREADCRUMB, "[]") ?: "[]")
            val last = arr.optJSONObject(arr.length() - 1)
            if (last != null) {
                val d = Geo.distanceMeters(last.optDouble("lat"), last.optDouble("lon"), lat, lon)
                val dt = timestampMs - last.optLong("t")
                if (d < 15.0 && dt < 12_000L) return
            }
            arr.put(JSONObject().put("lat", lat).put("lon", lon).put("t", timestampMs))
            val trimmed = if (arr.length() <= MAX_BREADCRUMB) arr else JSONArray().also { out ->
                for (i in (arr.length() - MAX_BREADCRUMB) until arr.length()) out.put(arr.get(i))
            }
            prefs.edit().putString(KEY_BREADCRUMB, trimmed.toString()).apply()
        }
    }

    fun breadcrumb(): List<Pair<Double, Double>> = runCatching {
        val arr = JSONArray(prefs.getString(KEY_BREADCRUMB, "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { it.optDouble("lat") to it.optDouble("lon") }
        }
    }.getOrDefault(emptyList())

    fun recordSuccessfulPlace(session: EmergencyDetourSession) {
        val list = history().toMutableList()
        val idx = list.indexOfFirst { it.id == session.candidateId || Geo.distanceMeters(it.lat, it.lon, session.candidateLat, session.candidateLon) < 80.0 }
        val old = list.getOrNull(idx)
        val item = ConfirmedEmergencyChargePlace(
            id = session.candidateId.ifBlank { old?.id ?: "confirmed_${System.currentTimeMillis()}" },
            name = session.candidateName,
            lat = session.candidateLat,
            lon = session.candidateLon,
            address = session.candidateAddress,
            successCount = (old?.successCount ?: 0) + 1,
            lastUsedMs = System.currentTimeMillis()
        )
        if (idx >= 0) list[idx] = item else list += item
        val arr = JSONArray()
        list.sortedByDescending { it.lastUsedMs }.take(50).forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id); put("name", p.name); put("lat", p.lat); put("lon", p.lon)
                put("address", p.address); put("successCount", p.successCount); put("lastUsedMs", p.lastUsedMs)
            })
        }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    fun history(): List<ConfirmedEmergencyChargePlace> = runCatching {
        val arr = JSONArray(prefs.getString(KEY_HISTORY, "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o ->
                ConfirmedEmergencyChargePlace(
                    id = o.optString("id"), name = o.optString("name", "과거 충전 성공"),
                    lat = o.optDouble("lat"), lon = o.optDouble("lon"), address = o.optString("address"),
                    successCount = o.optInt("successCount", 1), lastUsedMs = o.optLong("lastUsedMs", 0L)
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun encode(s: EmergencyDetourSession) = JSONObject().apply {
        put("courseId", s.courseId); put("anchorRouteKm", s.anchorRouteKm); put("anchorLat", s.anchorLat); put("anchorLon", s.anchorLon)
        put("candidateId", s.candidateId); put("candidateName", s.candidateName); put("candidateLat", s.candidateLat); put("candidateLon", s.candidateLon)
        put("candidateAddress", s.candidateAddress); put("candidateConfidence", s.candidateConfidence)
        put("outboundKm", s.outboundKm); put("outboundMinutes", s.outboundMinutes); put("returnKm", s.returnKm); put("returnMinutes", s.returnMinutes)
        put("outboundUrl", s.outboundUrl); put("returnUrl", s.returnUrl); put("phase", s.phase.name)
        put("startedMs", s.startedMs); put("phaseStartMs", s.phaseStartMs)
    }
}
