package com.seungjae.jangsu280battery

import kotlin.math.*

/** TG-FAIR-1. Margins are operational screening estimates, NOT calibrated confidence bounds. */
object GateTimingMath {
    const val ALGORITHM = "TG-GATE-1"
    data class Fix(val ns: Long, val along: Double, val across: Double, val accuracy: Double,
                   val speed: Double? = null, val timeErrorNs: Double = 0.0, val mock: Boolean = false)
    data class Estimate(val ns: Long, val originalNs: Long, val marginMs: Long?, val method: String,
                        val reasons: List<String>, val count: Int, val hz: Double) {
        val usable: Boolean get() = marginMs != null && reasons.isEmpty()
    }
    fun evaluate(input: List<Fix>, originalNs: Long, widthM: Double): Estimate {
        fun unknown(reason: String) = Estimate(originalNs, originalNs, null, "PAIR_RETAINED", listOf(reason), input.size, 0.0)
        if (originalNs <= 0L || !widthM.isFinite() || widthM <= 0.0) return unknown("INVALID_GATE")
        if (input.any { it.mock }) return unknown("MOCK_LOCATION")
        if (input.any { !it.along.isFinite() || !it.across.isFinite() || !it.accuracy.isFinite() || it.accuracy <= 0.0 }) return unknown("MISSING_ACCURACY")
        val points = input.filter { (it.ns - originalNs) / 1e9 in -1.8..2.5 }.distinctBy { it.ns }.sortedBy { it.ns }
        if (points.size < 3) return unknown("INSUFFICIENT_FIXES")
        val gaps = points.zipWithNext { a,b -> (b.ns-a.ns)/1e9 }.filter { it > 0.0 }.sorted()
        val hz = if (gaps.isEmpty()) 0.0 else 1.0 / gaps[gaps.size/2]
        val brackets = points.zipWithNext().filter { (a,b) -> a.along < 0.0 && b.along >= 0.0 }
        if (brackets.size != 1) return unknown(if (brackets.isEmpty()) "NO_SIGNED_BRACKET" else "MULTIPLE_CROSSINGS")
        val (a,b) = brackets.single()
        val dt = (b.ns-a.ns)/1e9
        if (dt <= 0.0 || dt > 1.5) return unknown("GATE_SAMPLE_GAP")
        val v = (b.along-a.along)/dt
        if (!v.isFinite() || v < 0.8 || v > 45.0) return unknown("NORMAL_SPEED_UNCERTAIN")
        val frac = -a.along/(b.along-a.along)
        val lateral = a.across+(b.across-a.across)*frac
        if (abs(lateral) > widthM/2.0) return unknown("OUTSIDE_FIXED_GATE")
        val pairNs = a.ns + ((b.ns-a.ns)*frac).roundToLong()
        val reasons = mutableListOf<String>()
        val pre = points.filter { it.ns <= a.ns && (pairNs-it.ns)/1e9 <= 1.8 }.takeLast(8)
        val post = points.filter { it.ns >= b.ns }
        if (post.size < 2) reasons += "NO_FOLLOWUP_FIX"
        else if (post.drop(1).take(2).any { it.along < -0.5 }) reasons += "POST_CROSSING_CONTRADICTION"
        var chosen = pairNs
        var method = "SIGNED_PAIR"
        var modelGapSec = 0.0
        val near = points.filter { it.ns <= b.ns && (pairNs-it.ns)/1e9 <= 1.8 }
        val velocities = near.mapNotNull { it.speed }.filter { it.isFinite() && it >= 0.0 }
        val speedStable = velocities.size == near.size && velocities.isNotEmpty() && velocities.max()-velocities.min() <= max(0.6, velocities.average()*0.20)
        // Later post-gate positions/speed are validation-only, never backward extrapolation.
        if (pre.size >= 2 && (b.ns-pairNs)/1e9 <= 0.55 && speedStable) {
            val fit = linearFit(near, pairNs)
            if (fit != null) {
                val (intercept, slope) = fit
                if (slope >= 0.8 && slope <= 45.0) {
                    val candidate = pairNs + (-intercept/slope*1e9).roundToLong()
                    modelGapSec = abs(candidate-pairNs)/1e9
                    if (candidate in a.ns..b.ns && abs(candidate-originalNs) <= 1_000_000_000L && modelGapSec <= 0.35) {
                        chosen = candidate; method = "LOCAL_SIGNED_FIT"
                    } else reasons += "REFINEMENT_REJECTED"
                }
            }
        }
        if (abs(chosen-originalNs) > 1_000_000_000L) {
            reasons += "CORRECTION_LIMIT_REACHED"
            chosen = originalNs
        }
        if (points.any { it.accuracy > 50.0 }) reasons += "GATE_ACCURACY_POOR"
        val reported = near.mapNotNull { it.speed }.filter { it.isFinite() && it >= 0.0 }
        if (reported.isNotEmpty() && abs(reported.average()-v) > max(2.0, v*0.5)) reasons += "SPEED_POSITION_DISAGREE"
        // Neighbouring GNSS fixes can share bias: no accuracy/sqrt(N), no arbitrary upper cap.
        val positionGuard = 2.0*max(1.0, max(a.accuracy,b.accuracy))
        val clockGuard = max(a.timeErrorNs,b.timeErrorNs).takeIf { it.isFinite() && it >= 0.0 } ?: return unknown("CLOCK_UNCERTAIN")
        val margin = ceil((positionGuard/v + dt/2.0 + modelGapSec + clockGuard/1e9)*1000.0).toLong()
        return Estimate(chosen, originalNs, margin, method, reasons.distinct(), points.size, hz)
    }
    private fun linearFit(points: List<Fix>, origin: Long): Pair<Double,Double>? {
        if (points.size < 3) return null
        val weights = points.map { 1.0/(max(1.0,it.accuracy).pow(2)*(1.0+abs(it.ns-origin)/1e9*2.0)) }
        val sw = weights.sum()
        val xs = points.map { (it.ns-origin)/1e9 }
        val mx = points.indices.sumOf { xs[it]*weights[it] }/sw
        val my = points.indices.sumOf { points[it].along*weights[it] }/sw
        val den = points.indices.sumOf { weights[it]*(xs[it]-mx).pow(2) }
        if (den <= 1e-9) return null
        val slope = points.indices.sumOf { weights[it]*(xs[it]-mx)*(points[it].along-my) }/den
        if (!slope.isFinite()) return null
        return (my-slope*mx) to slope
    }
}
