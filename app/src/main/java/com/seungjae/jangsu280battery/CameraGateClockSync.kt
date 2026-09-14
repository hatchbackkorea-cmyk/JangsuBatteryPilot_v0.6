package com.seungjae.jangsu280battery

import android.os.SystemClock
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Robust wall-clock synchronizer for Camera Gate.
 *
 * v7 adds a clock-discipline layer on top of the v6 multi-source sampler. Repeated syncs are
 * tracked against Android's monotonic clock so each phone learns not only its current offset but
 * also its slow clock drift. That makes START/FINISH phones stay on one time axis for long races
 * instead of treating every resync as an unrelated snapshot.
 */
object CameraGateClockSync {
    data class Result(
        val offsetMs: Double,
        val uncertaintyMs: Double,
        val bestRttMs: Long,
        val source: String,
        val usedSamples: Int,
        val quality: String,
        val driftPpm: Double = 0.0,
        val disciplineSamples: Int = 1
    )

    private data class Sample(
        val offsetMs: Double,
        val effectiveDelayMs: Double,
        val rawRttMs: Long
    )

    private data class Observation(
        val monoMs: Double,
        val offsetMs: Double,
        val uncertaintyMs: Double
    )

    private data class DriftModel(
        val predictedOffsetMs: Double,
        val driftPpm: Double,
        val residualMs: Double,
        val spanMs: Double,
        val samples: Int
    )

    private const val NTP_EPOCH_OFFSET_SECONDS = 2_208_988_800L
    private const val HISTORY_MAX = 24
    private const val HISTORY_MAX_AGE_MS = 60.0 * 60.0 * 1000.0
    private const val DRIFT_MIN_SPAN_MS = 45_000.0
    private const val DRIFT_PPM_LIMIT = 120.0

    private val historyLock = Any()
    private val history = ArrayDeque<Observation>()

    fun measure(baseUrl: String, http: OkHttpClient): Result {
        val base = baseUrl.trimEnd('/')
        require(base.startsWith("http://") || base.startsWith("https://")) {
            "RACE 서버 주소가 없습니다."
        }

        val followUp = synchronized(historyLock) { history.isNotEmpty() }
        val raceProbeCount = if (followUp) 6 else 12
        val ntpProbeCount = if (followUp) 3 else 5
        val candidates = mutableListOf<Result>()

        val raceSamples = mutableListOf<Sample>()
        repeat(raceProbeCount) {
            preciseRaceSample(base, http)?.let(raceSamples::add)
            Thread.sleep(if (followUp) 12L else 20L)
        }
        if (raceSamples.isNotEmpty()) {
            val race = combineBest(raceSamples, "정밀 RACE 서버시각 API")
            candidates += race
            if (race.uncertaintyMs <= 10.0 && race.usedSamples >= 3) return race
        }

        for (host in listOf("time.google.com", "time.cloudflare.com")) {
            val ntpSamples = mutableListOf<Sample>()
            repeat(ntpProbeCount) {
                ntpSample(host)?.let(ntpSamples::add)
                Thread.sleep(12L)
            }
            if (ntpSamples.isNotEmpty()) {
                candidates += combineBest(ntpSamples, "NTP 기준시각 · $host")
            }
        }

        require(candidates.isNotEmpty()) { "정밀 시간 샘플을 얻지 못했습니다." }
        return candidates.minWithOrNull(
            compareBy<Result> { it.uncertaintyMs }
                .thenBy { it.bestRttMs }
                .thenByDescending { it.usedSamples }
        ) ?: error("시간 동기화 결과가 없습니다.")
    }

