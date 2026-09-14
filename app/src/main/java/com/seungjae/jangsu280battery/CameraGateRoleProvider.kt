package com.seungjae.jangsu280battery

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Camera Gate v16 START/CP1..CP5/FINISH role selector and timing relay.
 *
 * The dropdown supports manual roles plus AUTO. AUTO registers every live gate phone with the
 * server every two seconds. The server assigns roles from the live phone count:
 * 1 phone = START, 2 = START/FINISH, 3 = START/CP1/FINISH ... up to
 * 7 = START/CP1/CP2/CP3/CP4/CP5/FINISH. Missing CP phones are never required.
 */
object CameraGateRoleInstaller {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val watchers = WeakHashMap<Activity, TextWatcher>()
    private val lastTimingTrigger = WeakHashMap<Activity, Int>()
    private val autoJobs = WeakHashMap<Activity, Runnable>()
    private val autoAssignedRole = WeakHashMap<Activity, String>()
    private val autoPhoneCount = WeakHashMap<Activity, Int>()

    fun onResume(activity: CameraGateHighSpeedActivity) {
        activity.window.decorView.post {
            installRoleSelector(activity)
            attachTimingRelay(activity)
            markV16(activity.window.decorView)
            startAutoRolePoll(activity)
        }
        main.postDelayed({
            if (!activity.isFinishing && !activity.isDestroyed) {
                installRoleSelector(activity)
                attachTimingRelay(activity)
                markV16(activity.window.decorView)
                startAutoRolePoll(activity)
            }
        }, 1_500L)
    }

    fun onPaused(activity: Activity) {
        stopAutoRolePoll(activity)
    }

    fun onDestroyed(activity: Activity) {
        stopAutoRolePoll(activity)
        watchers.remove(activity)
        lastTimingTrigger.remove(activity)
        autoAssignedRole.remove(activity)
        autoPhoneCount.remove(activity)
    }

