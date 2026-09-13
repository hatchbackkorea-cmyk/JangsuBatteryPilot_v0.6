package com.seungjae.jangsu280battery

import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Resolves the decoded high-speed stream PTS onto Android BOOTTIME.
 *
 * On several Samsung devices Camera2 SENSOR_TIMESTAMP is REALTIME/BOOTTIME, while MediaCodec
 * rebases the recording-surface timestamps onto a relative media timeline. In that case the raw
 * decoded SurfaceTexture timestamp cannot be subtracted from elapsedRealtimeNanos() directly.
 *
 * This helper supports both cases:
 *  1) raw frame PTS is already BOOTTIME -> use it directly;
 *  2) MediaCodec rebased PTS -> learn a stable offset from Camera2 SENSOR_TIMESTAMP samples and
 *     map the media PTS back to CAMERA REALTIME before converting it to wall clock time.
 *
 * Calibration is deliberately runtime-validated. Camera metadata is ~60 FPS in a 120 FPS
 * constrained-high-speed session, so each metadata timestamp is matched to the nearest recording
 * PTS after an initial first-frame anchor. Once enough matches agree, decoder/GL delivery latency
 * no longer changes the recorded crossing time.
 */
class CameraGateFrameClock {
    data class Resolution(
        val localWallMs: Long,
        val sourceLabel: String,
        val pipelineAgeMs: Double,
        val ptsUsed: Boolean
    )

    private var characteristicRealtime = false
    private var runtimeDirectAligned = false
    private var relativeCalibrated = false
    private var ptsToBootOffsetNs: Long? = null
    private var calibrationUncertaintyMs = Double.NaN

    private val frameAgesNs = ArrayDeque<Long>()
    private val captureAgesNs = ArrayDeque<Long>()
    private val framePtsNs = ArrayDeque<Long>()
    private val captureSensorNs = ArrayDeque<Long>()

    @Volatile var statusLabel: String = "PTS 시각 도메인 확인 중"
        private set

    fun reset(timestampSourceRealtime: Boolean) {
        characteristicRealtime = timestampSourceRealtime
        runtimeDirectAligned = false
        relativeCalibrated = false
        ptsToBootOffsetNs = null
        calibrationUncertaintyMs = Double.NaN
        frameAgesNs.clear()
        captureAgesNs.clear()
        framePtsNs.clear()
        captureSensorNs.clear()
        statusLabel = if (timestampSourceRealtime) {
            "CAMERA REALTIME 메타 · PTS 보정 준비"
        } else {
            "PTS 시각 도메인 런타임 확인 중"
        }
    }

    /** Camera2 SENSOR_TIMESTAMP samples provide the absolute CAMERA REALTIME anchor. */
    fun observeCapture(sensorTimestampNs: Long, callbackMonoNs: Long) {
        if (sensorTimestampNs <= 0L) return

        val callbackAge = callbackMonoNs - sensorTimestampNs
        if (callbackAge in 0L..MAX_REASONABLE_AGE_NS) {
            captureAgesNs.addLast(callbackAge)
            while (captureAgesNs.size > MAX_CAPTURE_AGES) captureAgesNs.removeFirst()
        }

        if (captureSensorNs.isEmpty() || sensorTimestampNs > captureSensorNs.last()) {
            captureSensorNs.addLast(sensorTimestampNs)
            while (captureSensorNs.size > MAX_CAPTURE_SAMPLES) captureSensorNs.removeFirst()
        }

        tryRelativeCalibration()
        updateStatus()
    }

