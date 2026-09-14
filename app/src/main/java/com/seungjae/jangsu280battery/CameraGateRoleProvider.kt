package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
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
 * Camera Gate v15 START/FINISH role selector and single-rider timing relay.
 *
 * Both gate phones run the same APK. Each phone is explicitly assigned START, FINISH, or COMPARE.
 * START and FINISH timestamps are already corrected to the shared clock domain by Camera Gate; the
 * server only subtracts those two camera timestamps. Network/request arrival time is never used as
 * the race time. This first field mode intentionally supports one active rider at a time.
 */
class CameraGateRoleProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
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

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(this)
        return true
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is CameraGateHighSpeedActivity) return
        activity.window.decorView.post {
            installRoleSelector(activity)
            attachTimingRelay(activity)
            markV15(activity.window.decorView)
        }
        main.postDelayed({
            if (!activity.isFinishing && !activity.isDestroyed) {
                installRoleSelector(activity)
                attachTimingRelay(activity)
                markV15(activity.window.decorView)
            }
        }, 900L)
    }

    override fun onActivityDestroyed(activity: Activity) {
        watchers.remove(activity)
        lastTimingTrigger.remove(activity)
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
            setPadding(dp(activity, 10), 0, dp(activity, 10), dp(activity, 4))
        }
        val status = TextView(activity).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        wrap.addView(status, LinearLayout.LayoutParams(-1, dp(activity, 30)))

        val buttons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val compare = Button(activity)
        val start = Button(activity)
        val finish = Button(activity)
        buttons.addView(compare, LinearLayout.LayoutParams(0, dp(activity, 46), 1f))
        buttons.addView(start, LinearLayout.LayoutParams(0, dp(activity, 46), 1f).apply {
            marginStart = dp(activity, 4)
        })
        buttons.addView(finish, LinearLayout.LayoutParams(0, dp(activity, 46), 1f).apply {
            marginStart = dp(activity, 4)
        })
        wrap.addView(buttons, LinearLayout.LayoutParams(-1, dp(activity, 46)))
        root.addView(wrap, controlsIndex + 1)

        fun refresh() {
            val role = gateRole(activity)
            status.text = when (role) {
                ROLE_START -> "게이트 역할 · START · 출발 통과시각 전송"
                ROLE_FINISH -> "게이트 역할 · FINISH · 도착 통과시각 전송"
                else -> "게이트 역할 · 비교 모드 · 기존 두 폰 오차 검증"
            }
            compare.text = if (role == ROLE_COMPARE) "✓ 비교" else "비교"
            start.text = if (role == ROLE_START) "✓ START" else "START"
            finish.text = if (role == ROLE_FINISH) "✓ FINISH" else "FINISH"
        }

        fun choose(role: String) {
            prefs(activity).edit().putString(KEY_ROLE, role).apply()
            refresh()
        }
        compare.setOnClickListener { choose(ROLE_COMPARE) }
        start.setOnClickListener { choose(ROLE_START) }
        finish.setOnClickListener { choose(ROLE_FINISH) }
        refresh()
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
                val role = gateRole(activity)
                if (role == ROLE_COMPARE) return

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
                    put("schema", 1)
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
                        "started" -> "게이트 START 접수 · FINISH 트리거 대기"
                        "finished" -> {
                            val elapsedMs = timing.optLong("elapsed_ms", -1L)
                            val officialTenthMs = timing.optLong("official_tenth_ms", -1L)
                            if (elapsedMs >= 0 && officialTenthMs >= 0) {
                                "공식 계측 · ${formatOfficial(officialTenthMs)} · 원시 ${String.format(Locale.US, "%.3f", elapsedMs / 1000.0)}초"
                            } else {
                                "게이트 FINISH 접수 · 기록 계산 응답 확인 필요"
                            }
                        }
                        "no_start" -> "게이트 FINISH 접수 · 매칭할 START가 없습니다"
                        "invalid_elapsed" -> "게이트 FINISH 접수 · START/FINISH 시각 순서 확인"
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

    private fun gateRole(context: Context): String {
        return prefs(context).getString(KEY_ROLE, ROLE_COMPARE)?.uppercase(Locale.US) ?: ROLE_COMPARE
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

    private fun markV15(view: View) {
        if (view is TextView) {
            val text = view.text?.toString().orEmpty()
            if (text.contains("CAMERA GATE BETA v")) {
                view.text = text.replace(Regex("CAMERA GATE BETA v\\d+"), "CAMERA GATE BETA v15")
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) markV15(view.getChildAt(i))
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun query(
        uri: android.net.Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: android.net.Uri): String? = null
    override fun insert(uri: android.net.Uri, values: ContentValues?): android.net.Uri? = null
    override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: android.net.Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val PREFS = "camera_gate_test"
        private const val KEY_ROLE = "gate_role"
        private const val ROLE_COMPARE = "COMPARE"
        private const val ROLE_START = "START"
        private const val ROLE_FINISH = "FINISH"
        private const val SESSION_KEY = "camera-gate-single-rider"
    }
}
