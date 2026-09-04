package com.seungjae.jangsu280battery

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * RACE UI v0.34.4
 *
 * HOME intentionally follows RaceChrono's simple hierarchy: one dominant START button and a
 * compact registration panel underneath. LIVE uses three giant Best/Previous/Current rows and a
 * full-width delta panel. Official timing remains in RaceTimingService; this Activity only renders
 * durable/local state and starts or stops the service.
 */
class RaceActivity : Activity() {
    private lateinit var store: RaceDataStore
    private lateinit var client: RaceServerClient
    private lateinit var repo: CourseRepository

    private var mode = MODE_HOME
    private var currentEventCode = ""

    private var eventCodeInput: EditText? = null
    private var riderIdInput: EditText? = null
    private var nicknameInput: EditText? = null
    private var bibInput: EditText? = null
    private var registrationStatus: TextView? = null
    private var startButton: Button? = null

    private var liveHeader: TextView? = null
    private var bestTime: TextView? = null
    private var previousTime: TextView? = null
    private var currentTime: TextView? = null
    private var deltaPanel: LinearLayout? = null
    private var deltaTime: TextView? = null
    private var liveFooter: TextView? = null

    private val handler = Handler(Looper.getMainLooper())
    private val poller = object : Runnable {
        override fun run() {
            if (mode == MODE_LIVE) renderLive() else renderHomeState()
            handler.postDelayed(this, 100L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = RaceDataStore(this)
        client = RaceServerClient(this)
        repo = CourseRepository(this)

        val deepEvent = intent?.data?.getQueryParameter("event")?.trim()?.uppercase().orEmpty()
        currentEventCode = deepEvent.ifBlank { store.lastJoined()?.config?.eventCode.orEmpty() }
        showHome()
        Thread { runCatching { client.flushPending() } }.start()
        handler.post(poller)
    }

    override fun onDestroy() {
        handler.removeCallbacks(poller)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (mode == MODE_LIVE) showHome() else super.onBackPressed()
    }

    private fun showHome() {
        mode = MODE_HOME
        val profile = RaceProfileStore.profile(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        setContentView(root)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            setBackgroundColor(Color.rgb(46, 46, 46))
        }
        top.addView(TextView(this).apply {
            text = "Ride Copilot RACE"
            textSize = 25f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, dp(68), 1f))
        top.addView(TextView(this).apply {
            text = "⋮"
            gravity = Gravity.CENTER
            textSize = 32f
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(dp(52), dp(68)))
        root.addView(top)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.BLACK)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(28), dp(22), dp(30))
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        body.addView(TextView(this).apply {
            text = currentEventCode.takeIf { it.isNotBlank() }?.let { "대회 $it" } ?: "RACE READY"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
        })

        body.addView(View(this), LinearLayout.LayoutParams(1, dp(48)))

        startButton = Button(this).apply {
            text = if (store.snapshot().state in setOf("ARMED", "RUNNING")) "LIVE" else "START"
            textSize = 34f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            isAllCaps = false
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(220, 0, 0))
                setStroke(dp(10), Color.rgb(95, 95, 95))
            }
            setOnClickListener {
                val s = store.snapshot()
                if (s.state == "ARMED" || s.state == "RUNNING") showLive() else startRace()
            }
        }
        body.addView(startButton, LinearLayout.LayoutParams(dp(216), dp(216)))