    /**
     * Stabilizes one-shot measurements and learns clock drift over time.
     *
     * Important: the discipline model never claims better absolute accuracy than the best accepted
     * sync observation. Its purpose is to stop two phones slowly walking away from each other
     * between syncs, not to hide network uncertainty.
     */
    fun stabilize(previousOffsetMs: Double, previousUncertaintyMs: Double, fresh: Result): Result {
        val baseline = stabilizeSnapshot(previousOffsetMs, previousUncertaintyMs, fresh)
        val nowMonoMs = SystemClock.elapsedRealtimeNanos() / 1_000_000.0

        synchronized(historyLock) {
            pruneHistory(nowMonoMs)

            if (fresh.uncertaintyMs.isFinite() && fresh.uncertaintyMs <= 80.0) {
                val accept = if (history.size < 2) {
                    true
                } else {
                    val offsets = history.map { it.offsetMs }.sorted()
                    val uncertainties = history.map { it.uncertaintyMs }.sorted()
                    val center = median(offsets)
                    val typicalUncertainty = median(uncertainties)
                    val tolerance = max(20.0, max(typicalUncertainty, fresh.uncertaintyMs) * 4.0)
                    abs(fresh.offsetMs - center) <= tolerance
                }
                if (accept) {
                    history.addLast(Observation(nowMonoMs, fresh.offsetMs, fresh.uncertaintyMs))
                    while (history.size > HISTORY_MAX) history.removeFirst()
                }
            }

            val model = fitDrift(nowMonoMs)
            if (model == null) {
                val count = history.size
                return baseline.copy(
                    source = if (count >= 2) "${baseline.source} · 자동추적 ${count}회(드리프트 학습중)" else baseline.source,
                    disciplineSamples = max(1, count)
                )
            }

            val bestObservedUncertainty = history.minOf { it.uncertaintyMs }
            val modelUncertainty = max(bestObservedUncertainty, model.residualMs)
            val predicted = model.predictedOffsetMs
            val freshDistance = abs(predicted - baseline.offsetMs)
            val allowedDistance = max(20.0, baseline.uncertaintyMs * 3.0)

            val disciplinedOffset = if (freshDistance <= allowedDistance) {
                // Keep the long-term time axis while allowing the newest high-quality sample to
                // nudge it slightly. This prevents a single asymmetric network request from
                // moving the official clock by tens or hundreds of milliseconds.
                predicted * 0.75 + baseline.offsetMs * 0.25
            } else {
                baseline.offsetMs
            }

            val uncertainty = max(1.0, max(modelUncertainty, min(baseline.uncertaintyMs, modelUncertainty * 1.25)))
            return baseline.copy(
                offsetMs = disciplinedOffset,
                uncertaintyMs = uncertainty,
                source = "${fresh.source} · 자동추적 ${model.samples}회 · 드리프트 ${String.format(Locale.US, "%+.2f", model.driftPpm)} ppm",
                quality = quality(uncertainty),
                driftPpm = model.driftPpm,
                disciplineSamples = model.samples
            )
        }
    }

    private fun stabilizeSnapshot(previousOffsetMs: Double, previousUncertaintyMs: Double, fresh: Result): Result {
        if (!previousUncertaintyMs.isFinite()) return fresh

        val jump = abs(fresh.offsetMs - previousOffsetMs)
        if (jump <= 20.0) {
            val oldWeight = if (previousUncertaintyMs <= fresh.uncertaintyMs) 0.70 else 0.45
            val blended = previousOffsetMs * oldWeight + fresh.offsetMs * (1.0 - oldWeight)
            return fresh.copy(offsetMs = blended)
        }

        val clearlyBetter = fresh.uncertaintyMs <= previousUncertaintyMs * 0.60 ||
            (previousUncertaintyMs > 50.0 && fresh.uncertaintyMs <= 20.0)
        if (clearlyBetter) return fresh

        val keptUncertainty = max(previousUncertaintyMs, fresh.uncertaintyMs)
        return fresh.copy(
            offsetMs = previousOffsetMs,
            uncertaintyMs = keptUncertainty,
            source = "${fresh.source} · 급변 보류",
            quality = quality(keptUncertainty)
        )
    }

    private fun pruneHistory(nowMonoMs: Double) {
        while (history.isNotEmpty() && nowMonoMs - history.first().monoMs > HISTORY_MAX_AGE_MS) {
            history.removeFirst()
        }
    }