    fun resolve(frameTimestampNs: Long, receiveMonoNs: Long, receiveWallMs: Long): Resolution {
        if (frameTimestampNs <= 0L) {
            return Resolution(receiveWallMs, "DIRECT 프레임 수신 시각 · PTS 없음", Double.NaN, false)
        }

        if (framePtsNs.isEmpty() || frameTimestampNs > framePtsNs.last()) {
            framePtsNs.addLast(frameTimestampNs)
            while (framePtsNs.size > MAX_FRAME_SAMPLES) framePtsNs.removeFirst()
        }

        val rawAgeNs = receiveMonoNs - frameTimestampNs
        val rawPlausible = rawAgeNs in 0L..MAX_REASONABLE_AGE_NS
        if (rawPlausible) {
            frameAgesNs.addLast(rawAgeNs)
            while (frameAgesNs.size > MAX_FRAME_AGES) frameAgesNs.removeFirst()
            validateDirectTimeline()
        }

        tryRelativeCalibration()

        // Best case: the decoded PTS itself is already CAMERA REALTIME/BOOTTIME.
        if (rawPlausible && (characteristicRealtime || runtimeDirectAligned)) {
            val localWall = receiveWallMs - rawAgeNs / 1_000_000L
            updateStatus()
            val source = if (characteristicRealtime) {
                "DIRECT PTS 프레임시각 · CAMERA REALTIME"
            } else {
                "DIRECT PTS 프레임시각 · BOOTTIME 런타임 정렬 확인"
            }
            return Resolution(localWall, source, rawAgeNs / 1_000_000.0, true)
        }

        // MediaCodec-rebased case: map relative media PTS back onto CAMERA REALTIME.
        val offset = ptsToBootOffsetNs
        if (relativeCalibrated && offset != null) {
            val mappedBootNs = frameTimestampNs + offset
            val mappedAgeNs = receiveMonoNs - mappedBootNs
            if (mappedAgeNs in 0L..MAX_REASONABLE_AGE_NS) {
                val localWall = receiveWallMs - mappedAgeNs / 1_000_000L
                updateStatus(mappedAgeNs)
                val uncertainty = if (calibrationUncertaintyMs.isFinite()) {
                    " · 보정불확도 ±${fmt(calibrationUncertaintyMs)} ms"
                } else ""
                return Resolution(
                    localWall,
                    "DIRECT PTS 프레임시각 · CAMERA REALTIME 보정 완료$uncertainty",
                    mappedAgeNs / 1_000_000.0,
                    true
                )
            }
        }

        updateStatus()
        return Resolution(
            receiveWallMs,
            if (ptsToBootOffsetNs != null) {
                "DIRECT 프레임 수신 시각 · PTS CAMERA REALTIME 보정 검증 중"
            } else {
                "DIRECT 프레임 수신 시각 · PTS 도메인 검증 대기"
            },
            if (rawPlausible) rawAgeNs / 1_000_000.0 else Double.NaN,
            false
        )
    }

    private fun validateDirectTimeline() {
        if (runtimeDirectAligned || frameAgesNs.size < MIN_FRAME_SAMPLES) return
        val ages = frameAgesNs.toList().sorted()
        val median = ages[ages.size / 2]
        val p10 = ages[(ages.size * 0.10).toInt().coerceIn(0, ages.lastIndex)]
        val p90 = ages[(ages.size * 0.90).toInt().coerceIn(0, ages.lastIndex)]
        val spread = p90 - p10
        if (median in 0L..MAX_MEDIAN_AGE_NS && spread <= MAX_SPREAD_NS) {
            runtimeDirectAligned = true
        }
    }

    /**
     * Learns CAMERA_REALTIME ~= mediaPTS + offset.
     *
     * The first metadata/frame pair establishes the phase. We then repeatedly match each sparse
     * Camera2 sensor timestamp to the nearest 120 FPS media PTS and use the median offset. A wrong
     * or unstable timeline fails the residual test and timing safely stays on receive time.
     */
    private fun tryRelativeCalibration() {
        if (relativeCalibrated || framePtsNs.size < MIN_CAL_FRAME_SAMPLES || captureSensorNs.size < MIN_CAL_CAPTURE_SAMPLES) return
        if (!cameraSensorClockTrusted()) return

        var offset = ptsToBootOffsetNs
            ?: (captureSensorNs.first() - framePtsNs.first()).also { ptsToBootOffsetNs = it }

        var matchedOffsets = emptyList<Long>()
        repeat(3) {
            val candidates = mutableListOf<Long>()
            for (sensorNs in captureSensorNs) {
                val nearest = nearestFramePts(sensorNs, offset) ?: continue
                val residual = abs((nearest + offset) - sensorNs)
                if (residual <= MATCH_WINDOW_NS) candidates += sensorNs - nearest
            }
            if (candidates.size < MIN_CAL_MATCHES) return
            candidates.sort()
            offset = candidates[candidates.size / 2]
            matchedOffsets = candidates
        }

        val residuals = mutableListOf<Long>()
        for (sensorNs in captureSensorNs) {
            val nearest = nearestFramePts(sensorNs, offset) ?: continue
            val residual = abs((nearest + offset) - sensorNs)
            if (residual <= MATCH_WINDOW_NS) residuals += residual
        }
        if (residuals.size < MIN_CAL_MATCHES) return
        residuals.sort()
        val p90 = residuals[(residuals.size * 0.90).toInt().coerceIn(0, residuals.lastIndex)]
        if (p90 > MAX_CAL_RESIDUAL_NS) return

        val framePeriodNs = medianFramePeriodNs()
        // First-frame phase can be ambiguous by roughly one high-speed frame on some HALs.
        // Report that conservatively instead of pretending sub-frame absolute accuracy.
        val phaseAllowanceNs = framePeriodNs ?: DEFAULT_120_FRAME_NS
        calibrationUncertaintyMs = max(p90.toDouble(), phaseAllowanceNs.toDouble()) / 1_000_000.0
        ptsToBootOffsetNs = offset
        relativeCalibrated = true
    }

