package com.seungjae.jangsu280battery

import android.content.Context
import kotlin.math.roundToInt

data class ChargeState(
    val active: Boolean,
    val routeKm: Double,
    val targetPct: Double,
    val startPct: Double,
    val startMs: Long,
    val lastPct: Double,
    val lastSampleMs: Long,
    val learnedPctPerMin: Double
) {
    fun elapsedMs(now: Long = System.currentTimeMillis()): Long = if (!active) 0L else (now - startMs).coerceAtLeast(0L)
    fun sessionRatePctPerMin(now: Long = System.currentTimeMillis()): Double? {
        val min = elapsedMs(now) / 60000.0
        val gain = lastPct - startPct
        return if (min >= 1.0 && gain >= 1.0) gain / min else null
    }
    fun effectiveRate(now: Long = System.currentTimeMillis()): Double? = sessionRatePctPerMin(now)?.takeIf { it > 0.05 }
        ?: learnedPctPerMin.takeIf { it > 0.05 }
    fun remainingMinutes(now: Long = System.currentTimeMillis()): Int? {
        val rate = effectiveRate(now) ?: return null
        val rem = (targetPct - lastPct).coerceAtLeast(0.0)
        return (rem / rate).roundToInt().coerceAtLeast(0)
    }
}

class ChargingSessionStore(context: Context) {
    companion object {
        private const val PREFS = "charge_state"
        private const val ACTIVE = "active"
        private const val KM = "km"
        private const val TARGET = "target"
        private const val START_PCT = "start_pct"
        private const val START_MS = "start_ms"
        private const val LAST_PCT = "last_pct"
        private const val LAST_MS = "last_ms"
        private const val LEARNED_RATE = "learned_rate"
    }
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun state(): ChargeState = ChargeState(
        active = prefs.getBoolean(ACTIVE, false),
        routeKm = prefs.getFloat(KM, 0f).toDouble(),
        targetPct = prefs.getFloat(TARGET, 0f).toDouble(),
        startPct = prefs.getFloat(START_PCT, 0f).toDouble(),
        startMs = prefs.getLong(START_MS, 0L),
        lastPct = prefs.getFloat(LAST_PCT, 0f).toDouble(),
        lastSampleMs = prefs.getLong(LAST_MS, 0L),
        learnedPctPerMin = prefs.getFloat(LEARNED_RATE, 0f).toDouble()
    )

    fun start(routeKm: Double, startPct: Double, targetPct: Double): ChargeState {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putBoolean(ACTIVE, true)
            .putFloat(KM, routeKm.toFloat())
            .putFloat(TARGET, targetPct.toFloat())
            .putFloat(START_PCT, startPct.toFloat())
            .putLong(START_MS, now)
            .putFloat(LAST_PCT, startPct.toFloat())
            .putLong(LAST_MS, now)
            .apply()
        return state()
    }

    fun observe(percent: Double): ChargeState {
        val s = state()
        if (!s.active) return s
        val now = System.currentTimeMillis()
        val pct = percent.coerceIn(s.startPct, 100.0)
        prefs.edit().putFloat(LAST_PCT, pct.toFloat()).putLong(LAST_MS, now).apply()
        val updated = state()
        val rate = updated.sessionRatePctPerMin(now)
        if (rate != null && rate in 0.05..20.0) {
            val old = s.learnedPctPerMin
            val learned = if (old > 0.05) old * 0.35 + rate * 0.65 else rate
            prefs.edit().putFloat(LEARNED_RATE, learned.toFloat()).apply()
        }
        return state()
    }

    fun stop(finalPct: Double? = null): ChargeState {
        if (finalPct != null) observe(finalPct)
        prefs.edit().putBoolean(ACTIVE, false).apply()
        return state()
    }

    fun clearSession() {
        prefs.edit().putBoolean(ACTIVE, false).remove(KM).remove(TARGET).remove(START_PCT).remove(START_MS).remove(LAST_PCT).remove(LAST_MS).apply()
    }
}