        body.addView(View(this), LinearLayout.LayoutParams(1, dp(42)))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(Color.rgb(24, 24, 24))
        }
        panel.addView(TextView(this).apply {
            text = "선수 등록"
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        panel.addView(TextView(this).apply {
            text = "대회 코드 · 아이디 · 닉네임 · 배번만 입력하고 저장합니다."
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(3), 0, dp(8))
        })

        eventCodeInput = darkInput("대회 코드", currentEventCode).also { panel.addView(it, inputLp()) }
        riderIdInput = darkInput("아이디", profile.name).also { panel.addView(it, inputLp()) }
        nicknameInput = darkInput("닉네임", profile.nickname).also { panel.addView(it, inputLp()) }
        bibInput = darkInput("배번", profile.bib).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }.also { panel.addView(it, inputLp()) }

        panel.addView(Button(this).apply {
            text = "저장 · 대회 등록"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener { saveRegistration() }
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })

        registrationStatus = TextView(this).apply {
            textSize = 12f
            setPadding(0, dp(7), 0, 0)
            setTextColor(if (profile.isReady) Color.rgb(75, 215, 115) else Color.LTGRAY)
            text = if (profile.isReady) {
                val event = store.lastJoined()?.config?.eventCode.orEmpty()
                if (event.isNotBlank()) "✓ 저장됨 · 배번 ${profile.bib} · 최근 대회 $event" else "✓ 프로필 저장됨"
            } else "아직 저장된 선수 정보가 없습니다."
        }
        panel.addView(registrationStatus)
        panel.addView(TextView(this).apply {
            text = "대회 등록 시 해당 GPX가 앱에 없으면 자동 다운로드하여 기존 코스 저장 위치에 보관합니다."
            textSize = 10.5f
            setTextColor(Color.GRAY)
            setPadding(0, dp(5), 0, 0)
        })
        body.addView(panel, LinearLayout.LayoutParams(-1, -2))
    }

    private fun darkInput(hintText: String, value: String): EditText = EditText(this).apply {
        hint = hintText
        setHintTextColor(Color.GRAY)
        setTextColor(Color.WHITE)
        textSize = 16f
        setSingleLine(true)
        setText(value)
        setPadding(dp(12), 0, dp(12), 0)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(6).toFloat()
            setColor(Color.rgb(38, 38, 38))
            setStroke(dp(1), Color.rgb(90, 90, 90))
        }
    }

    private fun inputLp() = LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(7) }

    private fun saveRegistration() {
        val riderId = riderIdInput?.text?.toString().orEmpty().trim()
        val nickname = nicknameInput?.text?.toString().orEmpty().trim()
        val bib = bibInput?.text?.toString().orEmpty().trim()
        val code = eventCodeInput?.text?.toString().orEmpty().trim().uppercase()
        if (riderId.isBlank() || nickname.isBlank() || bib.isBlank()) {
            registrationStatus?.setTextColor(Color.rgb(255, 140, 70))
            registrationStatus?.text = "아이디, 닉네임, 배번을 모두 입력해 주세요."
            return
        }

        val saved = RaceProfileStore.save(this, riderId, nickname, bib)
        hideKeyboard()
        currentEventCode = code
        if (code.isBlank()) {
            registrationStatus?.setTextColor(Color.rgb(75, 215, 115))
            registrationStatus?.text = "✓ 로컬 저장 완료 · 배번 ${saved.bib} · 대회 코드를 넣으면 관리페이지에도 등록됩니다."
            return
        }

        registrationStatus?.setTextColor(Color.LTGRAY)
        registrationStatus?.text = "대회 등록 중 · 코스 확인 중…"
        Thread {
            val result = runCatching {
                val joined = client.join(code, saved)
                val old = store.joined(code)
                val oldLocal = old?.localCourseId?.takeIf { id -> repo.listCourses().any { it.id == id } }
                val matchingLocal = repo.listCourses().firstOrNull { m ->
                    m.name.trim().equals(joined.config.courseName.trim(), ignoreCase = true) &&
                        abs(m.totalKm * 1000.0 - joined.config.distanceM) <= 40.0
                }?.id
                val localId = oldLocal ?: matchingLocal ?: run {
                    val tmp = client.downloadCourse(code)
                    try {
                        repo.importGpxFile(tmp, joined.config.courseName, enqueueServer = false).id
                    } finally {
                        tmp.delete()
                    }
                }
                repo.setActive(localId)
                store.saveJoined(joined.config, joined.participantToken, localId)
                localId to joined.config
            }
            runOnUiThread {
                result.onSuccess { (_, cfg) ->
                    currentEventCode = cfg.eventCode
                    eventCodeInput?.setText(cfg.eventCode)
                    registrationStatus?.setTextColor(Color.rgb(75, 215, 115))
                    registrationStatus?.text = "✓ 관리페이지 등록 완료 · 배번 ${saved.bib} · ${cfg.courseName} 맵 준비 완료"
                }.onFailure { e ->
                    registrationStatus?.setTextColor(Color.rgb(255, 110, 80))
                    registrationStatus?.text = "등록 실패 · ${e.message ?: "서버 연결 확인"}"
                }
            }
        }.start()
    }

    private fun startRace() {
        val profile = RaceProfileStore.profile(this)
        if (!profile.isReady) {
            registrationStatus?.setTextColor(Color.rgb(255, 140, 70))
            registrationStatus?.text = "먼저 아이디, 닉네임, 배번을 저장해 주세요."
            return
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 8801)
            return
        }

        val code = eventCodeInput?.text?.toString().orEmpty().trim().uppercase()
        currentEventCode = code
        val joined = if (code.isNotBlank()) store.joined(code) else null
        if (code.isNotBlank() && joined == null) {
            registrationStatus?.setTextColor(Color.rgb(255, 140, 70))
            registrationStatus?.text = "먼저 ‘저장 · 대회 등록’을 눌러 대회와 맵을 준비해 주세요."
            return
        }

        val localCourseId = joined?.localCourseId ?: repo.activeMeta().id
        val baseConfig = joined?.config ?: RaceGateMath.practiceConfig(localCourseId, repo.loadCourse(localCourseId), 4)
        registrationStatus?.setTextColor(Color.LTGRAY)
        registrationStatus?.text = if (joined == null) "연습 RACE 준비 중…" else "선두 기록 불러오는 중…"

        Thread {
            val reference = runCatching { client.fetchReference(baseConfig.eventCode) }.getOrDefault(baseConfig.reference)
            val cfg = baseConfig.copy(reference = reference)
            runOnUiThread {
                store.saveActiveConfig(cfg, localCourseId, reference)
                startForegroundService(Intent(this, RaceTimingService::class.java).apply {
                    action = RaceTimingService.ACTION_ARM
                    putExtra(RaceTimingService.EXTRA_CONFIG, cfg.toJson().toString())
                    putExtra(RaceTimingService.EXTRA_COURSE_ID, localCourseId)
                })
                currentEventCode = cfg.eventCode
                showLive()
            }
        }.start()
    }

    private fun showLive() {
        mode = MODE_LIVE
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(0, 0, 230))
        }
        setContentView(root)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(12), 0)
            setBackgroundColor(Color.rgb(47, 47, 47))
        }
        top.addView(Button(this).apply {
            text = "‹  Live"
            textSize = 17f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { showHome() }
        }, LinearLayout.LayoutParams(dp(112), dp(56)))
        liveHeader = TextView(this).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }
        top.addView(liveHeader, LinearLayout.LayoutParams(0, dp(56), 1f))
        root.addView(top)

        bestTime = addLiveTimeBlock(root, "Best", "1")
        previousTime = addLiveTimeBlock(root, "Previous", "2")
        currentTime = addLiveTimeBlock(root, "Current", "3")

        deltaPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(Color.rgb(55, 55, 55))
        }
        deltaPanel?.addView(TextView(this).apply {
            text = "DELTA"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        deltaTime = TextView(this).apply {
            text = "—"
            textSize = 92f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            includeFontPadding = false
        }
        deltaPanel?.addView(deltaTime, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(deltaPanel, LinearLayout.LayoutParams(-1, 0, 1.15f))

        liveFooter = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setBackgroundColor(Color.rgb(28, 28, 28))
            setPadding(dp(8), dp(5), dp(8), dp(5))
        }
        root.addView(liveFooter, LinearLayout.LayoutParams(-1, dp(34)))
        renderLive()
    }

    private fun addLiveTimeBlock(root: LinearLayout, label: String, index: String): TextView {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(3))
            setBackgroundColor(Color.rgb(0, 0, 230))
        }
        block.addView(TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = index
            textSize = 43f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }, LinearLayout.LayoutParams(dp(58), -1))
        val time = TextView(this).apply {
            text = "—"
            textSize = 72f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        row.addView(time, LinearLayout.LayoutParams(0, -1, 1f))
        block.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(block, LinearLayout.LayoutParams(-1, 0, 1.55f))
        return time
    }

    private fun renderHomeState() {
        val s = store.snapshot()
        startButton?.text = if (s.state == "ARMED" || s.state == "RUNNING") "LIVE" else "START"
    }

    private fun renderLive() {
        if (mode != MODE_LIVE) return
        val s = store.snapshot()
        val eventCode = s.eventCode.ifBlank { currentEventCode }.ifBlank { "PRACTICE" }
        val currentRunId = s.runId
        val historical = store.completed()
            .filter { it.eventCode == eventCode && it.runId != currentRunId }
        val valid = historical.filter { it.status == "VALID" }
        val best = (valid.ifEmpty { historical }).minByOrNull { it.elapsedMs }
        val previous = historical.maxByOrNull { it.finishedAtMs }

        val elapsed = if (s.state == "RUNNING" && s.startedAtMs > 0L) {
            (System.currentTimeMillis() - s.startedAtMs).coerceAtLeast(0L)
        } else s.elapsedMs

        bestTime?.text = best?.elapsedMs?.let(::formatBigTime) ?: "—"
        previousTime?.text = previous?.elapsedMs?.let(::formatBigTime) ?: "—"
        currentTime?.text = formatBigTime(elapsed)

        val stateLabel = when (s.state) {
            "ARMED" -> "ARMED · START GATE"
            "RUNNING" -> "RUNNING"
            "FINISHED" -> "FINISH · ${s.validation}"
            else -> "READY"
        }
        liveHeader?.text = "$stateLabel  ·  $eventCode"

        val d = s.deltaMs
        if (d == null || s.state != "RUNNING") {
            deltaPanel?.setBackgroundColor(Color.rgb(55, 55, 55))
            deltaTime?.text = "—"
        } else {
            val faster = d <= 0L
            deltaPanel?.setBackgroundColor(if (faster) Color.rgb(0, 145, 20) else Color.rgb(210, 0, 0))
            deltaTime?.text = "%s%.1f".format(if (faster) "−" else "+", abs(d) / 1000.0)
        }

        liveFooter?.text = buildString {
            append("GPS ±").append(s.gpsAccuracyM.roundToInt()).append("m")
            if (s.totalM > 0) append("  ·  ").append(s.routeM.roundToInt()).append("/").append(s.totalM.roundToInt()).append("m")
            if (s.serverStatus.isNotBlank()) append("  ·  ").append(s.serverStatus)
        }
    }

    private fun formatBigTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val minute = safe / 60_000
        val seconds = (safe % 60_000) / 1000
        val tenth = (safe % 1000) / 100
        return if (minute > 0) "%d:%02d.%d".format(minute, seconds, tenth) else "%d.%d".format(seconds, tenth)
    }

    private fun hideKeyboard() {
        val token = currentFocus?.windowToken ?: return
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(token, 0)
        currentFocus?.clearFocus()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val MODE_HOME = 0
        private const val MODE_LIVE = 1
    }
}
