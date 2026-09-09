package com.seungjae.jangsu280battery

import android.location.Location
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max

/**
 * Refines a gate crossing from several GNSS fixes around the physical gate.
 *
 * Important rule: samples after the gate are validation-first data. A rider may brake after FINISH
 * or accelerate after START, so later post-gate speed must never be extrapolated backwards as if the
 * rider kept a constant speed. The official crossing estimate is therefore driven mainly by fixes
 * before the gate, plus at most the first one or two post-gate fixes when motion is still continuous.
 * All remaining post-gate fixes only confirm that the rider really progressed through the gate.
 *
 * Location.elapsedRealtimeNanos is the primary clock so wall-clock adjustments cannot distort a
 * lap. Route progress provides position along the course and Android's Doppler-derived speed is
 * used only from the pre-gate side for crossing-time prediction.
 */
class RaceTimingRefiner {
    data class Result(
        val epochMs: Long,
        val correctionMs: Long,
        val uncertaintyMs: Long,
        val sampleCount: Int,
        val method: String
    )

    private data class Fix(
        val epochMs: Long,
        val elapsedNs: Long,
        val routeM: Double,
        val accuracyM: Double,
        val speedMps: Double?
    )

    private val fixes = ArrayDeque<Fix>()

    fun reset() = fixes.clear()

    fun add(location: Location, routeM: Double) {
        val elapsedNs = location.elapsedRealtimeNanos
        if (elapsedNs <= 0L) return
        val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else 99.0
        val speed = location.speed.toDouble().takeIf { location.hasSpeed() && it in 0.5..25.0 }
        fixes.addLast(Fix(location.time, elapsedNs, routeM, accuracy, speed))
        val cutoff = elapsedNs - HISTORY_NS
        while (fixes.isNotEmpty() && fixes.first().elapsedNs < cutoff) fixes.removeFirst()
    }

    fun refine(gateRouteM: Double, preliminaryEpochMs: Long): Result? {
        if (preliminaryEpochMs <= 0L) return null

        val candidates = fixes.filter {
            val dt = it.epochMs - preliminaryEpochMs
            dt >= -PRE_WINDOW_MS && dt <= POST_VALIDATION_WINDOW_MS &&
                it.accuracyM <= MAX_ACCURACY_M &&
                abs(it.routeM - gateRouteM) <= MAX_ROUTE_WINDOW_M
        }.sortedBy { it.elapsedNs }
        if (candidates.size < MIN_TOTAL_SAMPLES) return null

        val anchor = candidates.minByOrNull { abs(it.epochMs - preliminaryEpochMs) } ?: return null
        val preliminaryNs = anchor.elapsedNs + (preliminaryEpochMs - anchor.epochMs) * 1_000_000L

        // Pre-gate fixes are the primary timing evidence. Keep the closest several fixes so a stale
        // point from two seconds earlier cannot dominate a rider who changes speed near the line.
        val pre = candidates
            .filter { it.elapsedNs <= preliminaryNs }
            .takeLast(MAX_PRE_SAMPLES)
        val post = candidates.filter { it.elapsedNs > preliminaryNs }

        if (pre.size < MIN_PRE_SAMPLES) return null

        val preSpeed = robustPreSpeed(pre)
        val safePost = selectContinuousImmediatePost(pre, post, preSpeed, preliminaryNs)
        val calculation = (pre + safePost).sortedBy { it.elapsedNs }

        // Regression may use one or two immediately-adjacent post fixes, but only when speed remains
        // continuous across the gate. Doppler prediction is deliberately PRE-GATE ONLY so braking
        // after FINISH or acceleration after START cannot pull the crossing time backwards/forwards.
        val regression = regressionCrossingNs(calculation, gateRouteM, preliminaryNs)
        val speedPredictions = speedCrossingsNs(pre, gateRouteM, preliminaryNs)
        val speedMedian = weightedMedian(speedPredictions)

        val chosenNs = when {
            regression != null && speedMedian != null && abs(regression - speedMedian) <= AGREE_NS ->
                (regression + speedMedian) / 2L
            speedMedian != null -> speedMedian
            regression != null -> regression
            else -> return null
        }

        val limitedNs = chosenNs.coerceIn(
            preliminaryNs - MAX_CORRECTION_NS,
            preliminaryNs + MAX_CORRECTION_NS
        )
        val epochMs = anchor.epochMs + ((limitedNs - anchor.elapsedNs) / 1_000_000L)
        val correctionMs = epochMs - preliminaryEpochMs

        val speedSpreadMs = predictionSpreadMs(speedPredictions, limitedNs)
        val regressionGapMs = if (regression != null && speedMedian != null) {
            abs(regression - speedMedian) / 1_000_000L
        } else 0L
        val postConfirmed = post.any { it.routeM >= gateRouteM - POST_CONFIRM_ROUTE_TOLERANCE_M }
        val motionChangedAfterGate = post.isNotEmpty() && safePost.isEmpty()
        val validationPenalty = when {
            !postConfirmed -> 300L
            motionChangedAfterGate -> 120L
            else -> 0L
        }
        val uncertaintyMs = max(
            40L,
            max(speedSpreadMs, regressionGapMs / 2L) + validationPenalty
        ).coerceAtMost(1_500L)

        val method = when {
            regression != null && speedMedian != null && safePost.isNotEmpty() -> "PRE_DOPPLER+LOCAL_GATE_FIT"
            regression != null && speedMedian != null -> "PRE_DOPPLER+PRE_ROUTE_FIT"
            speedMedian != null -> "PRE_DOPPLER_MEDIAN"
            safePost.isNotEmpty() -> "LOCAL_GATE_FIT"
            else -> "PRE_ROUTE_FIT"
        }
        return Result(epochMs, correctionMs, uncertaintyMs, calculation.size, method)
    }

