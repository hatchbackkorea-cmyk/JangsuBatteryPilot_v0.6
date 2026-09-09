package com.seungjae.jangsu280battery

import android.location.Location
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import kotlin.math.*

/** Signed distance to the FIXED gate, never the clamped GPX progress at the route endpoint. */
class RaceTimingRefiner {
    data class Result(val epochMs: Long, val correctionMs: Long, val uncertaintyMs: Long?, val sampleCount: Int, val method: String, val usable: Boolean, val audit: JSONObject)
    private val fixes = ArrayDeque<Location>()
    fun reset() = fixes.clear()
    fun add(location: Location, routeM: Double) {
        if (location.elapsedRealtimeNanos <= 0L) return
        if (fixes.any { it.elapsedRealtimeNanos == location.elapsedRealtimeNanos }) return
        fixes.addLast(Location(location))
        val cutoff = location.elapsedRealtimeNanos - 8_000_000_000L
        while (fixes.isNotEmpty() && fixes.first.elapsedRealtimeNanos < cutoff) fixes.removeFirst()
        while (fixes.size > 160) fixes.removeFirst()
    }
    fun refine(gate: RaceGate, preliminaryEpochMs: Long): Result? {
        val anchor = fixes.minByOrNull { abs(it.time-preliminaryEpochMs) } ?: return null
        if (abs(anchor.time-preliminaryEpochMs)>8_000L) return null
        val originalNs = anchor.elapsedRealtimeNanos+(preliminaryEpochMs-anchor.time)*1_000_000L
        val selected = fixes.filter { (it.elapsedRealtimeNanos-originalNs)/1e9 in -1.8..2.5 }.sortedBy { it.elapsedRealtimeNanos }
        val rad = Math.toRadians(gate.bearingDeg)
        val lonScale = 111_320.0*cos(Math.toRadians(gate.lat))
        val evidence = JSONArray()
        val samples = selected.map { loc ->
            val x=(loc.longitude-gate.lon)*lonScale
            val y=(loc.latitude-gate.lat)*110_540.0
            val along=x*sin(rad)+y*cos(rad)
            val across=x*cos(rad)-y*sin(rad)
            val speed=if(loc.hasSpeed()&&loc.hasBearing()) loc.speed*cos(Math.toRadians(loc.bearing.toDouble()-gate.bearingDeg)) else null
            val clock=if(Build.VERSION.SDK_INT>=29&&loc.hasElapsedRealtimeUncertaintyNanos()) loc.elapsedRealtimeUncertaintyNanos else 0.0
            @Suppress("DEPRECATION") val mock=loc.isFromMockProvider
            val accuracy=if(loc.hasAccuracy()) loc.accuracy.toDouble() else 0.0
            evidence.put(JSONObject().apply {
                put("t_ns",loc.elapsedRealtimeNanos);put("epoch_ms",loc.time)
                put("lat",loc.latitude);put("lon",loc.longitude);put("accuracy_m",accuracy)
                put("along_m",along);put("across_m",across)
                if(loc.hasSpeed())put("speed_mps",loc.speed.toDouble())
                if(loc.hasBearing())put("bearing_deg",loc.bearing.toDouble())
                put("clock_uncertainty_ns",clock);put("mock",mock)
            })
            GateTimingMath.Fix(loc.elapsedRealtimeNanos,along,across,accuracy,speed,clock,mock)
        }
        val estimate=GateTimingMath.evaluate(samples,originalNs,gate.widthM)
        val correction=((estimate.ns-originalNs)/1_000_000.0).roundToLong()
        val audit=JSONObject().apply {
            put("algorithm",GateTimingMath.ALGORITHM);put("gate",gate.toJson())
            put("original_ns",originalNs);put("final_ns",estimate.ns)
            put("original_epoch_ms",preliminaryEpochMs);put("correction_ms",correction)
            put("margin_ms",estimate.marginMs ?: JSONObject.NULL);put("method",estimate.method)
            put("usable",estimate.usable);put("sample_count",estimate.count);put("actual_hz",estimate.hz)
            put("reasons",JSONArray(estimate.reasons));put("fixes",evidence)
            put("margin_kind","OPERATIONAL_ESTIMATE_NOT_CALIBRATED")
        }
        return Result(preliminaryEpochMs+correction,correction,estimate.marginMs,estimate.count,estimate.method,estimate.usable,audit)
    }
}
