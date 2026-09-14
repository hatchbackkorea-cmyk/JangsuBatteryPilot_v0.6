package com.seungjae.jangsu280battery

import android.content.Context
import android.os.Build
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Sends Camera Gate trigger measurements to the RACE server for two-phone comparison. */
object CameraGateTriggerReporter {
    data class Snapshot(
        val triggerIndex: Int,
        val localMs: Long,
        val correctedMs: Long,
        val clockOffsetMs: Double,
        val clockUncertaintyMs: Double,
        val clockQuality: String,
        val metadataFps: Double,
        val streamFps: Double,
        val analysisFps: Double,
        val frameSource: String
    )

    data class PairResult(
        val paired: Boolean,
        val deltaMs: Double = 0.0,
        val absDeltaMs: Double = 0.0,
        val peerLabel: String = "",
        val pairId: Long = 0L,
        val pairCount: Int = 0,
        val within10msCount: Int = 0
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun report(context: Context, baseUrl: String, snapshot: Snapshot, callback: (PairResult?) -> Unit) {
        val base = baseUrl.trim().trimEnd('/')
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            callback(null)
            return
        }
        val app = context.applicationContext
        executor.execute {
            val result = runCatching {
                val deviceId = installId(app)
                val deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL} · ${deviceId.takeLast(4)}"
                val payload = JSONObject().apply {
                    put("schema", 1)
                    put("device_id", deviceId)
                    put("device_label", deviceLabel)
                    put("app_version", BuildConfig.VERSION_NAME)
                    put("trigger_index", snapshot.triggerIndex)
                    put("local_ms", snapshot.localMs)
                    put("corrected_ms", snapshot.correctedMs)
                    put("clock_offset_ms", snapshot.clockOffsetMs)
                    put("clock_uncertainty_ms", snapshot.clockUncertaintyMs)
                    put("clock_quality", snapshot.clockQuality)
                    put("metadata_fps", snapshot.metadataFps)
                    put("stream_fps", snapshot.streamFps)
                    put("analysis_fps", snapshot.analysisFps)
                    put("frame_source", snapshot.frameSource.take(220))
                }
                val request = Request.Builder()
                    .url("$base/api/race/camera-gate/trigger")
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .header("Cache-Control", "no-cache")
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val json = JSONObject(response.body?.string().orEmpty())
                    val pair = json.optJSONObject("pair")
                    val stats = json.optJSONObject("stats")
                    if (pair == null) {
                        PairResult(
                            paired = false,
                            pairCount = stats?.optInt("pair_count", 0) ?: 0,
                            within10msCount = stats?.optInt("within_10ms_count", 0) ?: 0
                        )
                    } else {
                        PairResult(
                            paired = true,
                            deltaMs = pair.optDouble("delta_ms", 0.0),
                            absDeltaMs = pair.optDouble("abs_delta_ms", 0.0),
                            peerLabel = pair.optString("peer_label", "다른 폰"),
                            pairId = pair.optLong("pair_id", 0L),
                            pairCount = stats?.optInt("pair_count", 0) ?: 0,
                            within10msCount = stats?.optInt("within_10ms_count", 0) ?: 0
                        )
                    }
                }
            }.getOrNull()
            callback(result)
        }
    }

    private fun installId(context: Context): String {
        val prefs = context.getSharedPreferences("camera_gate_test", Context.MODE_PRIVATE)
        val existing = prefs.getString("install_id", null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString().replace("-", "").take(12)
        prefs.edit().putString("install_id", created).apply()
        return created
    }
}
