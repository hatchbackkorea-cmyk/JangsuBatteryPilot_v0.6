package com.seungjae.jangsu280battery

import android.content.Context

data class ActiveChargeSession(
    val routeKm: Double,
    val arrivalPct: Double,
    val startMs: Long
)

class ChargingSessionStore(context: Context) {
    companion object {
        private const val PREFS = "charging_session_state"
        private const val KEY_ACTIVE = "active"
        private const val KEY_KM = "route_km"
        private const val KEY_ARRIVAL = "arrival_pct"
        private const val KEY_START = "start_ms"
    }
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun active(): ActiveChargeSession? {
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return null
        return ActiveChargeSession(
            routeKm = prefs.getFloat(KEY_KM, 0f).toDouble(),
            arrivalPct = prefs.getFloat(KEY_ARRIVAL, 0f).toDouble(),
            startMs = prefs.getLong(KEY_START, 0L)
        )
    }

    fun start(routeKm: Double, arrivalPct: Double, startMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putFloat(KEY_KM, routeKm.toFloat())
            .putFloat(KEY_ARRIVAL, arrivalPct.toFloat())
            .putLong(KEY_START, startMs)
            .apply()
    }

    fun clear() = prefs.edit().clear().apply()
}
