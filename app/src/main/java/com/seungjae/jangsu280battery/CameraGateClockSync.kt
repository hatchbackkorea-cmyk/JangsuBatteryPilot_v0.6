package com.seungjae.jangsu280battery

import android.os.SystemClock
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Robust wall-clock synchronizer for Camera Gate.
 *
 * The RACE clock endpoint may travel through a public tunnel with highly variable RTT.  This
 * synchronizer therefore uses the server receive/send timestamps when available (NTP-style four
 * timestamp calculation), rejects slow/outlier samples, and compares the result with public NTP
 * instead of trusting the first reachable source.
 */
object CameraGateClockSync {
    data class Result(
        val offsetMs: Double,
        val uncertaintyMs: Double,
        val bestRttMs: Long,
        val source: String,
        val usedSamples: Int,
        val quality: String
    )

    private data class Sample(
        val offsetMs: Double,
        val effectiveDelayMs: Double,
        val rawRttMs: Long
    )

    private const val NTP_EPOCH_OFFSET_SECONDS = 2_208_988_800L

    fun measure(baseUrl: String, http: OkHttpClient): Result {
        val base = baseUrl.trimEnd('/')
        require(base.startsWith("http://") || base.startsWith("https://")) {
            "RACE 서버 주소가 없습니다."
        }

        val candidates = mutableListOf<Result>()

        val raceSamples = mutableListOf<Sample>()
        repeat(12) {
            preciseRaceSample(base, http)?.let(raceSamples::add)
            Thread.sleep(20L)
        }
        if (raceSamples.isNotEmpty()) {
            val race = combineBest(raceSamples, "정밀 RACE 서버시각 API")
            candidates += race
            // A genuinely low-delay local/nearby RACE endpoint is already better than spending
            // several more seconds probing UDP NTP.
            if (race.uncertaintyMs <= 12.0 && race.usedSamples >= 3) return race
        }

        for (host in listOf("time.google.com", "time.cloudflare.com")) {
            val ntpSamples = mutableListOf<Sample>()
            repeat(5) {
                ntpSample(host)?.let(ntpSamples::add)
                Thread.sleep(15L)
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
     * Keeps an already-good correction from being replaced by a noisy one-off resync.
     * Large jumps are accepted only when the new measurement is materially more trustworthy.
     */
    fun stabilize(previousOffsetMs: Double, previousUncertaintyMs: Double, fresh: Result): Result {
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

        return fresh.copy(
            offsetMs = previousOffsetMs,
            uncertaintyMs = max(previousUncertaintyMs, fresh.uncertaintyMs),
            source = "${fresh.source} · 급변 보류",
            quality = quality(max(previousUncertaintyMs, fresh.uncertaintyMs))
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

            // Backward compatibility for old RACE clock endpoints. This sample remains usable,
            // but its entire RTT is treated as network uncertainty so it loses to a better NTP
            // source when the public tunnel is slow.
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
        val median = median(offsets)
        val deviations = filtered.map { abs(it.offsetMs - median) }.sorted()
        val mad = median(deviations)
        val minDelay = filtered.minOf { it.effectiveDelayMs }
        val minRtt = filtered.minOf { it.rawRttMs }

        // Half the best effective network delay is the physical one-way ambiguity floor.
        // MAD protects against route jitter/outlier offsets without letting one slow request
        // inflate every subsequent trigger's displayed uncertainty.
        val uncertainty = max(1.0, max(minDelay / 2.0, mad * 1.4826))
        return Result(
            offsetMs = median,
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
        "%s · offset %+.1f ms · ±%.1f ms · RTT %d ms · n=%d",
        result.source,
        result.offsetMs,
        result.uncertaintyMs,
        result.bestRttMs,
        result.usedSamples
    )
}