    /**
     * Only the first one or two post-gate fixes may participate in the crossing fit, and only if
     * their motion is still compatible with the rider's pre-gate speed. Once braking/acceleration is
     * detected, every later post fix is validation-only.
     */
    private fun selectContinuousImmediatePost(
        pre: List<Fix>,
        post: List<Fix>,
        preSpeed: Double?,
        preliminaryNs: Long
    ): List<Fix> {
        if (post.isEmpty()) return emptyList()
        val lastPre = pre.lastOrNull() ?: return emptyList()
        val selected = mutableListOf<Fix>()

        for (fix in post) {
            if (selected.size >= MAX_POST_CALC_SAMPLES) break
            val afterMs = (fix.elapsedNs - preliminaryNs) / 1_000_000L
            if (afterMs > POST_CALC_WINDOW_MS) break

            val base = preSpeed
            if (base == null) {
                // Without a trustworthy pre-gate speed, permit only the very first adjacent fix.
                if (selected.isEmpty() && afterMs <= POST_CALC_NO_SPEED_WINDOW_MS) selected += fix
                break
            }

            val reference = selected.lastOrNull() ?: lastPre
            val dtSec = (fix.elapsedNs - reference.elapsedNs) / 1_000_000_000.0
            val routeSpeed = if (dtSec > 0.05) (fix.routeM - reference.routeM) / dtSec else Double.NaN
            val observed = when {
                routeSpeed.isFinite() && routeSpeed > 0.0 && fix.speedMps != null -> (routeSpeed + fix.speedMps) / 2.0
                routeSpeed.isFinite() && routeSpeed > 0.0 -> routeSpeed
                fix.speedMps != null -> fix.speedMps
                else -> Double.NaN
            }
            if (!observed.isFinite()) break

            val ratio = observed / base
            if (ratio !in CONTINUOUS_SPEED_RATIO_MIN..CONTINUOUS_SPEED_RATIO_MAX) break
            selected += fix
        }
        return selected
    }

    private fun robustPreSpeed(pre: List<Fix>): Double? {
        val direct = pre.takeLast(4).mapNotNull { it.speedMps }.filter { it in 0.8..25.0 }
        val slope = localPositiveSlope(pre)
        val values = buildList {
            addAll(direct)
            slope?.let { add(it) }
        }.sorted()
        return values.takeIf { it.isNotEmpty() }?.get(values.size / 2)
    }

    private fun regressionCrossingNs(samples: List<Fix>, gateRouteM: Double, preliminaryNs: Long): Long? {
        data class P(val x: Double, val y: Double, val w: Double)
        val pts = samples.map {
            val x = (it.elapsedNs - preliminaryNs) / 1_000_000_000.0
            val y = it.routeM - gateRouteM
            val accuracy = max(3.0, it.accuracyM)
            // Very near fixes dominate. This is intentional: a rider may be changing speed near START
            // or FINISH, so older trajectory samples should not impose a long constant-speed model.
            val timeWeight = 1.0 / (1.0 + abs(x) * 1.8)
            P(x, y, timeWeight / (accuracy * accuracy))
        }
        if (pts.size < MIN_REGRESSION_SAMPLES) return null
        val sw = pts.sumOf { it.w }
        if (sw <= 0.0) return null
        val mx = pts.sumOf { it.x * it.w } / sw
        val my = pts.sumOf { it.y * it.w } / sw
        val varX = pts.sumOf { it.w * (it.x - mx) * (it.x - mx) }
        if (varX <= 1e-9) return null
        val slope = pts.sumOf { it.w * (it.x - mx) * (it.y - my) } / varX
        if (!slope.isFinite() || slope !in 0.6..25.0) return null
        val intercept = my - slope * mx
        val crossOffsetSec = -intercept / slope
        if (!crossOffsetSec.isFinite() || abs(crossOffsetSec) > 2.0) return null

        // Robust second pass removes a single route-match jump without averaging it into the time.
        val residuals = pts.map { abs(it.y - (intercept + slope * it.x)) }.sorted()
        val medianResidual = residuals[residuals.size / 2]
        val limit = max(2.5, medianResidual * 2.3 + 0.8)
        val clean = pts.filter { abs(it.y - (intercept + slope * it.x)) <= limit }
        if (clean.size < MIN_REGRESSION_SAMPLES) {
            return preliminaryNs + (crossOffsetSec * 1_000_000_000.0).toLong()
        }

        val sw2 = clean.sumOf { it.w }
        val mx2 = clean.sumOf { it.x * it.w } / sw2
        val my2 = clean.sumOf { it.y * it.w } / sw2
        val varX2 = clean.sumOf { it.w * (it.x - mx2) * (it.x - mx2) }
        if (varX2 <= 1e-9) return preliminaryNs + (crossOffsetSec * 1_000_000_000.0).toLong()
        val slope2 = clean.sumOf { it.w * (it.x - mx2) * (it.y - my2) } / varX2
        if (!slope2.isFinite() || slope2 !in 0.6..25.0) {
            return preliminaryNs + (crossOffsetSec * 1_000_000_000.0).toLong()
        }
        val intercept2 = my2 - slope2 * mx2
        val offset2 = -intercept2 / slope2
        if (!offset2.isFinite() || abs(offset2) > 2.0) {
            return preliminaryNs + (crossOffsetSec * 1_000_000_000.0).toLong()
        }
        return preliminaryNs + (offset2 * 1_000_000_000.0).toLong()
    }

