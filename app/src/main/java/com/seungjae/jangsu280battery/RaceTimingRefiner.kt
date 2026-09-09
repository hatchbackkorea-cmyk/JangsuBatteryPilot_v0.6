package com.seungjae.jangsu280battery

import android.location.Location
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Refines a gate crossing from several GNSS fixes around the gate instead of trusting one pair.
 *
 * Location.elapsedRealtimeNanos is the primary clock so wall-clock adjustments cannot distort a
 * lap. Route progress provides position along the course and Android's Doppler-derived speed is
 * used when available. A robust median/weighted-regression blend suppresses one-off GPS jumps.
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
            abs(it.epochMs - preliminaryEpochMs) <= WINDOW_MS &&
                it.accuracyM <= MAX_ACCURACY_M &&
                abs(it.routeM - gateRouteM) <= MAX_ROUTE_WINDOW_M
        }
        if (candidates.size < MIN_SAMPLES) return null

        val anchor = candidates.minByOrNull { abs(it.epochMs - preliminaryEpochMs) } ?: return null
        val preliminaryNs = anchor.elapsedNs + (preliminaryEpochMs - anchor.epochMs) * 1_000_000L

        val regression = regressionCrossingNs(candidates, gateRouteM, preliminaryNs)
        val speedPredictions = speedCrossingsNs(candidates, gateRouteM, preliminaryNs)
        val speedMedian = weightedMedian(speedPredictions)

        val chosenNs = when {
            regression != null && speedMedian != null && abs(regression - speedMedian) <= AGREE_NS ->
                ((regression + speedMedian) / 2L)
            regression != null && speedMedian == null -> regression
            speedMedian != null -> speedMedian
            else -> return null
        }
        val limitedNs = chosenNs.coerceIn(
            preliminaryNs - MAX_CORRECTION_NS,
            preliminaryNs + MAX_CORRECTION_NS
        )
        val epochMs = anchor.epochMs + ((limitedNs - anchor.elapsedNs) / 1_000_000L)
        val correctionMs = epochMs - preliminaryEpochMs
        val spreadMs = predictionSpreadMs(speedPredictions, limitedNs)
        val regressionGapMs = if (regression != null && speedMedian != null) abs(regression - speedMedian) / 1_000_000L else 0L
        val uncertaintyMs = max(40L, max(spreadMs, regressionGapMs / 2L)).coerceAtMost(1_500L)
        val method = when {
            regression != null && speedMedian != null -> "ROUTE_REGRESSION+DOPPLER"
            regression != null -> "ROUTE_REGRESSION"
            else -> "DOPPLER_MEDIAN"
        }
        return Result(epochMs, correctionMs, uncertaintyMs, candidates.size, method)
    }

    private fun regressionCrossingNs(samples: List<Fix>, gateRouteM: Double, preliminaryNs: Long): Long? {
        data class P(val x: Double, val y: Double, val w: Double)
        val pts = samples.map {
            val x = (it.elapsedNs - preliminaryNs) / 1_000_000_000.0
            val y = it.routeM - gateRouteM
            val accuracy = max(3.0, it.accuracyM)
            val timeWeight = 1.0 / (1.0 + abs(x))
            P(x, y, timeWeight / (accuracy * accuracy))
        }
        if (pts.size < MIN_SAMPLES) return null
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

        // One robust second pass: reject fixes far from the first fitted trajectory.
        val residuals = pts.map { abs(it.y - (intercept + slope * it.x)) }.sorted()
        val medianResidual = residuals[residuals.size / 2]
        val limit = max(3.0, medianResidual * 2.5 + 1.0)
        val clean = pts.filter { abs(it.y - (intercept + slope * it.x)) <= limit }
        if (clean.size < MIN_SAMPLES) return preliminaryNs + (crossOffsetSec * 1_000_000_000.0).toLong()

        val sw2 = clean.sumOf { it.w }
        val mx2 = clean.sumOf { it.x * it.w } / sw2
        val my2 = clean.sumOf { it.y * it.w } / sw2
        val varX2 = clean.sumOf { it.w * (it.x - mx2) * (it.x - mx2) }
        if (varX2 <= 1e-9) return preliminaryNs + (crossOffsetSec * 1_000_000_000.0).toLong()
        val slope2 = clean.sumOf { it.w * (it.x - mx2) * (it.y - my2) } / varX2
        if (!slope2.isFinite() || slope2 !in 0.6..25.0) return preliminaryNs + (crossOffsetSec * 1_000_000_000.0).toLong()
        val intercept2 = my2 - slope2 * mx2
        val offset2 = -intercept2 / slope2
        if (!offset2.isFinite() || abs(offset2) > 2.0) return preliminaryNs + (crossOffsetSec * 1_000_000_000.0).toLong()
        return preliminaryNs + (offset2 * 1_000_000_000.0).toLong()
    }

    private fun speedCrossingsNs(samples: List<Fix>, gateRouteM: Double, preliminaryNs: Long): List<Pair<Long, Double>> {
        val fallbackSlope = localPositiveSlope(samples)
        return samples.mapNotNull { fix ->
            val speed = fix.speedMps ?: fallbackSlope ?: return@mapNotNull null
            if (speed !in 0.8..25.0) return@mapNotNull null
            val travelSec = (gateRouteM - fix.routeM) / speed
            if (!travelSec.isFinite() || abs(travelSec) > 2.5) return@mapNotNull null
            val predicted = fix.elapsedNs + (travelSec * 1_000_000_000.0).toLong()
            if (abs(predicted - preliminaryNs) > 2_000_000_000L) return@mapNotNull null
            val accuracy = max(3.0, fix.accuracyM)
            val timeDistanceSec = abs(fix.elapsedNs - preliminaryNs) / 1_000_000_000.0
            val weight = (1.0 / (accuracy * accuracy)) * (1.0 / (1.0 + timeDistanceSec))
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
        private const val WINDOW_MS = 1_600L
        private const val MAX_ACCURACY_M = 45.0
        private const val MAX_ROUTE_WINDOW_M = 45.0
        private const val MIN_SAMPLES = 4
        private const val MIN_SPEED_SAMPLES = 3
        private const val AGREE_NS = 800_000_000L
        private const val MAX_CORRECTION_NS = 1_500_000_000L
    }
}
