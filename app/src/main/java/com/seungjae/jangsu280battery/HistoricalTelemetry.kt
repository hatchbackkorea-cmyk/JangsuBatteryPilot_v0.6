package com.seungjae.jangsu280battery

import kotlin.math.max

enum class TelemetryState {
    NORMAL,
    CLIMB,
    STEEP_CLIMB,
    DOWNHILL_COAST,
    COASTING,
    STOPPED,
    SENSOR_GAP,
    OUTLIER
}

data class HistoricalTelemetryPoint(
    val timestampMs: Long?,
    val routeKm: Double,
    val lat: Double,
    val lon: Double,
    val elevationM: Double?,
    val speedKph: Double?,
    val cadenceRpm: Double?,
    val riderPowerW: Double?,
    val motorPowerW: Double?,
    val state: TelemetryState = TelemetryState.NORMAL,
    /** Standard FIT heart_rate. Kept separate from learning; used for Strava/export analytics. */
    val heartRateBpm: Double? = null,
    /** FIT battery_soc when present. Avinox files may omit this and phone BLE can fill it later. */
    val batterySocPercent: Double? = null,
    /** FIT ebike_battery_level when present. */
    val ebikeBatteryLevelPercent: Double? = null,
    /** FIT ebike_assist_mode numeric code when present. */
    val ebikeAssistMode: Int? = null,
    /** FIT ebike_assist_level_percent when present. */
    val ebikeAssistLevelPercent: Double? = null,
    val temperatureC: Double? = null
)

data class TelemetrySegmentStats(
    val riderWh: Double,
    val motorWh: Double,
    val avgSpeedKph: Double?,
    val motorActiveRatio: Double,
    val validPowerSeconds: Double,
    val ascentM: Double,
    val descentM: Double,
    val avgRiderPowerW: Double? = null,
    val avgMotorPowerW: Double? = null,
    val avgActiveMotorPowerW: Double? = null,
    val avgCadenceRpm: Double? = null,
    val validCadenceSeconds: Double = 0.0
)

object TelemetryMath {
    fun segmentStats(points: List<HistoricalTelemetryPoint>, fromKm: Double, toKm: Double): TelemetrySegmentStats {
        if (toKm <= fromKm || points.size < 2) return TelemetrySegmentStats(0.0, 0.0, null, 0.0, 0.0, 0.0, 0.0)
        val slice = points.filter { it.routeKm >= fromKm - 0.01 && it.routeKm <= toKm + 0.01 }
        if (slice.size < 2) return TelemetrySegmentStats(0.0, 0.0, null, 0.0, 0.0, 0.0, 0.0)

        var riderWh = 0.0
        var motorWh = 0.0
        var powerSeconds = 0.0
        var motorActiveSeconds = 0.0
        var riderPowerWeighted = 0.0
        var riderPowerSeconds = 0.0
        var motorPowerWeighted = 0.0
        var motorPowerSeconds = 0.0
        var activeMotorPowerWeighted = 0.0
        var activeMotorPowerSeconds = 0.0
        var cadenceWeighted = 0.0
        var cadenceSeconds = 0.0
        var speedWeighted = 0.0
        var speedSeconds = 0.0
        var ascent = 0.0
        var descent = 0.0

        for (i in 1 until slice.size) {
            val a = slice[i - 1]
            val b = slice[i]
            val ta = a.timestampMs
            val tb = b.timestampMs
            val dt = if (ta != null && tb != null) ((tb - ta) / 1000.0).takeIf { it in 0.05..30.0 } else null
            if (dt != null && a.state !in setOf(TelemetryState.OUTLIER, TelemetryState.SENSOR_GAP) && b.state !in setOf(TelemetryState.OUTLIER, TelemetryState.SENSOR_GAP)) {
                val rider = listOfNotNull(a.riderPowerW, b.riderPowerW).averageOrNull()
                val motor = listOfNotNull(a.motorPowerW, b.motorPowerW).averageOrNull()
                val cadence = listOfNotNull(a.cadenceRpm, b.cadenceRpm).averageOrNull()
                if (rider != null) {
                    val r = rider.coerceAtLeast(0.0)
                    riderWh += r * dt / 3600.0
                    riderPowerWeighted += r * dt
                    riderPowerSeconds += dt
                }
                if (motor != null) {
                    val m = motor.coerceAtLeast(0.0)
                    motorWh += m * dt / 3600.0
                    motorPowerWeighted += m * dt
                    motorPowerSeconds += dt
                    powerSeconds += dt
                    if (m > 5.0) {
                        motorActiveSeconds += dt
                        activeMotorPowerWeighted += m * dt
                        activeMotorPowerSeconds += dt
                    }
                }
                if (cadence != null && cadence in 20.0..220.0) {
                    cadenceWeighted += cadence * dt
                    cadenceSeconds += dt
                }
                val speed = listOfNotNull(a.speedKph, b.speedKph).averageOrNull()
                if (speed != null) {
                    speedWeighted += speed.coerceAtLeast(0.0) * dt
                    speedSeconds += dt
                }
            }
            val ea = a.elevationM
            val eb = b.elevationM
            if (ea != null && eb != null && b.routeKm > a.routeKm) {
                val d = eb - ea
                if (d > 0) ascent += d else descent += -d
            }
        }
        return TelemetrySegmentStats(
            riderWh = riderWh,
            motorWh = motorWh,
            avgSpeedKph = if (speedSeconds > 0.0) speedWeighted / speedSeconds else null,
            motorActiveRatio = if (powerSeconds > 0.0) (motorActiveSeconds / powerSeconds).coerceIn(0.0, 1.0) else 0.0,
            validPowerSeconds = powerSeconds,
            ascentM = max(0.0, ascent),
            descentM = max(0.0, descent),
            avgRiderPowerW = if (riderPowerSeconds > 0.0) riderPowerWeighted / riderPowerSeconds else null,
            avgMotorPowerW = if (motorPowerSeconds > 0.0) motorPowerWeighted / motorPowerSeconds else null,
            avgActiveMotorPowerW = if (activeMotorPowerSeconds > 0.0) activeMotorPowerWeighted / activeMotorPowerSeconds else null,
            avgCadenceRpm = if (cadenceSeconds > 0.0) cadenceWeighted / cadenceSeconds else null,
            validCadenceSeconds = cadenceSeconds
        )
    }

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
}
