package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Range
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * Camera Gate v14 regular-FPS compatibility shim.
 *
 * v13 preferred advertised 60 FPS ranges, but a few Samsung phones can actually record 60 FPS
 * while Camera2 exposes only a fixed 30-30 regular range. v14 therefore performs a guarded 60 FPS
 * probe after the failed 120 FPS path: advertised 60 first, otherwise a forced 60-60 request when
 * the high-speed capability table proves the sensor/pipeline is 60+ capable. The real metadata and
 * analysis FPS are then verified. If the forced probe cannot sustain roughly 50+ FPS, Camera Gate
 * fully restarts the device once more and falls back to the advertised 30 FPS range.
 */
class CameraGateRegularFpsProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val probeJobs = WeakHashMap<Activity, Runnable>()
    private val forcedProbe = WeakHashMap<Activity, Boolean>()
    private val fallbackSeenAtMs = WeakHashMap<Activity, Long>()

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(this)
        return true
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is CameraGateHighSpeedActivity) return
        main.post { markV14(activity.window.decorView) }
        main.postDelayed({ if (!activity.isFinishing && !activity.isDestroyed) markV14(activity.window.decorView) }, 1_000L)
        prepareRegular60ProbeWithRetry(activity, 0)
        startProbeWatchdog(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        stopProbeWatchdog(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        stopProbeWatchdog(activity)
        forcedProbe.remove(activity)
        fallbackSeenAtMs.remove(activity)
    }

    private fun prepareRegular60ProbeWithRetry(activity: CameraGateHighSpeedActivity, attempt: Int) {
        if (activity.isFinishing || activity.isDestroyed) return
        val ready = prepareRegular60Probe(activity)
        if (!ready && attempt < MAX_DISCOVERY_RETRY) {
            main.postDelayed({ prepareRegular60ProbeWithRetry(activity, attempt + 1) }, DISCOVERY_RETRY_MS)
        }
    }

    private fun prepareRegular60Probe(activity: CameraGateHighSpeedActivity): Boolean = runCatching {
        val cameraId = readField(activity, "cameraId")?.toString().orEmpty()
        if (cameraId.isBlank()) return@runCatching false

        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val ranges = characteristics
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.toList()
            .orEmpty()

        val advertised60 = chooseBest60Range(ranges)
        if (advertised60 != null) {
            writeField(activity, "regularRange", advertised60)
            forcedProbe[activity] = false
            return@runCatching true
        }

        // Some vendor camera apps drive 60 FPS through a path that is not advertised as a regular
        // AE range. Only probe 60 when the high-speed table confirms this camera is at least 60-capable.
        if (hasHighSpeed60Capability(characteristics)) {
            writeField(activity, "regularRange", Range(60, 60))
            forcedProbe[activity] = true
            appendSupportNote(activity, "60 FPS 강제 프로브 예약")
        } else {
            forcedProbe[activity] = false
        }
        true
    }.getOrDefault(false)

    private fun chooseBest60Range(ranges: List<Range<Int>>): Range<Int>? {
        ranges.firstOrNull { it.lower == 60 && it.upper == 60 }?.let { return it }
        return ranges
            .filter { it.lower <= 60 && it.upper >= 60 }
            .sortedWith(
                compareBy<Range<Int>> { abs(it.upper - 60) }
                    .thenByDescending { it.lower }
                    .thenBy { it.upper }
            )
            .firstOrNull()
    }

    private fun chooseSafe30Range(ranges: List<Range<Int>>): Range<Int>? {
        ranges.firstOrNull { it.lower == 30 && it.upper == 30 }?.let { return it }
        return ranges
            .filter { it.lower <= 30 && it.upper >= 30 }
            .sortedWith(compareBy<Range<Int>> { abs(it.upper - 30) }.thenByDescending { it.lower })
            .firstOrNull()
    }

    private fun hasHighSpeed60Capability(characteristics: CameraCharacteristics): Boolean {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
        return runCatching {
            map.highSpeedVideoSizes.any { size ->
                map.getHighSpeedVideoFpsRangesFor(size).any { range -> range.upper >= 60 }
            }
        }.getOrDefault(false)
    }

    private fun startProbeWatchdog(activity: CameraGateHighSpeedActivity) {
        stopProbeWatchdog(activity)
        val job = object : Runnable {
            override fun run() {
                if (activity.isFinishing || activity.isDestroyed) {
                    probeJobs.remove(activity)
                    return
                }

                if (forcedProbe[activity] != true) {
                    main.postDelayed(this, PROBE_CHECK_MS)
                    return
                }

                val fallbackStarted = readBooleanField(activity, "highSpeedFallbackStarted")
                val highSpeedActive = readBooleanField(activity, "highSpeedActive")
                if (!fallbackStarted || highSpeedActive) {
                    main.postDelayed(this, PROBE_CHECK_MS)
                    return
                }

                val now = SystemClock.elapsedRealtime()
                val firstSeen = fallbackSeenAtMs[activity]
                if (firstSeen == null) {
                    fallbackSeenAtMs[activity] = now
                    main.postDelayed(this, PROBE_CHECK_MS)
                    return
                }

                val metadataFps = readDoubleField(activity, "metadataFps")
                val analysisFps = readDoubleField(activity, "analysisFps")
                if (metadataFps >= MIN_ACCEPTED_60_METADATA_FPS && analysisFps >= MIN_ACCEPTED_60_ANALYSIS_FPS) {
                    forcedProbe[activity] = false
                    appendSupportNote(activity, "60 FPS 강제 프로브 성공")
                    probeJobs.remove(activity)
                    return
                }

                if (now - firstSeen >= PROBE_VERIFY_WINDOW_MS) {
                    forcedProbe[activity] = false
                    fallbackSeenAtMs.remove(activity)
                    forceSafe30Restart(activity, metadataFps, analysisFps)
                    probeJobs.remove(activity)
                    return
                }

                main.postDelayed(this, PROBE_CHECK_MS)
            }
        }
        probeJobs[activity] = job
        main.postDelayed(job, PROBE_CHECK_MS)
    }

    private fun stopProbeWatchdog(activity: Activity) {
        probeJobs.remove(activity)?.let { main.removeCallbacks(it) }
    }

    private fun forceSafe30Restart(
        activity: CameraGateHighSpeedActivity,
        metadataFps: Double,
        analysisFps: Double
    ) {
        val cameraId = readField(activity, "cameraId")?.toString().orEmpty()
        val handler = readField(activity, "cameraHandler") as? Handler ?: return
        if (cameraId.isBlank()) return

        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ranges = runCatching {
            manager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.toList()
                .orEmpty()
        }.getOrDefault(emptyList())
        val safe30 = chooseSafe30Range(ranges) ?: return

        val reason = String.format(
            Locale.US,
            "60 FPS 강제 프로브 실패 · 실제 메타 %.1f / 분석 %.1f FPS → 30 FPS 안전모드",
            metadataFps,
            analysisFps
        )
        writeField(activity, "regularRange", safe30)
        writeField(activity, "fallbackReason", reason)
        setStateText(activity, "$reason\n카메라를 30 FPS로 다시 시작합니다…")

        handler.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            runCatching {
                CameraGateHighSpeedActivity::class.java.getDeclaredMethod("closeCamera").apply {
                    isAccessible = true
                }.invoke(activity)
            }
            writeField(activity, "regularRange", safe30)
            writeField(activity, "highSpeedFallbackStarted", true)
            writeField(activity, "fallbackReason", reason)
            handler.postDelayed({ reopenRegular(activity, handler, reason, attempt = 0) }, CAMERA_REOPEN_DELAY_MS)
        }
    }

    private fun reopenRegular(
        activity: CameraGateHighSpeedActivity,
        handler: Handler,
        reason: String,
        attempt: Int
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val cameraId = readField(activity, "cameraId")?.toString().orEmpty()
        if (cameraId.isBlank()) return
        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (activity.isFinishing || activity.isDestroyed) {
                        camera.close()
                        return
                    }
                    writeField(activity, "cameraDevice", camera)
                    writeField(activity, "highSpeedFallbackStarted", true)
                    writeField(activity, "fallbackReason", reason)
                    resetFrameClock(activity)
                    setStateText(activity, "$reason\n30 FPS 일반세션 구성 중…")
                    val started = runCatching {
                        CameraGateHighSpeedActivity::class.java.getDeclaredMethod(
                            "createRegularSession",
                            CameraDevice::class.java
                        ).apply { isAccessible = true }.invoke(activity, camera)
                        true
                    }.getOrDefault(false)
                    if (!started) {
                        runCatching { camera.close() }
                        writeField(activity, "cameraDevice", null)
                        setStateText(activity, "30 FPS 안전모드 시작 실패")
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    runCatching { camera.close() }
                    writeField(activity, "cameraDevice", null)
                    setStateText(activity, "30 FPS 안전모드 · 카메라 연결 끊김")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    runCatching { camera.close() }
                    writeField(activity, "cameraDevice", null)
                    if (attempt < MAX_CAMERA_REOPEN_RETRY) {
                        handler.postDelayed({ reopenRegular(activity, handler, reason, attempt + 1) }, CAMERA_RETRY_DELAY_MS)
                    } else {
                        setStateText(activity, "30 FPS 안전모드 재오픈 실패 · 카메라 오류 $error")
                    }
                }
            }, handler)
        } catch (e: Throwable) {
            if (attempt < MAX_CAMERA_REOPEN_RETRY) {
                handler.postDelayed({ reopenRegular(activity, handler, reason, attempt + 1) }, CAMERA_RETRY_DELAY_MS)
            } else {
                setStateText(activity, "30 FPS 안전모드 재오픈 실패 · ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun resetFrameClock(activity: CameraGateHighSpeedActivity) {
        runCatching {
            val frameClock = readField(activity, "frameClock") as? CameraGateFrameClock ?: return@runCatching
            val timestampRealtime = readBooleanField(activity, "timestampRealtime")
            frameClock.reset(timestampRealtime)
        }
    }

    private fun appendSupportNote(activity: CameraGateHighSpeedActivity, note: String) {
        runCatching {
            val current = readField(activity, "supportLabel")?.toString().orEmpty()
            if (!current.contains(note)) writeField(activity, "supportLabel", "$current · $note")
        }
    }

    private fun setStateText(activity: CameraGateHighSpeedActivity, value: String) {
        main.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            runCatching {
                val field = CameraGateHighSpeedActivity::class.java.getDeclaredField("stateText").apply {
                    isAccessible = true
                }
                (field.get(activity) as? TextView)?.text = value
            }
        }
    }

    private fun readField(activity: CameraGateHighSpeedActivity, name: String): Any? = runCatching {
        CameraGateHighSpeedActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
            .get(activity)
    }.getOrNull()

    private fun readBooleanField(activity: CameraGateHighSpeedActivity, name: String): Boolean = runCatching {
        CameraGateHighSpeedActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
            .getBoolean(activity)
    }.getOrDefault(false)

    private fun readDoubleField(activity: CameraGateHighSpeedActivity, name: String): Double = runCatching {
        CameraGateHighSpeedActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
            .getDouble(activity)
    }.getOrDefault(0.0)

    private fun writeField(activity: CameraGateHighSpeedActivity, name: String, value: Any?) {
        runCatching {
            val field = CameraGateHighSpeedActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
            when (field.type) {
                java.lang.Boolean.TYPE -> field.setBoolean(activity, value as? Boolean ?: false)
                java.lang.Double.TYPE -> field.setDouble(activity, value as? Double ?: 0.0)
                else -> field.set(activity, value)
            }
        }
    }

    private fun markV14(view: View) {
        if (view is TextView) {
            val text = view.text?.toString().orEmpty()
            if (text.contains("CAMERA GATE BETA v")) {
                view.text = text.replace(Regex("CAMERA GATE BETA v\\d+"), "CAMERA GATE BETA v14")
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) markV14(view.getChildAt(i))
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val DISCOVERY_RETRY_MS = 250L
        private const val MAX_DISCOVERY_RETRY = 8
        private const val PROBE_CHECK_MS = 500L
        private const val PROBE_VERIFY_WINDOW_MS = 3_500L
        private const val MIN_ACCEPTED_60_METADATA_FPS = 50.0
        private const val MIN_ACCEPTED_60_ANALYSIS_FPS = 50.0
        private const val CAMERA_REOPEN_DELAY_MS = 900L
        private const val CAMERA_RETRY_DELAY_MS = 1_200L
        private const val MAX_CAMERA_REOPEN_RETRY = 1
    }
}