    private fun installRoleSelector(activity: CameraGateHighSpeedActivity) {
        val decor = activity.window.decorView
        if (findTextView(decor) { it.text?.toString()?.startsWith("게이트 역할 ·") == true } != null) return

        val arm = findButton(decor) {
            val t = it.text?.toString().orEmpty()
            t.contains("계측 대기 ARM") || t.contains("계측 중지")
        } ?: return
        val controls = arm.parent as? LinearLayout ?: return
        val root = controls.parent as? LinearLayout ?: return
        val controlsIndex = root.indexOfChild(controls)
        if (controlsIndex < 0) return

        val wrap = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 10), 0, dp(activity, 10), dp(activity, 6))
        }
        val status = TextView(activity).apply {
            text = "게이트 역할 · 준비 중"
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        wrap.addView(status, LinearLayout.LayoutParams(-1, dp(activity, 34)))

        val spinner = Spinner(activity)
        val labels = ROLE_OPTIONS.map { it.first }
        val adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.adapter = adapter
        val current = selectedRole(activity)
        spinner.setSelection(ROLE_OPTIONS.indexOfFirst { it.second == current }.coerceAtLeast(0), false)
        wrap.addView(spinner, LinearLayout.LayoutParams(-1, dp(activity, 50)))
        root.addView(wrap, controlsIndex + 1)

        fun refresh() = refreshRoleStatus(activity)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val role = ROLE_OPTIONS.getOrNull(position)?.second ?: ROLE_AUTO
                prefs(activity).edit().putString(KEY_ROLE_V16, role).apply()
                if (role == ROLE_AUTO) {
                    startAutoRolePoll(activity)
                } else {
                    stopAutoRolePoll(activity)
                    autoAssignedRole.remove(activity)
                    autoPhoneCount.remove(activity)
                }
                refresh()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        refresh()
    }

    private fun refreshRoleStatus(activity: CameraGateHighSpeedActivity) {
        val status = findTextView(activity.window.decorView) {
            it.text?.toString()?.startsWith("게이트 역할 ·") == true
        } ?: return
        val role = selectedRole(activity)
        status.text = when (role) {
            ROLE_AUTO -> {
                val assigned = autoAssignedRole[activity] ?: "배정 대기"
                val count = autoPhoneCount[activity]
                val countText = count?.let { " · 연결 ${it}대" }.orEmpty()
                "게이트 역할 · 자동 → $assigned$countText"
            }
            ROLE_COMPARE -> "게이트 역할 · 비교 모드 · 기존 두 폰 오차 검증"
            ROLE_START -> "게이트 역할 · START · 출발 통과시각 전송"
            ROLE_FINISH -> "게이트 역할 · FINISH · 도착 통과시각 전송"
            else -> "게이트 역할 · $role · 중간 통과 누적시간 전송"
        }
    }

    private fun startAutoRolePoll(activity: CameraGateHighSpeedActivity) {
        if (selectedRole(activity) != ROLE_AUTO) return
        stopAutoRolePoll(activity)
        val job = object : Runnable {
            override fun run() {
                if (activity.isFinishing || activity.isDestroyed || selectedRole(activity) != ROLE_AUTO) {
                    autoJobs.remove(activity)
                    return
                }
                registerAutoRole(activity)
                main.postDelayed(this, AUTO_ROLE_POLL_MS)
            }
        }
        autoJobs[activity] = job
        main.post(job)
    }

    private fun stopAutoRolePoll(activity: Activity) {
        autoJobs.remove(activity)?.let { main.removeCallbacks(it) }
    }

    private fun registerAutoRole(activity: CameraGateHighSpeedActivity) {
        val base = runCatching { RaceServerClient(activity).baseUrl() }.getOrDefault("")
            .trim().trimEnd('/')
        if (!base.startsWith("http://") && !base.startsWith("https://")) return
        val app = activity.applicationContext
        executor.execute {
            val result = runCatching {
                val deviceId = installId(app)
                val payload = JSONObject().apply {
                    put("device_id", deviceId)
                    put("device_label", "${Build.MANUFACTURER} ${Build.MODEL} · ${deviceId.takeLast(4)}")
                    put("session_key", SESSION_KEY)
                }
                val request = Request.Builder()
                    .url("$base/api/race/camera-gate/role")
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .header("Cache-Control", "no-cache")
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val json = JSONObject(response.body?.string().orEmpty())
                    val assigned = json.optString("assigned_role", "").uppercase(Locale.US)
                    val count = json.optInt("phone_count", 0)
                    if (assigned.isBlank()) null else assigned to count
                }
            }.getOrNull()
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed || selectedRole(activity) != ROLE_AUTO) return@runOnUiThread
                if (result != null) {
                    autoAssignedRole[activity] = result.first
                    autoPhoneCount[activity] = result.second
                }
                refreshRoleStatus(activity)
            }
        }
    }

    private fun attachTimingRelay(activity: CameraGateHighSpeedActivity) {
        if (watchers.containsKey(activity)) return
        val triggerView = findTextView(activity.window.decorView) {
            it.text?.toString()?.startsWith("TRIGGER") == true
        } ?: return

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                val role = effectiveRole(activity)
                if (role == ROLE_COMPARE || role == ROLE_AUTO || role.isBlank()) return

                val triggerIndex = Regex("TRIGGER #(\\d+)")
                    .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return
                if (lastTimingTrigger[activity] == triggerIndex) return

                val correctedClock = Regex("기준보정\\s+(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})")
                    .find(text)?.groupValues?.getOrNull(1) ?: return
                val correctedMs = clockToEpoch(correctedClock, System.currentTimeMillis()) ?: return
                val uncertainty = Regex("동기화추정\\s+±([0-9.]+) ms")
                    .find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: Double.NaN
                val streamFps = currentStreamFps(activity)

                lastTimingTrigger[activity] = triggerIndex
                sendTiming(
                    activity = activity,
                    role = role,
                    triggerIndex = triggerIndex,
                    correctedMs = correctedMs,
                    uncertaintyMs = uncertainty,
                    streamFps = streamFps
                ) { line ->
                    activity.runOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        val current = triggerView.text?.toString().orEmpty()
                        if (!current.startsWith("TRIGGER #$triggerIndex")) return@runOnUiThread
                        val clean = current.lineSequence()
                            .filterNot {
                                it.startsWith("게이트 START") ||
                                    it.startsWith("게이트 FINISH") ||
                                    it.startsWith("게이트 CP") ||
                                    it.startsWith("공식 계측")
                            }
                            .joinToString("\n")
                        triggerView.text = "$clean\n$line"
                    }
                }
            }
        }
        triggerView.addTextChangedListener(watcher)
        watchers[activity] = watcher
    }

    private fun sendTiming(
        activity: CameraGateHighSpeedActivity,
        role: String,
        triggerIndex: Int,
        correctedMs: Long,
        uncertaintyMs: Double,
        streamFps: Double,
        callback: (String) -> Unit
    ) {
        val base = runCatching { RaceServerClient(activity).baseUrl() }.getOrDefault("")
            .trim().trimEnd('/')
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            callback("게이트 $role 전송 실패 · RACE 서버 주소 확인")
            return
        }

        val app = activity.applicationContext
        executor.execute {
            val line = runCatching {
                val deviceId = installId(app)
                val payload = JSONObject().apply {
                    put("schema", 2)
                    put("role", role)
                    put("session_key", SESSION_KEY)
                    put("device_id", deviceId)
                    put("device_label", "${Build.MANUFACTURER} ${Build.MODEL} · ${deviceId.takeLast(4)}")
                    put("app_version", BuildConfig.VERSION_NAME)
                    put("trigger_index", triggerIndex)
                    put("corrected_ms", correctedMs)
                    if (uncertaintyMs.isFinite()) put("clock_uncertainty_ms", uncertaintyMs)
                    put("stream_fps", streamFps)
                }
                val request = Request.Builder()
                    .url("$base/api/race/camera-gate/timing")
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .header("Cache-Control", "no-cache")
                    .build()

                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use "게이트 $role 전송 실패 · HTTP ${response.code} · 서버 업데이트 확인"
                    }
                    val json = JSONObject(response.body?.string().orEmpty())
                    val timing = json.optJSONObject("timing")
                        ?: return@use "게이트 $role 전송 완료 · 응답 확인 필요"
                    when (timing.optString("state")) {
                        "started" -> "게이트 START 접수 · CP/FINISH 트리거 대기"
                        "split" -> {
                            val elapsedMs = timing.optLong("elapsed_ms", -1L)
                            val official = timing.optLong("official_tenth_ms", -1L)
                            if (elapsedMs >= 0 && official >= 0) {
                                "게이트 $role 접수 · 누적 ${formatOfficial(official)} · 원시 ${String.format(Locale.US, "%.3f", elapsedMs / 1000.0)}초"
                            } else "게이트 $role 접수"
                        }
                        "finished" -> {
                            val elapsedMs = timing.optLong("elapsed_ms", -1L)
                            val officialTenthMs = timing.optLong("official_tenth_ms", -1L)
                            if (elapsedMs >= 0 && officialTenthMs >= 0) {
                                "공식 계측 · ${formatOfficial(officialTenthMs)} · 원시 ${String.format(Locale.US, "%.3f", elapsedMs / 1000.0)}초"
                            } else {
                                "게이트 FINISH 접수 · 기록 계산 응답 확인 필요"
                            }
                        }
                        "no_start" -> "게이트 $role 접수 · 매칭할 START가 없습니다"
                        "invalid_elapsed" -> "게이트 $role 접수 · START 이후 시각인지 확인"
                        else -> "게이트 $role 전송 완료"
                    }
                }
            }.getOrElse { e ->
                "게이트 $role 전송 실패 · ${e.message ?: e.javaClass.simpleName}"
            }
            callback(line)
        }
    }

    private fun currentStreamFps(activity: Activity): Double {
        val fpsText = findTextView(activity.window.decorView) {
            it.text?.toString()?.contains("녹화스트림") == true
        }?.text?.toString().orEmpty()
        return Regex("녹화스트림\\s+([0-9.]+) FPS")
            .find(fpsText)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
    }

    private fun selectedRole(context: Context): String =
        prefs(context).getString(KEY_ROLE_V16, ROLE_AUTO)?.uppercase(Locale.US) ?: ROLE_AUTO

    private fun effectiveRole(activity: CameraGateHighSpeedActivity): String {
        val selected = selectedRole(activity)
        return if (selected == ROLE_AUTO) autoAssignedRole[activity] ?: ROLE_AUTO else selected
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun installId(context: Context): String {
        val p = prefs(context)
        val existing = p.getString("install_id", null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString().replace("-", "").take(12)
        p.edit().putString("install_id", created).apply()
        return created
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

    private fun formatOfficial(ms: Long): String {
        val totalTenths = (ms / 100L).coerceAtLeast(0L)
        val tenths = totalTenths % 10L
        val totalSeconds = totalTenths / 10L
        val seconds = totalSeconds % 60L
        val totalMinutes = totalSeconds / 60L
        val minutes = totalMinutes % 60L
        val hours = totalMinutes / 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d.%d", hours, minutes, seconds, tenths)
        } else {
            String.format(Locale.US, "%02d:%02d.%d", minutes, seconds, tenths)
        }
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

    private fun findButton(view: View, predicate: (Button) -> Boolean): Button? {
        if (view is Button && predicate(view)) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findButton(view.getChildAt(i), predicate)?.let { return it }
            }
        }
        return null
    }

    private fun markV16(view: View) {
        if (view is TextView) {
            val text = view.text?.toString().orEmpty()
            if (text.contains("CAMERA GATE BETA v")) {
                view.text = text.replace(Regex("CAMERA GATE BETA v\\d+"), "CAMERA GATE BETA v16")
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) markV16(view.getChildAt(i))
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private const val PREFS = "camera_gate_test"
    private const val KEY_ROLE_V16 = "gate_role_v16"
    private const val ROLE_AUTO = "AUTO"
    private const val ROLE_COMPARE = "COMPARE"
    private const val ROLE_START = "START"
    private const val ROLE_FINISH = "FINISH"
    private const val SESSION_KEY = "camera-gate-single-rider"
    private const val AUTO_ROLE_POLL_MS = 2_000L

    private val ROLE_OPTIONS = listOf(
        "자동 (폰 수에 맞춤)" to ROLE_AUTO,
        "비교 모드" to ROLE_COMPARE,
        "START" to ROLE_START,
        "CP1" to "CP1",
        "CP2" to "CP2",
        "CP3" to "CP3",
        "CP4" to "CP4",
        "CP5" to "CP5",
        "FINISH" to ROLE_FINISH,
    )
}
