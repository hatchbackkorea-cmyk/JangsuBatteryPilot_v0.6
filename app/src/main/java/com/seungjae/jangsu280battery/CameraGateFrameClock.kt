package com.seungjae.jangsu280battery

import java.util.ArrayDeque
import kotlin.math.max

/**
 * Resolves decoded camera PTS timestamps onto Android's monotonic BOOTTIME clock.
 *
 * Some camera HALs report SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN even though the recording-surface
 * timestamps are actually aligned with elapsedRealtimeNanos(). Instead of trusting the metadata
 * flag blindly, this helper validates that relationship at runtime. Once enough consecutive frame
 * timestamps are plausibly in the same domain, the frame PTS itself is used for timing; decoder
 * and GL delivery latency therefore does not move the recorded crossing time.
 */
class CameraGateFrameClock {
    data class Resolution(
        val localWallMs: Long,
        val sourceLabel: String,
        val pipelineAgeMs: Double,
        val ptsUsed: Boolean
    )

    private var characteristicRealtime = false
    private var runtimeAligned = false
    private val frameAgesNs = ArrayDeque<Long>()
    private val captureAgesNs = ArrayDeque<Long>()

    @Volatile var statusLabel: String = "PTS 시각 도메인 확인 중"
        private set

    fun reset(timestampSourceRealtime: Boolean) {
        characteristicRealtime = timestampSourceRealtime
        runtimeAligned = timestampSourceRealtime
        frameAgesNs.clear()
        captureAgesNs.clear()
        statusLabel = if (timestampSourceRealtime) {
            "CAMERA REALTIME · PTS 직접 사용"
        } else {
            "PTS 시각 도메인 런타임 확인 중"
        }
    }

    /** Sparse Camera2 metadata samples are used only as an extra sanity check. */
    fun observeCapture(sensorTimestampNs: Long, callbackMonoNs: Long) {
        if (sensorTimestampNs <= 0L) return
        val age = callbackMonoNs - sensorTimestampNs
        if (age in 0L..MAX_REASONABLE_AGE_NS) {
            captureAgesNs.addLast(age)
            while (captureAgesNs.size > 24) captureAgesNs.removeFirst()
        }
        updateStatus()
    }

    fun resolve(frameTimestampNs: Long, receiveMonoNs: Long, receiveWallMs: Long): Resolution {
        if (frameTimestampNs <= 0L) {
            return Resolution(receiveWallMs, "DIRECT 프레임 수신 시각 · PTS 없음", Double.NaN, false)
        }

        val ageNs = receiveMonoNs - frameTimestampNs
        val plausible = ageNs in 0L..MAX_REASONABLE_AGE_NS
        if (plausible) {
            frameAgesNs.addLast(ageNs)
            while (frameAgesNs.size > 48) frameAgesNs.removeFirst()

            if (!runtimeAligned && frameAgesNs.size >= MIN_FRAME_SAMPLES) {
                val ages = frameAgesNs.toList().sorted()
                val median = ages[ages.size / 2]
                val p10 = ages[(ages.size * 0.10).toInt().coerceIn(0, ages.lastIndex)]
                val p90 = ages[(ages.size * 0.90).toInt().coerceIn(0, ages.lastIndex)]
                val spread = p90 - p10
                // A real camera PTS should trail BOOTTIME by a positive, bounded and fairly stable
                // encode/decode latency. A random/relative media clock will fail these checks.
                if (median in 0L..MAX_MEDIAN_AGE_NS && spread <= MAX_SPREAD_NS) {
                    runtimeAligned = true
                }
            }
        }

        updateStatus()

        if ((characteristicRealtime || runtimeAligned) && plausible) {
            val localWall = receiveWallMs - ageNs / 1_000_000L
            val source = if (characteristicRealtime) {
                "DIRECT PTS 프레임시각 · CAMERA REALTIME"
            } else {
                "DIRECT PTS 프레임시각 · BOOTTIME 런타임 정렬 확인"
            }
            return Resolution(localWall, source, ageNs / 1_000_000.0, true)
        }

        return Resolution(
            receiveWallMs,
            "DIRECT 프레임 수신 시각 · PTS 도메인 검증 대기",
            if (plausible) ageNs / 1_000_000.0 else Double.NaN,
            false
        )
    }

    private fun updateStatus() {
        val frameMedian = medianMs(frameAgesNs)
        val captureMedian = medianMs(captureAgesNs)
        statusLabel = when {
            characteristicRealtime -> {
                if (frameMedian != null) "CAMERA REALTIME · PTS 직접 사용 · 파이프라인 ${fmt(frameMedian)} ms"
                else "CAMERA REALTIME · PTS 직접 사용"
            }
            runtimeAligned -> {
                val suffix = if (frameMedian != null) " · 파이프라인 ${fmt(frameMedian)} ms" else ""
                "BOOTTIME 런타임 정렬 확인 · PTS 직접 사용$suffix"
            }
            frameAgesNs.isNotEmpty() -> {
                val n = frameAgesNs.size
                val meta = if (captureMedian != null) " · 메타 ${fmt(captureMedian)} ms" else ""
                "PTS 정렬 확인 중 $n/$MIN_FRAME_SAMPLES · 프레임 ${fmt(frameMedian ?: 0.0)} ms$meta"
            }
            else -> "PTS 시각 도메인 런타임 확인 중"
        }
    }

    private fun medianMs(values: ArrayDeque<Long>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.toList().sorted()
        return sorted[sorted.size / 2] / 1_000_000.0
    }

    private fun fmt(v: Double): String = "%.1f".format(java.util.Locale.US, max(0.0, v))

    companion object {
        private const val MIN_FRAME_SAMPLES = 12
        private const val MAX_REASONABLE_AGE_NS = 2_000_000_000L
        private const val MAX_MEDIAN_AGE_NS = 1_000_000_000L
        private const val MAX_SPREAD_NS = 250_000_000L
    }
}