    private fun fitDrift(nowMonoMs: Double): DriftModel? {
        if (history.size < 3) return null
        val points = history.toList()
        val first = points.first().monoMs
        val last = points.last().monoMs
        val span = last - first
        if (span < DRIFT_MIN_SPAN_MS) return null

        val ref = last
        var sw = 0.0
        var swx = 0.0
        var swy = 0.0
        var swxx = 0.0
        var swxy = 0.0
        for (p in points) {
            val x = p.monoMs - ref
            val sigma = max(2.0, p.uncertaintyMs)
            val w = 1.0 / (sigma * sigma)
            sw += w
            swx += w * x
            swy += w * p.offsetMs
            swxx += w * x * x
            swxy += w * x * p.offsetMs
        }
        val denom = sw * swxx - swx * swx
        if (abs(denom) < 1e-9 || sw <= 0.0) return null

        val slope = ((sw * swxy - swx * swy) / denom)
            .coerceIn(-DRIFT_PPM_LIMIT / 1_000_000.0, DRIFT_PPM_LIMIT / 1_000_000.0)
        val interceptAtRef = (swy - slope * swx) / sw
        val predictedNow = interceptAtRef + slope * (nowMonoMs - ref)
        val residuals = points.map { p ->
            val predicted = interceptAtRef + slope * (p.monoMs - ref)
            abs(p.offsetMs - predicted)
        }.sorted()
        val residual = max(1.0, median(residuals) * 1.4826)

        return DriftModel(
            predictedOffsetMs = predictedNow,
            driftPpm = slope * 1_000_000.0,
            residualMs = residual,
            spanMs = span,
            samples = points.size
        )
    }

    private fun preciseRaceSample(base: String, http: OkHttpClient): Sample? = try {
        val t1Wall = System.currentTimeMillis().toDouble()
        val t1Mono = SystemClock.elapsedRealtimeNanos()
        val req = Request.Builder()
            .url("$base/api/race/clock?probe=${System.nanoTime()}")
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("Cache-Control", "no-cache")
            .build()

        http.newCall(req).execute().use { response ->
            val t4Mono = SystemClock.elapsedRealtimeNanos()
            val t4Wall = t1Wall + (t4Mono - t1Mono) / 1_000_000.0
            if (!response.isSuccessful) return null

            val json = JSONObject(response.body?.string().orEmpty())
            val rawRtt = ((t4Mono - t1Mono) / 1_000_000L).coerceAtLeast(0L)

            val serverReceive = json.optEpochMs("server_receive_ms")
            val serverSend = json.optEpochMs("server_send_ms")
            if (serverReceive != null && serverSend != null && serverSend >= serverReceive) {
                val serverProcessing = (serverSend - serverReceive).toDouble()
                val effectiveDelay = ((t4Wall - t1Wall) - serverProcessing).coerceAtLeast(0.0)
                val offset = ((serverReceive - t1Wall) + (serverSend - t4Wall)) / 2.0
                return Sample(offset, effectiveDelay, rawRtt)
            }

            val keys = listOf(
                "server_time_ms", "server_ms", "serverTimeMs", "epochMs", "now_ms",
                "nowMs", "time_ms", "timeMs", "timestamp_ms"
            )
            val serverMs = keys.firstNotNullOfOrNull { json.optEpochMs(it) } ?: return null
            Sample(
                offsetMs = serverMs - (t1Wall + t4Wall) / 2.0,
                effectiveDelayMs = rawRtt.toDouble(),
                rawRttMs = rawRtt
            )
        }
    } catch (_: Throwable) {
        null
    }

    private fun ntpSample(host: String): Sample? {
        val packet = ByteArray(48)
        packet[0] = 0x23
        val socket = DatagramSocket()
        return try {
            socket.soTimeout = 1300
            val address = InetAddress.getByName(host)
            val t1Wall = System.currentTimeMillis().toDouble()
            val t1Mono = SystemClock.elapsedRealtimeNanos()
            writeNtpTimestamp(packet, 40, t1Wall)
            socket.send(DatagramPacket(packet, packet.size, address, 123))
            val response = DatagramPacket(packet, packet.size)
            socket.receive(response)
            val t4Mono = SystemClock.elapsedRealtimeNanos()
            val t4Wall = t1Wall + (t4Mono - t1Mono) / 1_000_000.0
            val t2 = readNtpTimestamp(packet, 32)
            val t3 = readNtpTimestamp(packet, 40)
            if (t2 <= 0.0 || t3 <= 0.0) return null

            val delay = ((t4Wall - t1Wall) - (t3 - t2)).coerceAtLeast(0.0)
            val offset = ((t2 - t1Wall) + (t3 - t4Wall)) / 2.0
            val rawRtt = ((t4Mono - t1Mono) / 1_000_000L).coerceAtLeast(0L)
            Sample(offset, delay, rawRtt)
        } catch (_: Throwable) {
            null
        } finally {
            socket.close()
        }
    }

