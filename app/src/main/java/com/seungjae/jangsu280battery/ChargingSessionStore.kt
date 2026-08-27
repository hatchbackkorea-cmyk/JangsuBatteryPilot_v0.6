package com.seungjae.jangsu280battery

data class ActiveChargeSession(
    val routeKm: Double,
    val arrivalPct: Double,
    val startMs: Long,
    /** 계획주행이면 사용자가 정한 해당 충전소의 충전 계획 SOC, 임의주행이면 설정의 기본 충전 알림 목표. */
    val targetPct: Int? = null,
    val targetAlerted: Boolean = false,
    val fullAlerted: Boolean = false
)

class ChargingSessionStore(context: android.content.Context) {
    companion object {
        private const val PREFS = "charging_session_state"
        private const val KEY_ACTIVE = "active"
        private const val KEY_KM = "route_km"
        private const val KEY_ARRIVAL = "arrival_pct"
        private const val KEY_START = "start_ms"
        private const val KEY_TARGET = "target_pct"
        private const val KEY_TARGET_ALERTED = "target_alerted"
        private const val KEY_FULL_ALERTED = "full_alerted"
    }
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    fun active(): ActiveChargeSession? {
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return null
        return ActiveChargeSession(
            routeKm = prefs.getFloat(KEY_KM, 0f).toDouble(),
            arrivalPct = prefs.getFloat(KEY_ARRIVAL, 0f).toDouble(),
            startMs = prefs.getLong(KEY_START, 0L),
            targetPct = prefs.getInt(KEY_TARGET, -1).takeIf { it in 1..100 },
            targetAlerted = prefs.getBoolean(KEY_TARGET_ALERTED, false),
            fullAlerted = prefs.getBoolean(KEY_FULL_ALERTED, false)
        )
    }

    fun start(routeKm: Double, arrivalPct: Double, startMs: Long = System.currentTimeMillis(), targetPct: Int? = null) {
        prefs.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putFloat(KEY_KM, routeKm.toFloat())
            .putFloat(KEY_ARRIVAL, arrivalPct.toFloat())
            .putLong(KEY_START, startMs)
            .apply {
                if (targetPct != null) putInt(KEY_TARGET, targetPct.coerceIn(1, 100)) else remove(KEY_TARGET)
            }
            .putBoolean(KEY_TARGET_ALERTED, false)
            .putBoolean(KEY_FULL_ALERTED, false)
            .apply()
    }

    fun markTargetAlerted() = prefs.edit().putBoolean(KEY_TARGET_ALERTED, true).apply()
    fun markFullAlerted() = prefs.edit().putBoolean(KEY_FULL_ALERTED, true).apply()

    fun clear() = prefs.edit().clear().apply()
}
