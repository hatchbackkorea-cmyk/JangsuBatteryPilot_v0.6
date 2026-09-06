package com.seungjae.jangsu280battery

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Secondary confidence layer for RACE gate timing.
 *
 * GNSS/GPS remains the authoritative position source. This class does not dead-reckon a new
 * position from the phone sensors. Instead it combines:
 * - two consecutive GNSS positions and their accuracy/speed/bearing,
 * - GNSS satellite quality (used-in-fix count, average C/N0, constellation diversity),
 * - course progress around the configured gate,
 * - accelerometer/linear-acceleration and gyroscope activity,
 * to reject obvious GNSS teleports and lower confidence for ambiguous crossings.
 *
 * IMU data is deliberately a soft signal because a phone may be mounted in different orientations
 * or carried in a pocket. A quiet IMU alone never rejects a legitimate crossing.
 */
class RaceSensorFusion(context: Context) : SensorEventListener {
    data class Decision(
        val candidate: Boolean,
        val accepted: Boolean,
        val crossingTimeMs: Long?,
        val confidence: Double,
        val weak: Boolean,
        val reason: String
    )

    data class Snapshot(
        val imuAvailable: Boolean,
        val gnssFresh: Boolean,
        val satellitesUsed: Int,
        val averageCn0DbHz: Double,
        val constellationCount: Int,
        val accelActivity: Double,
        val gyroActivity: Double
    )

