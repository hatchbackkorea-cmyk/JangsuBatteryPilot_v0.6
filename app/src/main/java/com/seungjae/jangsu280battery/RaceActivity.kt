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
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.roundToInt

/** RACE UI v0.34.9: field QR join, waiting/practice/official phases, local-first timing. */
class RaceActivity : Activity() {
    private lateinit var store: RaceDataStore
    private lateinit var client: RaceServerClient
    private lateinit var repo: CourseRepository

    private var mode = MODE_HOME
    private var currentEventCode = ""
    private var startButton: Button? = null
    private var homeEventStatus: TextView? = null
    private var registrationStatus: TextView? = null
    private var joinStatus: TextView? = null
    private var eventsContainer: LinearLayout? = null
    private var riderIdInput: EditText? = null
    private var nicknameInput: EditText? = null
    private var bibInput: EditText? = null
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
            when (mode) { MODE_LIVE -> renderLive(); MODE_HOME -> renderHomeState() }
            handler.postDelayed(this, 100L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = RaceDataStore(this); client = RaceServerClient(this); repo = CourseRepository(this)
        val deepServer = intent?.data?.getQueryParameter("server").orEmpty().trim()
        if (deepServer.isNotBlank()) client.setEventServer(deepServer)
        val deepEvent = intent?.data?.getQueryParameter("event")?.trim()?.uppercase().orEmpty()
        currentEventCode = deepEvent.ifBlank { store.lastJoined()?.config?.eventCode.orEmpty() }
        if (deepEvent.isNotBlank()) showEvents() else showHome()
        Thread { runCatching { client.flushPending() } }.start()
        handler.post(poller)
    }

    override fun onDestroy() { handler.removeCallbacks(poller); super.onDestroy() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (mode == MODE_HOME) super.onBackPressed() else showHome() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startRace()
    }