    private fun speedCrossingsNs(samples: List<Fix>, gateRouteM: Double, preliminaryNs: Long): List<Pair<Long, Double>> {
        val fallbackSlope = localPositiveSlope(samples)
        return samples.mapNotNull { fix ->
            val speed = fix.speedMps ?: fallbackSlope ?: return@mapNotNull null
            if (speed !in 0.8..25.0) return@mapNotNull null
            val travelSec = (gateRouteM - fix.routeM) / speed
            if (!travelSec.isFinite() || travelSec !in -0.25..2.5) return@mapNotNull null
            val predicted = fix.elapsedNs + (travelSec * 1_000_000_000.0).toLong()
            if (abs(predicted - preliminaryNs) > 1_800_000_000L) return@mapNotNull null
            val accuracy = max(3.0, fix.accuracyM)
            val timeDistanceSec = abs(fix.elapsedNs - preliminaryNs) / 1_000_000_000.0
            val weight = (1.0 / (accuracy * accuracy)) * (1.0 / (1.0 + timeDistanceSec * 2.0))
            predicted to weight
        }
    }

    private fun localPositiveSlope(samples: List<Fix>): Double? {
        if (samples.size < 2) return null
        val slopes = mutableListOf<Double>()
        for (i in 1 until samples.size) {
            val a = samples[i - 1]
            val b = samples[i]
            val dt = (b.elapsedNs - a.elapsedNs) / 1_000_000_000.0
            if (dt <= 0.05) continue
            val v = (b.routeM - a.routeM) / dt
            if (v in 0.8..25.0) slopes += v
        }
        if (slopes.isEmpty()) return null
        slopes.sort()
        return slopes[slopes.size / 2]
    }

    private fun weightedMedian(values: List<Pair<Long, Double>>): Long? {
        if (values.size < MIN_SPEED_SAMPLES) return null
        val sorted = values.sortedBy { it.first }
        val total = sorted.sumOf { max(0.0, it.second) }
        if (total <= 0.0) return null
        var acc = 0.0
        for ((value, weight) in sorted) {
            acc += max(0.0, weight)
            if (acc >= total / 2.0) return value
        }
        return sorted.last().first
    }

    private fun predictionSpreadMs(values: List<Pair<Long, Double>>, centerNs: Long): Long {
        if (values.isEmpty()) return 0L
        val deviations = values.map { abs(it.first - centerNs) / 1_000_000L }.sorted()
        return deviations[deviations.size / 2]
    }

    companion object {
        private const val HISTORY_NS = 5_000_000_000L
        private const val PRE_WINDOW_MS = 1_800L
        private const val POST_VALIDATION_WINDOW_MS = 1_000L
        private const val POST_CALC_WINDOW_MS = 550L
        private const val POST_CALC_NO_SPEED_WINDOW_MS = 350L
        private const val MAX_PRE_SAMPLES = 8
        private const val MAX_POST_CALC_SAMPLES = 2
        private const val MAX_ACCURACY_M = 45.0
        private const val MAX_ROUTE_WINDOW_M = 45.0
        private const val POST_CONFIRM_ROUTE_TOLERANCE_M = 2.0
        private const val MIN_TOTAL_SAMPLES = 3
        private const val MIN_PRE_SAMPLES = 2
        private const val MIN_REGRESSION_SAMPLES = 3
        private const val MIN_SPEED_SAMPLES = 2
        private const val CONTINUOUS_SPEED_RATIO_MIN = 0.65
        private const val CONTINUOUS_SPEED_RATIO_MAX = 1.45
        private const val AGREE_NS = 650_000_000L
        private const val MAX_CORRECTION_NS = 1_000_000_000L
    }
}