    private fun nearestFramePts(sensorNs: Long, offsetNs: Long): Long? {
        var best: Long? = null
        var bestError = Long.MAX_VALUE
        for (pts in framePtsNs) {
            val error = abs((pts + offsetNs) - sensorNs)
            if (error < bestError) {
                bestError = error
                best = pts
            }
        }
        return best
    }

    private fun cameraSensorClockTrusted(): Boolean {
        if (characteristicRealtime) return true
        if (captureAgesNs.size < MIN_CAPTURE_CLOCK_SAMPLES) return false
        val ages = captureAgesNs.toList().sorted()
        val median = ages[ages.size / 2]
        val p10 = ages[(ages.size * 0.10).toInt().coerceIn(0, ages.lastIndex)]
        val p90 = ages[(ages.size * 0.90).toInt().coerceIn(0, ages.lastIndex)]
        return median in 0L..MAX_MEDIAN_AGE_NS && (p90 - p10) <= MAX_SPREAD_NS
    }

    private fun medianFramePeriodNs(): Long? {
        if (framePtsNs.size < 4) return null
        val values = framePtsNs.toList()
        val deltas = ArrayList<Long>(values.size - 1)
        for (i in 1 until values.size) {
            val d = values[i] - values[i - 1]
            if (d in MIN_FRAME_PERIOD_NS..MAX_FRAME_PERIOD_NS) deltas += d
        }
        if (deltas.isEmpty()) return null
        deltas.sort()
        return deltas[deltas.size / 2]
    }

    private fun updateStatus(mappedAgeNs: Long? = null) {
        val rawMedian = medianMs(frameAgesNs)
        val captureMedian = medianMs(captureAgesNs)
        statusLabel = when {
            relativeCalibrated -> {
                val age = mappedAgeNs?.let { " · 파이프라인 ${fmt(it / 1_000_000.0)} ms" } ?: ""
                val uncertainty = if (calibrationUncertaintyMs.isFinite()) " · 보정 ±${fmt(calibrationUncertaintyMs)} ms" else ""
                "CAMERA REALTIME 보정 완료 · PTS 직접 사용$uncertainty$age"
            }
            rawMedian != null && (characteristicRealtime || runtimeDirectAligned) -> {
                val prefix = if (characteristicRealtime) "CAMERA REALTIME" else "BOOTTIME 런타임 정렬 확인"
                "$prefix · PTS 직접 사용 · 파이프라인 ${fmt(rawMedian)} ms"
            }
            ptsToBootOffsetNs != null -> {
                "상대 PTS → CAMERA REALTIME 보정 검증 중 · 메타 ${captureSensorNs.size} · 프레임 ${framePtsNs.size}"
            }
            characteristicRealtime -> {
                "CAMERA REALTIME 메타 · MediaCodec PTS 기준 보정 대기"
            }
            frameAgesNs.isNotEmpty() -> {
                val meta = if (captureMedian != null) " · 메타 ${fmt(captureMedian)} ms" else ""
                "PTS 정렬 확인 중 ${frameAgesNs.size}/$MIN_FRAME_SAMPLES · 프레임 ${fmt(rawMedian ?: 0.0)} ms$meta"
            }
            else -> "PTS 시각 도메인 런타임 확인 중"
        }
    }

    private fun medianMs(values: ArrayDeque<Long>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.toList().sorted()
        return sorted[sorted.size / 2] / 1_000_000.0
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.1f", max(0.0, v))

    companion object {
        private const val MIN_FRAME_SAMPLES = 12
        private const val MIN_CAL_FRAME_SAMPLES = 12
        private const val MIN_CAL_CAPTURE_SAMPLES = 6
        private const val MIN_CAL_MATCHES = 6
        private const val MIN_CAPTURE_CLOCK_SAMPLES = 6

        private const val MAX_FRAME_AGES = 48
        private const val MAX_CAPTURE_AGES = 32
        private const val MAX_FRAME_SAMPLES = 240
        private const val MAX_CAPTURE_SAMPLES = 120

        private const val MAX_REASONABLE_AGE_NS = 2_000_000_000L
        private const val MAX_MEDIAN_AGE_NS = 1_000_000_000L
        private const val MAX_SPREAD_NS = 250_000_000L
        private const val MATCH_WINDOW_NS = 20_000_000L
        private const val MAX_CAL_RESIDUAL_NS = 7_000_000L

        private const val MIN_FRAME_PERIOD_NS = 3_000_000L
        private const val MAX_FRAME_PERIOD_NS = 20_000_000L
        private const val DEFAULT_120_FRAME_NS = 8_333_333L
    }
}