    private val appContext = context.applicationContext
    private val sensors = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locations = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())

    private var started = false
    private var accelSensor: Sensor? = null
    private var accelIsLinear = false
    private var gyroSensor: Sensor? = null
    private var lastAccelNs = 0L
    private var lastGyroNs = 0L
    private var accelActivity = 0.0
    private var gyroActivity = 0.0

    @Volatile private var gnssAtElapsedMs = 0L
    @Volatile private var satellitesUsed = 0
    @Volatile private var averageCn0DbHz = 0.0
    @Volatile private var constellationCount = 0

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            var cn0Sum = 0.0
            val constellations = HashSet<Int>()
            for (i in 0 until status.satelliteCount) {
                if (!status.usedInFix(i)) continue
                used++
                cn0Sum += status.getCn0DbHz(i).toDouble()
                constellations += status.getConstellationType(i)
            }
            satellitesUsed = used
            averageCn0DbHz = if (used > 0) cn0Sum / used else 0.0
            constellationCount = constellations.size
            gnssAtElapsedMs = SystemClock.elapsedRealtime()
        }
    }

    fun start() {
        if (started) return
        started = true
        accelSensor = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        accelIsLinear = accelSensor != null
        if (accelSensor == null) accelSensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accelSensor?.let { runCatching { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
        gyroSensor?.let { runCatching { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
        runCatching { locations.registerGnssStatusCallback(gnssCallback, handler) }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { sensors.unregisterListener(this) }
        runCatching { locations.unregisterGnssStatusCallback(gnssCallback) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!started || event.values.size < 3) return
        val x = event.values[0].toDouble()
        val y = event.values[1].toDouble()
        val z = event.values[2].toDouble()
        val magnitude = sqrt(x * x + y * y + z * z)
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                accelActivity = ema(accelActivity, magnitude)
                lastAccelNs = event.timestamp
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val linearLike = abs(magnitude - SensorManager.GRAVITY_EARTH.toDouble())
                accelActivity = ema(accelActivity, linearLike)
                lastAccelNs = event.timestamp
            }
            Sensor.TYPE_GYROSCOPE -> {
                gyroActivity = ema(gyroActivity, magnitude)
                lastGyroNs = event.timestamp
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun snapshot(): Snapshot {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val imuFresh = (lastAccelNs > 0L && nowNs - lastAccelNs < 3_000_000_000L) ||
            (lastGyroNs > 0L && nowNs - lastGyroNs < 3_000_000_000L)
        val gnssFresh = gnssAtElapsedMs > 0L && SystemClock.elapsedRealtime() - gnssAtElapsedMs < 5_000L
        return Snapshot(
            imuAvailable = imuFresh,
            gnssFresh = gnssFresh,
            satellitesUsed = satellitesUsed,
            averageCn0DbHz = averageCn0DbHz,
            constellationCount = constellationCount,
            accelActivity = accelActivity,
            gyroActivity = gyroActivity
        )
    }

    fun evaluateCrossing(
        prev: Location,
        cur: Location,
        gate: RaceGate,
        previousRouteM: Double?,
        currentRouteM: Double
    ): Decision {
        val fraction = RaceGateMath.crossingFraction(prev, cur, gate)
            ?: return Decision(false, false, null, 0.0, false, "no_gate_intersection")

        val dtMs = cur.time - prev.time
        if (dtMs <= 0L || dtMs > 10_000L) {
            return Decision(true, false, null, 0.0, true, "invalid_time_gap")
        }

        val prevAcc = if (prev.hasAccuracy()) prev.accuracy.toDouble() else 99.0
        val curAcc = if (cur.hasAccuracy()) cur.accuracy.toDouble() else 99.0
        val worstAccuracy = max(prevAcc, curAcc)
        if (worstAccuracy > 70.0) {
            return Decision(true, false, null, 0.05, true, "gps_accuracy")
        }

        val segmentM = Geo.distanceMeters(prev.latitude, prev.longitude, cur.latitude, cur.longitude)
        val dtSec = dtMs / 1000.0
        val segmentSpeed = if (dtSec > 0.0) segmentM / dtSec else Double.POSITIVE_INFINITY
        if (!segmentSpeed.isFinite() || segmentSpeed > 45.0) {
            return Decision(true, false, null, 0.05, true, "gps_teleport_speed")
        }

        val reportedSpeeds = ArrayList<Double>(2)
        if (prev.hasSpeed()) reportedSpeeds += prev.speed.toDouble()
        if (cur.hasSpeed()) reportedSpeeds += cur.speed.toDouble()
        val reportedSpeed = reportedSpeeds.takeIf { it.isNotEmpty() }?.average()
        if (reportedSpeed != null && segmentSpeed > max(30.0, reportedSpeed * 2.8 + 10.0)) {
            return Decision(true, false, null, 0.10, true, "gps_speed_mismatch")
        }

        if (previousRouteM != null && currentRouteM + 50.0 < previousRouteM) {
            return Decision(true, false, null, 0.10, true, "course_progress_backwards")
        }

        var score = 0.46

        score += when {
            worstAccuracy <= 8.0 -> 0.24
            worstAccuracy <= 15.0 -> 0.19
            worstAccuracy <= 30.0 -> 0.12
            worstAccuracy <= 50.0 -> 0.05
            else -> -0.04
        }

        if (dtSec <= 1.2) score += 0.05
        else if (dtSec <= 2.5) score += 0.02
        else if (dtSec > 4.0) score -= 0.07

        if (previousRouteM != null) {
            val slack = max(30.0, gate.widthM * 4.0)
            val lo = min(previousRouteM, currentRouteM)
            val hi = max(previousRouteM, currentRouteM)
            val routeSupports = lo <= gate.routeM + slack && hi >= gate.routeM - slack
            score += if (routeSupports) 0.11 else -0.08
            if (currentRouteM + 8.0 >= previousRouteM) score += 0.03
        }

        if (reportedSpeed != null) {
            val diff = abs(segmentSpeed - reportedSpeed)
            val tolerance = max(3.0, reportedSpeed * 0.65)
            score += if (diff <= tolerance) 0.07 else -0.05
        }

        if (cur.hasBearing() && cur.hasSpeed() && cur.speed >= 1.5f) {
            val delta = bearingDelta(cur.bearing.toDouble(), gate.bearingDeg)
            score += when {
                delta <= 35.0 -> 0.05
                delta <= 70.0 -> 0.02
                delta >= 120.0 -> -0.08
                else -> 0.0
            }
        }

        val s = snapshot()
        if (s.gnssFresh) {
            score += when {
                s.satellitesUsed >= 12 && s.averageCn0DbHz >= 28.0 && s.constellationCount >= 2 -> 0.08
                s.satellitesUsed >= 8 && s.averageCn0DbHz >= 22.0 -> 0.05
                s.satellitesUsed >= 5 -> 0.01
                s.satellitesUsed in 1..3 -> -0.07
                else -> 0.0
            }
        }

        if (s.imuAvailable) {
            val active = s.accelActivity >= 0.15 || s.gyroActivity >= 0.03
            if (active) score += 0.03
            // Quiet IMU is only a soft warning. Constant-speed riding can legitimately be quiet.
            if (!active && segmentM > max(8.0, gate.widthM * 1.5)) score -= 0.03
        }

        score = score.coerceIn(0.0, 1.0)
        val accepted = score >= 0.50
        val crossingAt = if (accepted) prev.time + (dtMs * fraction).toLong() else null
        return Decision(
            candidate = true,
            accepted = accepted,
            crossingTimeMs = crossingAt,
            confidence = score,
            weak = score < 0.64,
            reason = if (accepted) "accepted" else "low_confidence"
        )
    }

    private fun ema(old: Double, sample: Double): Double = if (old == 0.0) sample else old * 0.86 + sample * 0.14

    private fun bearingDelta(a: Double, b: Double): Double {
        val d = abs((((a - b) + 540.0) % 360.0) - 180.0)
        return d.coerceIn(0.0, 180.0)
    }
}
