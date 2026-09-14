package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.Calendar
import java.util.Locale
import java.util.WeakHashMap

/**
 * Camera Gate RACE integration.
 *
 * v12 keeps automatic trigger relay, 15-second clock discipline and the high-speed health watchdog.
 * The safe fallback is now FPS-agnostic because some phones reopen at 30 FPS rather than 60 FPS.
 * A full camera restart also resets CameraGateFrameClock before the regular session starts so stale
 * 120 FPS PTS/calibration samples cannot leak into the 30/60 FPS timing uncertainty estimate.
 */
class CameraGateRaceUiProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val autoSyncJobs = WeakHashMap<Activity, Runnable>()
    private val cameraHealthJobs = WeakHashMap<Activity, Runnable>()
    private val triggerWatchers = WeakHashMap<Activity, TextWatcher>()
    private val lastReportedTrigger = WeakHashMap<Activity, Int>()

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(this)
        return true
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is RaceActivity) {
            activity.window.decorView.post { CameraGateRaceUiInstaller.install(activity) }
            return
        }
        if (activity is CameraGateHighSpeedActivity) {
            activity.window.decorView.post {
                markV12(activity.window.decorView)
                attachTriggerRelay(activity)
            }
            startClockDiscipline(activity)
            startCameraHealthWatchdog(activity)
        }
    }

    override fun onActivityPaused(activity: Activity) {
        stopClockDiscipline(activity)
        stopCameraHealthWatchdog(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        stopClockDiscipline(activity)
        stopCameraHealthWatchdog(activity)
        triggerWatchers.remove(activity)
        lastReportedTrigger.remove(activity)
        if (activity is RaceActivity) CameraGateRaceUiInstaller.uninstall(activity)
    }

    private fun startClockDiscipline(activity: CameraGateHighSpeedActivity) {
        stopClockDiscipline(activity)
        val job = object : Runnable {
            override fun run() {
                if (activity.isFinishing || activity.isDestroyed) {
                    autoSyncJobs.remove(activity)
                    return
                }
                invokeSyncClock(activity)
                main.postDelayed(this, AUTO_SYNC_INTERVAL_MS)
            }
        }
        autoSyncJobs[activity] = job
        main.postDelayed(job, AUTO_SYNC_INTERVAL_MS)
    }

    private fun stopClockDiscipline(activity: Activity) {
        autoSyncJobs.remove(activity)?.let { main.removeCallbacks(it) }
    }

    private fun invokeSyncClock(activity: CameraGateHighSpeedActivity) {
        runCatching {
            CameraGateHighSpeedActivity::class.java.getDeclaredMethod("syncClock").apply {
                isAccessible = true
            }.invoke(activity)
        }
    }

    private fun startCameraHealthWatchdog(activity: CameraGateHighSpeedActivity) {
        stopCameraHealthWatchdog(activity)
        val job = object : Runnable {
            var checks = 0
            override fun run() {
                if (activity.isFinishing || activity.isDestroyed) {
                    cameraHealthJobs.remove(activity)
                    return
                }
                checks += 1
                val highSpeed = readBooleanField(activity, "highSpeedActive")
                val fallbackStarted = readBooleanField(activity, "highSpeedFallbackStarted")
                val analysisFps = readDoubleField(activity, "analysisFps")
                val metadataFps = readDoubleField(activity, "metadataFps")

                if (highSpeed && !fallbackStarted && checks >= HEALTH_WARMUP_CHECKS) {
                    val stalled = analysisFps < MIN_HEALTHY_ANALYSIS_FPS ||
                        metadataFps < MIN_HEALTHY_METADATA_FPS
                    if (stalled && forceRegularFallback(activity, analysisFps, metadataFps)) {
                        cameraHealthJobs.remove(activity)
                        return
                    }
                }
                main.postDelayed(this, HEALTH_CHECK_INTERVAL_MS)
            }
        }
        cameraHealthJobs[activity] = job
        main.postDelayed(job, HEALTH_CHECK_INTERVAL_MS)
    }

    private fun stopCameraHealthWatchdog(activity: Activity) {
        cameraHealthJobs.remove(activity)?.let { main.removeCallbacks(it) }
    }

    /**
     * A stalled constrained-high-speed session must be torn down completely on affected devices.
     * Reusing that CameraDevice caused ERROR_CAMERA_DEVICE(4), so reopen a fresh device and start
     * only the regular session. The actual regular FPS can be 60 or 30 depending on the phone.
     */
    private fun forceRegularFallback(
        activity: CameraGateHighSpeedActivity,
        analysisFps: Double,
        metadataFps: Double
    ): Boolean = runCatching {
        val reason = String.format(
            Locale.US,
            "120 FPS 호환 실패 · 실제 메타 %.1f / 분석 %.1f FPS → 일반 FPS 안전모드",
            metadataFps,
            analysisFps
        )
        val handler = readObjectField(activity, "cameraHandler") as? Handler ?: return@runCatching false

        writeBooleanField(activity, "highSpeedFallbackStarted", true)
        writeStringField(activity, "fallbackReason", reason)
        setStateText(activity, "$reason\n카메라 장치를 완전히 재시작합니다…")

        handler.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            runCatching {
                CameraGateHighSpeedActivity::class.java.getDeclaredMethod("closeCamera").apply {
                    isAccessible = true
                }.invoke(activity)
            }
            writeBooleanField(activity, "highSpeedFallbackStarted", true)
            writeStringField(activity, "fallbackReason", reason)
            setStateText(activity, "$reason\n카메라 해제 완료 · 일반 FPS로 재오픈 대기 중…")
            handler.postDelayed({
                openRegularOnly(activity, handler, reason, attempt = 0)
            }, CAMERA_REOPEN_DELAY_MS)
        }
        true
    }.getOrDefault(false)

    private fun openRegularOnly(
        activity: CameraGateHighSpeedActivity,
        handler: Handler,
        reason: String,
        attempt: Int
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val cameraId = readStringField(activity, "cameraId")
        if (cameraId.isBlank()) {
            setStateText(activity, "일반 FPS 안전모드 재오픈 실패 · 카메라 ID 없음")
            return
        }

        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        setStateText(
            activity,
            if (attempt == 0) "$reason\n일반 카메라를 새로 여는 중…"
            else "$reason\n카메라 재오픈 1회 재시도 중…"
        )

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (activity.isFinishing || activity.isDestroyed) {
                        camera.close()
                        return
                    }

                    writeObjectField(activity, "cameraDevice", camera)
                    writeBooleanField(activity, "highSpeedFallbackStarted", true)
                    writeStringField(activity, "fallbackReason", reason)

                    // The new regular stream has a different frame period. Forget every PTS sample
                    // learned from the failed high-speed stream before measuring 30/60 FPS timing.
                    resetFrameClockForRegularSession(activity)

                    setStateText(activity, "$reason\n카메라 재오픈 성공 · 일반세션 구성 중…")
                    val started = runCatching {
                        CameraGateHighSpeedActivity::class.java.getDeclaredMethod(
                            "createRegularSession",
                            CameraDevice::class.java
                        ).apply { isAccessible = true }.invoke(activity, camera)
                        true
                    }.getOrDefault(false)

                    if (!started) {
                        runCatching { camera.close() }
                        writeObjectField(activity, "cameraDevice", null)
                        setStateText(activity, "일반 FPS 세션 시작 호출 실패")
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    runCatching { camera.close() }
                    writeObjectField(activity, "cameraDevice", null)
                    setStateText(activity, "일반 FPS 안전모드 · 카메라 연결 끊김")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    runCatching { camera.close() }
                    writeObjectField(activity, "cameraDevice", null)
                    if (attempt < MAX_CAMERA_REOPEN_RETRY) {
                        setStateText(activity, "일반 FPS 재오픈 오류 · $error · 잠시 후 한 번 더 시도합니다…")
                        handler.postDelayed({
                            openRegularOnly(activity, handler, reason, attempt + 1)
                        }, CAMERA_RETRY_DELAY_MS)
                    } else {
                        setStateText(activity, "일반 FPS 안전모드 재오픈 실패 · 카메라 오류 $error")
                    }
                }
            }, handler)
        } catch (e: Throwable) {
            if (attempt < MAX_CAMERA_REOPEN_RETRY) {
                setStateText(activity, "일반 FPS 재오픈 예외 · ${e.message ?: e.javaClass.simpleName} · 재시도 중…")
                handler.postDelayed({
                    openRegularOnly(activity, handler, reason, attempt + 1)
                }, CAMERA_RETRY_DELAY_MS)
            } else {
                setStateText(activity, "일반 FPS 안전모드 재오픈 실패 · ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun resetFrameClockForRegularSession(activity: CameraGateHighSpeedActivity) {
        runCatching {
            val frameClock = readObjectField(activity, "frameClock") as? CameraGateFrameClock ?: return@runCatching
            val timestampRealtime = readBooleanField(activity, "timestampRealtime")
            frameClock.reset(timestampRealtime)
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

    private fun readBooleanField(activity: CameraGateHighSpeedActivity, name: String): Boolean = runCatching {
        CameraGateHighSpeedActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
            .getBoolean(activity)
    }.getOrDefault(false)

    private fun readDoubleField(activity: CameraGateHighSpeedActivity, name: String): Double = runCatching {
        CameraGateHighSpeedActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
            .getDouble(activity)
    }.getOrDefault(0.0)

    private fun readStringField(activity: CameraGateHighSpeedActivity, name: String): String = runCatching {
        CameraGateHighSpeedActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
            .get(activity)?.toString().orEmpty()
    }.getOrDefault("")

    private fun readObjectField(activity: CameraGateHighSpeedActivity, name: String): Any? = runCatching {
        CameraGateHighSpeedActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
            .get(activity)
    }.getOrNull()

    private fun writeBooleanField(activity: CameraGateHighSpeedActivity, name: String, value: Boolean) {
        runCatching {
            CameraGateHighSpeedActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
                .setBoolean(activity, value)
        }
    }

    private fun writeStringField(activity: CameraGateHighSpeedActivity, name: String, value: String) {
        runCatching {
            CameraGateHighSpeedActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
                .set(activity, value)
        }
    }

    private fun writeObjectField(activity: CameraGateHighSpeedActivity, name: String, value: Any?) {
        runCatching {
            CameraGateHighSpeedActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
                .set(activity, value)
        }
    }

    private fun attachTriggerRelay(activity: CameraGateHighSpeedActivity) {
        if (triggerWatchers.containsKey(activity)) return
        val triggerView = findTextView(activity.window.decorView) {
            it.text?.toString()?.startsWith("TRIGGER") == true
        } ?: return

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                val snapshot = parseTrigger(activity, text) ?: return
                if (lastReportedTrigger[activity] == snapshot.triggerIndex) return
                lastReportedTrigger[activity] = snapshot.triggerIndex

                val base = runCatching { RaceServerClient(activity).baseUrl() }.getOrDefault("")
                CameraGateTriggerReporter.report(activity, base, snapshot) { result ->
                    activity.runOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        val current = triggerView.text?.toString().orEmpty()
                        if (!current.startsWith("TRIGGER #${snapshot.triggerIndex}")) return@runOnUiThread
                        val clean = current.lineSequence()
                            .filterNot { it.startsWith("서버 전송") || it.startsWith("서버 비교") }
                            .joinToString("\n")
                        val suffix = when {
                            result == null -> "서버 전송 실패 · 서버 버전/연결 확인"
                            result.paired -> String.format(
                                Locale.US,
                                "서버 비교 · %s · 차이 %+.1f ms · 절대 %.1f ms · 누적 %d쌍(10ms 이내 %d)",
                                result.peerLabel,
                                result.deltaMs,
                                result.absDeltaMs,
                                result.pairCount,
                                result.within10msCount
                            )
                            else -> "서버 전송 완료 · 다른 폰 트리거 대기 · 누적 ${result.pairCount}쌍"
                        }
                        triggerView.text = "$clean\n$suffix"
                    }
                }
            }
        }
        triggerView.addTextChangedListener(watcher)
        triggerWatchers[activity] = watcher
    }

    private fun parseTrigger(
        activity: CameraGateHighSpeedActivity,
        text: String
    ): CameraGateTriggerReporter.Snapshot? {
        val triggerIndex = Regex("TRIGGER #(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
        val localClock = Regex("폰\\s+(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})").find(text)?.groupValues?.getOrNull(1) ?: return null
        val correctedClock = Regex("기준보정\\s+(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})").find(text)?.groupValues?.getOrNull(1) ?: return null
        val offset = Regex("오프셋\\s+([+-]?\\d+(?:\\.\\d+)?) ms").find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
        val uncertainty = Regex("동기화추정\\s+±([0-9.]+) ms").find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: Double.NaN
        val quality = Regex("품질\\s+([^\\s·]+)").find(text)?.groupValues?.getOrNull(1).orEmpty()
        val now = System.currentTimeMillis()
        val localMs = clockToEpoch(localClock, now) ?: return null
        val correctedMs = clockToEpoch(correctedClock, localMs + offset.toLong()) ?: return null

        val fpsText = findTextView(activity.window.decorView) {
            it.text?.toString()?.contains("녹화스트림") == true &&
                it.text?.toString()?.contains("직접분석") == true
        }?.text?.toString().orEmpty()
        val fpsMatch = Regex(
            "메타데이터\\s+([0-9.]+) FPS\\s+·\\s+녹화스트림\\s+([0-9.]+) FPS\\s+·\\s+직접분석\\s+([0-9.]+) FPS"
        ).find(fpsText)
        val metadataFps = fpsMatch?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
        val streamFps = fpsMatch?.groupValues?.getOrNull(2)?.toDoubleOrNull() ?: 0.0
        val analysisFps = fpsMatch?.groupValues?.getOrNull(3)?.toDoubleOrNull() ?: 0.0
        val frameSource = text.lineSequence()
            .drop(4)
            .firstOrNull { it.isNotBlank() && !it.startsWith("서버 ") }
            .orEmpty()

        return CameraGateTriggerReporter.Snapshot(
            triggerIndex = triggerIndex,
            localMs = localMs,
            correctedMs = correctedMs,
            clockOffsetMs = offset,
            clockUncertaintyMs = uncertainty,
            clockQuality = quality,
            metadataFps = metadataFps,
            streamFps = streamFps,
            analysisFps = analysisFps,
            frameSource = frameSource
        )
    }

    private fun clockToEpoch(value: String, nearMs: Long): Long? {
        val m = Regex("(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})").matchEntire(value) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        val sec = m.groupValues[3].toIntOrNull() ?: return null
        val ms = m.groupValues[4].toIntOrNull() ?: return null
        val cal = Calendar.getInstance().apply {
            timeInMillis = nearMs
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, min)
            set(Calendar.SECOND, sec)
            set(Calendar.MILLISECOND, ms)
        }
        var candidate = cal.timeInMillis
        val halfDay = 12L * 60L * 60L * 1000L
        val day = 24L * 60L * 60L * 1000L
        if (candidate - nearMs > halfDay) candidate -= day
        if (nearMs - candidate > halfDay) candidate += day
        return candidate
    }

    private fun findTextView(view: View, predicate: (TextView) -> Boolean): TextView? {
        if (view is TextView && predicate(view)) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findTextView(view.getChildAt(i), predicate)?.let { return it }
            }
        }
        return null
    }

    private fun markV12(view: View) {
        if (view is TextView) {
            val text = view.text?.toString().orEmpty()
            if (text.contains("CAMERA GATE BETA v")) {
                view.text = text.replace(Regex("CAMERA GATE BETA v\\d+"), "CAMERA GATE BETA v12")
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) markV12(view.getChildAt(i))
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
        private const val AUTO_SYNC_INTERVAL_MS = 15_000L
        private const val HEALTH_CHECK_INTERVAL_MS = 1_000L
        private const val HEALTH_WARMUP_CHECKS = 4
        private const val MIN_HEALTHY_ANALYSIS_FPS = 30.0
        private const val MIN_HEALTHY_METADATA_FPS = 15.0
        private const val CAMERA_REOPEN_DELAY_MS = 900L
        private const val CAMERA_RETRY_DELAY_MS = 1_200L
        private const val MAX_CAMERA_REOPEN_RETRY = 1
    }
}