    private fun combineBest(samples: List<Sample>, source: String): Result {
        val valid = samples.filter {
            it.offsetMs.isFinite() && it.effectiveDelayMs.isFinite() &&
                it.effectiveDelayMs >= 0.0 && it.effectiveDelayMs <= 3_000.0
        }
        require(valid.isNotEmpty()) { "유효한 시간 샘플이 없습니다." }

        val fastestDelay = valid.minOf { it.effectiveDelayMs }
        val delayCutoff = max(fastestDelay + 25.0, fastestDelay * 1.8)
        val filtered = valid.filter { it.effectiveDelayMs <= delayCutoff }
            .sortedWith(compareBy<Sample> { it.effectiveDelayMs }.thenBy { it.rawRttMs })
            .take(min(5, valid.size))
            .ifEmpty { valid.sortedBy { it.effectiveDelayMs }.take(1) }

        val offsets = filtered.map { it.offsetMs }.sorted()
        val offset = median(offsets)
        val deviations = filtered.map { abs(it.offsetMs - offset) }.sorted()
        val mad = median(deviations)
        val minDelay = filtered.minOf { it.effectiveDelayMs }
        val minRtt = filtered.minOf { it.rawRttMs }
        val uncertainty = max(1.0, max(minDelay / 2.0, mad * 1.4826))

        return Result(
            offsetMs = offset,
            uncertaintyMs = uncertainty,
            bestRttMs = minRtt,
            source = source,
            usedSamples = filtered.size,
            quality = quality(uncertainty)
        )
    }

    private fun median(values: List<Double>): Double {
        require(values.isNotEmpty())
        val n = values.size
        return if (n % 2 == 1) values[n / 2]
        else (values[n / 2 - 1] + values[n / 2]) / 2.0
    }

    private fun JSONObject.optEpochMs(key: String): Long? {
        if (!has(key)) return null
        return optLong(key).takeIf { it > 1_000_000_000_000L }
    }

    private fun quality(uncertaintyMs: Double): String = when {
        uncertaintyMs <= 10.0 -> "우수"
        uncertaintyMs <= 20.0 -> "좋음"
        uncertaintyMs <= 50.0 -> "보통"
        else -> "불량"
    }

    private fun writeNtpTimestamp(bytes: ByteArray, offset: Int, unixMs: Double) {
        val ntpSeconds = unixMs / 1000.0 + NTP_EPOCH_OFFSET_SECONDS
        val seconds = ntpSeconds.toLong()
        val fraction = ((ntpSeconds - seconds) * 4294967296.0).toLong()
        for (i in 0..3) bytes[offset + i] = (seconds shr (24 - i * 8)).toByte()
        for (i in 0..3) bytes[offset + 4 + i] = (fraction shr (24 - i * 8)).toByte()
    }

    private fun readNtpTimestamp(bytes: ByteArray, offset: Int): Double {
        var seconds = 0L
        var fraction = 0L
        for (i in 0..3) seconds = (seconds shl 8) or (bytes[offset + i].toLong() and 0xff)
        for (i in 0..3) fraction = (fraction shl 8) or (bytes[offset + 4 + i].toLong() and 0xff)
        return ((seconds - NTP_EPOCH_OFFSET_SECONDS) * 1000.0) +
            (fraction * 1000.0 / 4294967296.0)
    }

    fun debugSummary(result: Result): String = String.format(
        Locale.US,
        "%s · offset %+.1f ms · ±%.1f ms · RTT %d ms · n=%d · drift %+.2f ppm · discipline=%d",
        result.source,
        result.offsetMs,
        result.uncertaintyMs,
        result.bestRttMs,
        result.usedSamples,
        result.driftPpm,
        result.disciplineSamples
    )
}