    private fun showHome() {
        mode = MODE_HOME
        val profile = RaceProfileStore.profile(this)
        val joined = currentJoined()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        setContentView(root); addTopBar(root, "Ride Copilot RACE", false)
        val scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(Color.BLACK) }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(22), dp(22), dp(22), dp(26)) }
        scroll.addView(body); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        homeEventStatus = TextView(this).apply {
            gravity = Gravity.CENTER; textSize = 22.5f; setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(10), dp(12), dp(10)); setTextColor(if (joined != null) GOOD else Color.LTGRAY)
            text = if (joined != null) {
                "✓ 참가완료 · ${joined.config.name} · ${joined.config.eventCode}\n배번 ${profile.bib} · 아이디 ${profile.name} · 닉네임 ${profile.nickname}"
            } else if (profile.isReady) {
                "선수등록 완료 · 배번 ${profile.bib}\n대회 참가 메뉴에서 참가할 대회를 선택하세요."
            } else "먼저 선수 등록을 완료하세요."
        }
        body.addView(homeEventStatus, LinearLayout.LayoutParams(-1, -2))
        body.addView(View(this), LinearLayout.LayoutParams(1, dp(24)))

        startButton = Button(this).apply {
            text = "START"; textSize = 51f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER; isAllCaps = false
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(220, 0, 0)); setStroke(dp(10), Color.rgb(95, 95, 95)) }
            setOnClickListener { val s = store.snapshot(); if (s.state == "ARMED" || s.state == "RUNNING") showLive() else startRace() }
        }
        body.addView(startButton, LinearLayout.LayoutParams(dp(216), dp(216)))
        body.addView(View(this), LinearLayout.LayoutParams(1, dp(24)))

        val menuRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        menuRow.addView(menuButton(if (profile.isReady) "✓ 선수 등록" else "선수 등록") { showRegistration() }, LinearLayout.LayoutParams(0, dp(64), 1f).apply { marginEnd = dp(6) })
        menuRow.addView(menuButton(if (joined != null) "✓ 대회 참가" else "대회 참가") { showEvents() }, LinearLayout.LayoutParams(0, dp(64), 1f).apply { marginStart = dp(6) })
        body.addView(menuRow, LinearLayout.LayoutParams(-1, -2))
        body.addView(TextView(this).apply {
            text = "화면을 끄거나 다른 앱을 열어도 계측은 계속됩니다. 앱을 최근 앱 목록에서 밀어 종료하면 계측도 종료됩니다."
            textSize = 11f; gravity = Gravity.CENTER; setTextColor(Color.GRAY); setPadding(0, dp(12), 0, 0)
        })
    }

    private fun showRegistration() {
        mode = MODE_REGISTER
        val profile = RaceProfileStore.profile(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        setContentView(root); addTopBar(root, "선수 등록", true)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(24), dp(22), dp(24)) }
        root.addView(body, LinearLayout.LayoutParams(-1, -1))
        body.addView(TextView(this).apply { text = "아이디 · 닉네임 · 배번"; textSize = 22f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
        body.addView(TextView(this).apply { text = "여기서는 선수 정보만 저장합니다. 저장 후 ‘대회 참가’에서 참가할 대회를 선택합니다."; textSize = 12f; setTextColor(Color.LTGRAY); setPadding(0, dp(4), 0, dp(12)) })
        riderIdInput = darkInput("아이디", profile.name).also { body.addView(it, inputLp()) }
        nicknameInput = darkInput("닉네임", profile.nickname).also { body.addView(it, inputLp()) }
        bibInput = darkInput("배번", profile.bib).apply { inputType = InputType.TYPE_CLASS_NUMBER }.also { body.addView(it, inputLp()) }
        body.addView(Button(this).apply { text = "선수 정보 저장"; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setOnClickListener { saveProfileOnly() } }, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(12) })
        registrationStatus = TextView(this).apply { textSize = 14f; setPadding(0, dp(12), 0, 0); setTextColor(if (profile.isReady) GOOD else Color.LTGRAY); text = if (profile.isReady) "✓ 저장됨 · 배번 ${profile.bib} · ${profile.nickname} (${profile.name})" else "아직 저장된 선수 정보가 없습니다." }
        body.addView(registrationStatus)
    }

    private fun saveProfileOnly() {
        val riderId = riderIdInput?.text?.toString().orEmpty().trim(); val nickname = nicknameInput?.text?.toString().orEmpty().trim(); val bib = bibInput?.text?.toString().orEmpty().trim()
        if (riderId.isBlank() || nickname.isBlank() || bib.isBlank()) { registrationStatus?.setTextColor(WARN); registrationStatus?.text = "아이디, 닉네임, 배번을 모두 입력해 주세요."; return }
        val saved = RaceProfileStore.save(this, riderId, nickname, bib); hideKeyboard(); registrationStatus?.setTextColor(GOOD); registrationStatus?.text = "✓ 선수등록 저장 완료 · 배번 ${saved.bib} · ${saved.nickname} (${saved.name})"; Toast.makeText(this, "선수등록이 저장되었습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun showEvents() {
        mode = MODE_EVENTS
        val profile = RaceProfileStore.profile(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        setContentView(root); addTopBar(root, "대회 참가", true)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.BLACK) }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(28)) }
        scroll.addView(body); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(TextView(this).apply { text = if (profile.isReady) "배번 ${profile.bib} · 아이디 ${profile.name} · 닉네임 ${profile.nickname}" else "선수등록이 필요합니다."; textSize = 16f; setTextColor(if (profile.isReady) GOOD else WARN); setTypeface(typeface, Typeface.BOLD) })
        body.addView(TextView(this).apply { text = "QR로 들어온 경우 현장 서버와 대회코드가 자동 설정됩니다. 대기중인 방은 운영자가 오픈할 때까지 참가할 수 없습니다."; textSize = 12f; setTextColor(Color.LTGRAY); setPadding(0, dp(5), 0, dp(10)) })
        body.addView(Button(this).apply { text = "새로고침"; setOnClickListener { loadEventsAsync() } }, LinearLayout.LayoutParams(-1, dp(48)))
        joinStatus = TextView(this).apply { text = "개설된 대회를 불러오는 중…"; textSize = 13f; setTextColor(Color.LTGRAY); setPadding(0, dp(10), 0, dp(8)) }
        body.addView(joinStatus); eventsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; body.addView(eventsContainer, LinearLayout.LayoutParams(-1, -2)); loadEventsAsync()
    }

    private fun loadEventsAsync() {
        if (mode != MODE_EVENTS) return
        joinStatus?.setTextColor(Color.LTGRAY); joinStatus?.text = "개설된 대회를 불러오는 중…"; eventsContainer?.removeAllViews()
        Thread {
            val result = runCatching { client.listEvents() }
            runOnUiThread {
                if (mode != MODE_EVENTS) return@runOnUiThread
                result.onSuccess { events ->
                    if (events.isEmpty()) { joinStatus?.text = "현재 표시할 대회가 없습니다."; return@onSuccess }
                    val selected = currentEventCode.takeIf { it.isNotBlank() }?.let { code -> events.firstOrNull { it.config.eventCode == code } }
                    joinStatus?.text = selected?.notice?.takeIf { it.isNotBlank() } ?: "개설된 대회 ${events.size}개"
                    joinStatus?.setTextColor(if (selected?.phase == "WAITING") WARN else Color.LTGRAY)
                    events.forEach { addEventCard(it) }
                }.onFailure { e -> joinStatus?.setTextColor(WARN); joinStatus?.text = "대회 목록을 불러오지 못했습니다.\n${e.message ?: "서버 연결을 확인하세요."}\n서버: ${client.baseUrl()}" }
            }
        }.start()
    }

    private fun phaseLabel(phase: String): String = when (phase.uppercase()) { "WAITING" -> "대기중"; "PRACTICE" -> "연습주행"; "OFFICIAL" -> "정식계측"; "ENDED" -> "종료"; else -> phase }

    private fun addEventCard(item: RaceServerClient.EventListItem) {
        val code = item.config.eventCode; val joined = store.joined(code); val selected = currentEventCode == code && joined != null
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(10).toFloat(); setColor(Color.rgb(24, 24, 24)); setStroke(dp(if (selected) 2 else 1), if (selected) GOOD else Color.rgb(70, 70, 70)) } }
        card.addView(TextView(this).apply { text = "${item.config.name}  ·  ${item.config.eventCode}"; textSize = 18f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
        card.addView(TextView(this).apply { text = "${item.config.courseName} · ${"%.2f".format(item.config.distanceM / 1000.0)} km · 참가 ${item.participants}명 · ${phaseLabel(item.phase)}"; textSize = 12f; setTextColor(if (item.phase == "WAITING") WARN else Color.LTGRAY); setPadding(0, dp(4), 0, dp(8)) })
        card.addView(Button(this).apply {
            text = when {
                item.phase == "WAITING" -> "현재는 오픈되지 않았습니다"
                item.phase == "ENDED" -> "대회 종료"
                joined != null && selected -> "✓ 참가완료 · 현재 선택됨"
                joined != null -> "✓ 참가완료 · 이 대회 선택"
                item.phase == "PRACTICE" -> "연습주행 참가하기"
                else -> "정식 대회 참가하기"
            }
            textSize = 15f; isEnabled = item.joinable
            setOnClickListener {
                if (joined != null) { currentEventCode = code; runCatching { repo.setActive(joined.localCourseId) }; joinStatus?.setTextColor(GOOD); joinStatus?.text = "✓ ${item.config.name} 참가완료 · ${phaseLabel(item.phase)}"; loadEventsAsync() }
                else joinEvent(item)
            }
        }, LinearLayout.LayoutParams(-1, dp(50)))
        if (item.phase == "WAITING") card.addView(TextView(this).apply { text = item.notice.ifBlank { "현재는 대회 서버가 오픈되지 않았습니다. 운영자 오픈 후 이용해 주세요." }; textSize = 12f; setTextColor(WARN); setPadding(0, dp(6), 0, 0) })
        eventsContainer?.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }

    private fun joinEvent(item: RaceServerClient.EventListItem) {
        val profile = RaceProfileStore.profile(this)
        if (!profile.isReady) { AlertDialog.Builder(this).setTitle("선수등록 필요").setMessage("먼저 아이디, 닉네임, 배번을 선수 등록 메뉴에서 저장해 주세요.").setPositiveButton("선수 등록") { _, _ -> showRegistration() }.setNegativeButton("취소", null).show(); return }
        if (!item.joinable) { AlertDialog.Builder(this).setTitle("대회 오픈 대기").setMessage(item.notice.ifBlank { "현재는 대회 서버가 오픈되지 않았습니다. 운영자 오픈 후 이용해 주세요." }).setPositiveButton("확인", null).show(); return }
        joinStatus?.setTextColor(Color.LTGRAY); joinStatus?.text = "${item.config.name} 참가 처리 중…\n서버 참가 등록 → GPX 확인"
        Thread {
            val result = runCatching {
                val joined = client.join(item.config.eventCode, profile); val old = store.joined(item.config.eventCode); val localCourses = repo.listCourses()
                val oldLocal = old?.localCourseId?.takeIf { id -> localCourses.any { it.id == id } }
                val matchingLocal = localCourses.firstOrNull { m -> m.name.trim().equals(joined.config.courseName.trim(), true) && abs(m.totalKm * 1000.0 - joined.config.distanceM) <= 40.0 }?.id
                val localId = oldLocal ?: matchingLocal ?: run { val tmp = client.downloadCourse(item.config.eventCode); try { repo.importGpxFile(tmp, joined.config.courseName, enqueueServer = false).id } finally { tmp.delete() } }
                repo.setActive(localId); store.saveJoined(joined.config, joined.participantToken, localId); Triple(joined.config, localId, joined.phase)
            }
            runOnUiThread {
                if (mode != MODE_EVENTS) return@runOnUiThread
                result.onSuccess { (cfg, _, phase) -> currentEventCode = cfg.eventCode; joinStatus?.setTextColor(GOOD); joinStatus?.text = "✓ 참가완료 · ${cfg.name} · ${cfg.eventCode}\n배번 ${profile.bib} · ${profile.nickname} · ${phaseLabel(phase)}"; AlertDialog.Builder(this).setTitle("대회 참가 완료").setMessage("${cfg.name}\n대회 코드 ${cfg.eventCode}\n배번 ${profile.bib}\n\n${phaseLabel(phase)} 세션에 참가했습니다.").setPositiveButton("확인", null).show(); loadEventsAsync() }
                    .onFailure { e -> joinStatus?.setTextColor(WARN); joinStatus?.text = e.message ?: "참가 실패"; AlertDialog.Builder(this).setTitle("대회 참가 안내").setMessage(e.message ?: "서버 연결을 확인하세요.").setPositiveButton("확인", null).show() }
            }
        }.start()
    }

    private fun currentJoined(): RaceDataStore.Joined? {
        val code = currentEventCode.ifBlank { store.lastJoined()?.config?.eventCode.orEmpty() }; val joined = code.takeIf { it.isNotBlank() }?.let(store::joined) ?: store.lastJoined(); if (joined != null) currentEventCode = joined.config.eventCode; return joined
    }

    private fun startRace() {
        val profile = RaceProfileStore.profile(this)
        if (!profile.isReady) { AlertDialog.Builder(this).setTitle("선수등록 필요").setMessage("먼저 선수 등록 메뉴에서 아이디, 닉네임, 배번을 저장해 주세요.").setPositiveButton("선수 등록") { _, _ -> showRegistration() }.setNegativeButton("취소", null).show(); return }
        val joined = currentJoined() ?: run { AlertDialog.Builder(this).setTitle("대회 참가 필요").setMessage("START 전에 대회 참가 메뉴에서 참가할 대회를 선택해 주세요.").setPositiveButton("대회 참가") { _, _ -> showEvents() }.setNegativeButton("취소", null).show(); return }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION); return }
        currentEventCode = joined.config.eventCode; val localCourseId = joined.localCourseId; val baseConfig = joined.config
        homeEventStatus?.setTextColor(Color.LTGRAY); homeEventStatus?.text = "${baseConfig.name} · 세션 상태 확인 중…"
        Thread {
            val stateResult = runCatching { client.eventState(baseConfig.eventCode) }
            if (stateResult.isSuccess) {
                val state = stateResult.getOrThrow()
                if (state.phase == "WAITING" || state.phase == "ENDED") {
                    runOnUiThread { homeEventStatus?.setTextColor(WARN); homeEventStatus?.text = state.notice; AlertDialog.Builder(this).setTitle(if (state.phase == "WAITING") "대회 오픈 대기" else "대회 종료").setMessage(state.notice).setPositiveButton("확인", null).show() }
                    return@Thread
                }
            }
            val reference = runCatching { client.fetchReference(baseConfig.eventCode) }.getOrDefault(baseConfig.reference)
            val cfg = baseConfig.copy(reference = reference)
            runOnUiThread {
                store.saveActiveConfig(cfg, localCourseId, reference)
                startForegroundService(Intent(this, RaceTimingService::class.java).apply { action = RaceTimingService.ACTION_ARM; putExtra(RaceTimingService.EXTRA_CONFIG, cfg.toJson().toString()); putExtra(RaceTimingService.EXTRA_COURSE_ID, localCourseId) })
                currentEventCode = cfg.eventCode; showLive()
                if (stateResult.isFailure) Toast.makeText(this, "서버 상태 확인이 안 되어도 휴대폰 로컬 계측은 계속합니다. 연결 복구 후 자동 분류됩니다.", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun showLive() {
        mode = MODE_LIVE
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(0, 0, 230)) }; setContentView(root)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), 0, dp(12), 0); setBackgroundColor(Color.rgb(47, 47, 47)) }
        top.addView(Button(this).apply { text = "‹  Live"; textSize = 17f; isAllCaps = false; setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); setOnClickListener { showHome() } }, LinearLayout.LayoutParams(dp(112), dp(56)))
        liveHeader = TextView(this).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END; textSize = 14f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) }
        top.addView(liveHeader, LinearLayout.LayoutParams(0, dp(56), 1f)); root.addView(top)
        bestTime = addLiveTimeBlock(root, "Best", "1"); previousTime = addLiveTimeBlock(root, "Previous", "2"); currentTime = addLiveTimeBlock(root, "Current", "3")
        deltaPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(8), dp(6), dp(8), dp(6)); setBackgroundColor(Color.rgb(55, 55, 55)) }
        deltaPanel?.addView(TextView(this).apply { text = "DELTA"; textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER })
        deltaTime = TextView(this).apply { text = "—"; textSize = 92f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); includeFontPadding = false }
        deltaPanel?.addView(deltaTime, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(deltaPanel, LinearLayout.LayoutParams(-1, 0, 1.15f))
        liveFooter = TextView(this).apply { gravity = Gravity.CENTER; textSize = 11f; setTextColor(Color.LTGRAY); setBackgroundColor(Color.rgb(28, 28, 28)); setPadding(dp(8), dp(5), dp(8), dp(5)) }
        root.addView(liveFooter, LinearLayout.LayoutParams(-1, dp(34))); renderLive()
    }

    private fun addLiveTimeBlock(root: LinearLayout, label: String, index: String): TextView {
        val block = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(6), dp(10), dp(3)); setBackgroundColor(Color.rgb(0, 0, 230)) }
        block.addView(TextView(this).apply { text = label; textSize = 13f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply { text = index; textSize = 43f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; includeFontPadding = false }, LinearLayout.LayoutParams(dp(58), -1))
        val time = TextView(this).apply { text = "—"; textSize = 72f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; includeFontPadding = false }
        row.addView(time, LinearLayout.LayoutParams(0, -1, 1f)); block.addView(row, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(block, LinearLayout.LayoutParams(-1, 0, 1.55f)); return time
    }

    private fun renderHomeState() { startButton?.text = "START" }

    private fun renderLive() {
        if (mode != MODE_LIVE) return
        val s = store.snapshot(); val eventCode = s.eventCode.ifBlank { currentEventCode }.ifBlank { "PRACTICE" }; val currentRunId = s.runId
        val historical = store.completed().filter { it.eventCode == eventCode && it.runId != currentRunId }; val valid = historical.filter { it.status == "VALID" }; val best = (valid.ifEmpty { historical }).minByOrNull { it.elapsedMs }; val previous = historical.maxByOrNull { it.finishedAtMs }
        val elapsed = if (s.state == "RUNNING" && s.startedAtMs > 0L) (System.currentTimeMillis() - s.startedAtMs).coerceAtLeast(0L) else s.elapsedMs
        bestTime?.text = best?.elapsedMs?.let(::formatBigTime) ?: "—"; previousTime?.text = previous?.elapsedMs?.let(::formatBigTime) ?: "—"; currentTime?.text = formatBigTime(elapsed)
        val stateLabel = when (s.state) { "ARMED" -> "ARMED · START GATE"; "RUNNING" -> "RUNNING"; "FINISHED" -> "FINISH · ${s.validation}"; else -> "READY" }; liveHeader?.text = "$stateLabel  ·  $eventCode"
        val d = s.deltaMs
        if (d == null || s.state != "RUNNING") { deltaPanel?.setBackgroundColor(Color.rgb(55, 55, 55)); deltaTime?.text = "—" } else { val faster = d <= 0L; deltaPanel?.setBackgroundColor(if (faster) Color.rgb(0, 145, 20) else Color.rgb(210, 0, 0)); deltaTime?.text = "%s%.1f".format(if (faster) "−" else "+", abs(d) / 1000.0) }
        liveFooter?.text = buildString { append("GPS ±").append(s.gpsAccuracyM.roundToInt()).append("m"); if (s.totalM > 0) append("  ·  ").append(s.routeM.roundToInt()).append("/").append(s.totalM.roundToInt()).append("m"); if (s.serverStatus.isNotBlank()) append("  ·  ").append(s.serverStatus) }
    }

    private fun addTopBar(root: LinearLayout, title: String, back: Boolean) {
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(14), 0); setBackgroundColor(Color.rgb(46, 46, 46)) }
        if (back) top.addView(Button(this).apply { text = "‹"; textSize = 28f; isAllCaps = false; setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); setOnClickListener { showHome() } }, LinearLayout.LayoutParams(dp(58), dp(68)))
        top.addView(TextView(this).apply { text = title; textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, dp(68), 1f)); root.addView(top)
    }

    private fun menuButton(label: String, action: () -> Unit) = Button(this).apply { text = label; textSize = 16f; isAllCaps = false; setTypeface(typeface, Typeface.BOLD); setOnClickListener { action() } }
    private fun darkInput(hintText: String, value: String): EditText = EditText(this).apply { hint = hintText; setHintTextColor(Color.GRAY); setTextColor(Color.WHITE); textSize = 16f; setSingleLine(true); setText(value); setPadding(dp(12), 0, dp(12), 0); background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(6).toFloat(); setColor(Color.rgb(38, 38, 38)); setStroke(dp(1), Color.rgb(90, 90, 90)) } }
    private fun inputLp() = LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) }
    private fun formatBigTime(ms: Long): String { val safe = ms.coerceAtLeast(0L); val minute = safe / 60_000; val seconds = (safe % 60_000) / 1000; val tenth = (safe % 1000) / 100; return if (minute > 0) "%d:%02d.%d".format(minute, seconds, tenth) else "%d.%d".format(seconds, tenth) }
    private fun hideKeyboard() { val token = currentFocus?.windowToken ?: return; (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(token, 0); currentFocus?.clearFocus() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val MODE_HOME = 0; private const val MODE_REGISTER = 1; private const val MODE_EVENTS = 2; private const val MODE_LIVE = 3; private const val REQ_LOCATION = 8801
        private val GOOD = Color.rgb(75, 215, 115); private val WARN = Color.rgb(255, 140, 70)
    }
}
