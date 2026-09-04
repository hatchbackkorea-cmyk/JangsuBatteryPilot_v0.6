package com.seungjae.jangsu280battery

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONObject
import kotlin.math.roundToInt

/** RaceChrono-inspired local-first RACE screen. START only arms; official time begins at the START gate. */
class RaceActivity : Activity() {
    private lateinit var store: RaceDataStore
    private lateinit var client: RaceServerClient
    private lateinit var repo: CourseRepository
    private lateinit var profileName: EditText
    private lateinit var profileNickname: EditText
    private lateinit var profileStatus: TextView
    private lateinit var eventCode: EditText
    private lateinit var eventStatus: TextView
    private lateinit var courseSpinner: Spinner
    private lateinit var stateText: TextView
    private lateinit var timerText: TextView
    private lateinit var deltaText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var sectorText: TextView
    private lateinit var gpsText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private var courses: List<CourseMeta> = emptyList()
    private var joined: RaceDataStore.Joined? = null
    private val handler = Handler(Looper.getMainLooper())
    private val poller = object : Runnable { override fun run() { render(); handler.postDelayed(this, 100L) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = RaceDataStore(this); client = RaceServerClient(this); repo = CourseRepository(this)
        buildUi(); refreshCourses()
        joined = store.lastJoined()
        joined?.let { j -> eventCode.setText(j.config.eventCode); eventStatus.text = "최근 대회 · ${j.config.name}"; selectCourse(j.localCourseId) }
        intent?.data?.getQueryParameter("event")?.takeIf { it.isNotBlank() }?.let { eventCode.setText(it.uppercase()) }
        Thread { runCatching { client.flushPending() } }.start()
        handler.post(poller)
    }

    override fun onDestroy() { handler.removeCallbacks(poller); super.onDestroy() }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(getColor(R.color.bg)); isFillViewport = true }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(24)) }
        scroll.addView(root); setContentView(scroll)
        root.addView(TextView(this).apply { text = "🏁 RACE MODE"; textSize = 27f; setTextColor(getColor(R.color.text_primary)); setTypeface(typeface, 1) })

        val profile = RaceProfileStore.profile(this)
        val profilePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(getColor(R.color.panel))
        }
        profilePanel.addView(TextView(this).apply {
            text = "RACE 프로필"
            textSize = 16f
            setTypeface(typeface, 1)
            setTextColor(getColor(R.color.text_primary))
        })
        profilePanel.addView(TextView(this).apply {
            text = "이름과 닉네임만 입력합니다. 저장 버튼을 눌러야 실제 RACE 프로필에 적용됩니다."
            textSize = 11f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(3), 0, dp(6))
        })
        val profileRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        profileName = EditText(this).apply {
            hint = "이름"
            setSingleLine()
            textSize = 15f
            setText(profile.name)
            layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f)
        }
        profileNickname = EditText(this).apply {
            hint = "닉네임"
            setSingleLine()
            textSize = 15f
            setText(profile.nickname)
            layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(6) }
        }
        profileRow.addView(profileName); profileRow.addView(profileNickname)
        profilePanel.addView(profileRow)
        val saveProfileButton = Button(this).apply {
            text = "프로필 저장"
            textSize = 15f
            setOnClickListener { saveProfile() }
        }
        profilePanel.addView(saveProfileButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })
        profileStatus = TextView(this).apply {
            text = if (profile.isReady) "✓ 저장됨 · ${profile.nickname} (${profile.name})" else "아직 저장된 RACE 프로필이 없습니다."
            textSize = 12f
            setTextColor(if (profile.isReady) getColor(R.color.good) else getColor(R.color.text_secondary))
            setPadding(0, dp(5), 0, 0)
        }
        profilePanel.addView(profileStatus)
        root.addView(profilePanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val eventPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); setBackgroundColor(getColor(R.color.panel)) }
        eventPanel.addView(TextView(this).apply { text = "대회 참가"; textSize = 16f; setTypeface(typeface, 1); setTextColor(getColor(R.color.text_primary)) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        eventCode = EditText(this).apply { hint = "대회 코드"; setSingleLine(); layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f) }
        row.addView(eventCode)
        row.addView(Button(this).apply { text = "참가 / 불러오기"; setOnClickListener { joinEvent() } }, LinearLayout.LayoutParams(dp(145), dp(50)).apply { marginStart = dp(6) })
        eventPanel.addView(row)
        eventStatus = TextView(this).apply { text = "대회 코드가 없으면 로컬 연습 RACE로 사용할 수 있습니다."; textSize = 12f; setTextColor(getColor(R.color.text_secondary)); setPadding(0, dp(6), 0, 0) }
        eventPanel.addView(eventStatus); root.addView(eventPanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        root.addView(TextView(this).apply { text = "코스"; textSize = 13f; setTextColor(getColor(R.color.text_secondary)); setPadding(0, dp(14), 0, dp(3)) })
        courseSpinner = Spinner(this); root.addView(courseSpinner, LinearLayout.LayoutParams(-1, dp(52)))

        stateText = TextView(this).apply { text = "STOPPED"; gravity = Gravity.CENTER; textSize = 20f; setTypeface(typeface, 1); setTextColor(getColor(R.color.text_secondary)); setPadding(0, dp(18), 0, dp(4)) }
        timerText = TextView(this).apply { text = "0.000"; gravity = Gravity.CENTER; textSize = 58f; setTypeface(typeface, 1); setTextColor(Color.WHITE) }
        deltaText = TextView(this).apply { text = "DELTA —"; gravity = Gravity.CENTER; textSize = 35f; setTypeface(typeface, 1); setTextColor(Color.LTGRAY); setPadding(0, dp(2), 0, dp(8)) }
        root.addView(stateText); root.addView(timerText); root.addView(deltaText)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progress = 0 }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(18)))
        gpsText = TextView(this).apply { gravity = Gravity.CENTER; textSize = 12f; setTextColor(getColor(R.color.text_secondary)); setPadding(0, dp(5), 0, dp(8)) }
        root.addView(gpsText)
        sectorText = TextView(this).apply { text = "Sector 기록은 FINISH와 함께 자동 저장됩니다."; textSize = 15f; setTextColor(getColor(R.color.text_primary)); setPadding(dp(12), dp(10), dp(12), dp(10)); setBackgroundColor(getColor(R.color.panel)) }
        root.addView(sectorText, LinearLayout.LayoutParams(-1, -2))

        startButton = Button(this).apply { text = "START · 계측 준비"; textSize = 22f; setTypeface(typeface, 1); setOnClickListener { onStartRace() } }
        root.addView(startButton, LinearLayout.LayoutParams(-1, dp(78)).apply { topMargin = dp(14) })
        stopButton = Button(this).apply { text = "STOP"; setOnClickListener { onStopRace() } }
        root.addView(stopButton, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(6) })
        root.addView(Button(this).apply { text = "내 RACE 기록"; setOnClickListener { showRecords() } }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        root.addView(TextView(this).apply {
            text = "START를 눌러도 즉시 기록되지 않습니다. START 게이트를 올바른 방향으로 통과하는 순간 자동 계측됩니다. FINISH 즉시 휴대폰에 먼저 저장하고 서버 전송은 그 다음입니다."
            textSize = 12f; setTextColor(getColor(R.color.text_secondary)); setPadding(0, dp(12), 0, 0)
        })
    }

    private fun saveProfile() {
        val name = profileName.text?.toString().orEmpty().trim()
        val nickname = profileNickname.text?.toString().orEmpty().trim()
        if (name.isBlank() || nickname.isBlank()) {
            profileStatus.setTextColor(getColor(R.color.warn))
            profileStatus.text = "이름과 닉네임을 모두 입력해 주세요."
            Toast.makeText(this, "이름과 닉네임을 모두 입력해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val saved = RaceProfileStore.save(this, name, nickname)
        profileStatus.setTextColor(getColor(R.color.good))
        profileStatus.text = "✓ 저장됨 · ${saved.nickname} (${saved.name})"
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(profileNickname.windowToken, 0)
        Toast.makeText(this, "RACE 프로필을 저장했습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun refreshCourses() {
        courses = repo.listCourses()
        courseSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, courses.map { "${it.name} · %.2f km".format(it.totalKm) })
        selectCourse(repo.activeMeta().id)
    }
    private fun selectCourse(id: String) { val i = courses.indexOfFirst { it.id == id }; if (i >= 0) courseSpinner.setSelection(i) }
    private fun selectedCourse(): CourseMeta? = courses.getOrNull(courseSpinner.selectedItemPosition)

    private fun requireSavedProfile(): RaceProfileStore.Profile? {
        val profile = RaceProfileStore.profile(this)
        if (!profile.isReady) {
            profileStatus.setTextColor(getColor(R.color.warn))
            profileStatus.text = "이름과 닉네임을 입력한 뒤 ‘프로필 저장’을 눌러 주세요."
            Toast.makeText(this, "RACE 프로필을 먼저 저장해 주세요.", Toast.LENGTH_LONG).show()
            return null
        }
        return profile
    }

    private fun joinEvent() {
        val profile = requireSavedProfile() ?: return
        val code = eventCode.text.toString().trim().uppercase()
        if (code.isBlank()) { Toast.makeText(this, "대회 코드를 입력하세요.", Toast.LENGTH_SHORT).show(); return }
        eventStatus.text = "대회 정보와 GPX 불러오는 중…"
        Thread {
            val result = runCatching {
                val j = client.join(code, profile)
                val old = store.joined(code)
                val existingId = old?.localCourseId?.takeIf { id -> repo.listCourses().any { it.id == id } }
                val localId = existingId ?: run {
                    val f = client.downloadCourse(code)
                    val meta = repo.importGpxFile(f, j.config.courseName, enqueueServer = false); f.delete(); meta.id
                }
                store.saveJoined(j.config, j.participantToken, localId)
                RaceDataStore.Joined(j.config, j.participantToken, localId)
            }
            runOnUiThread {
                result.onSuccess { j ->
                    joined = j; refreshCourses(); selectCourse(j.localCourseId)
                    eventStatus.setTextColor(getColor(R.color.good)); eventStatus.text = "✓ ${j.config.name} · GPX/게이트 준비 완료 · ${j.config.gates.size - 2}개 중간 게이트"
                }.onFailure {
                    eventStatus.setTextColor(getColor(R.color.warn)); eventStatus.text = "대회 참가 실패 · ${it.message ?: "서버 확인"}"
                }
            }
        }.start()
    }

    private fun onStartRace() {
        requireSavedProfile() ?: return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 8801); return
        }
        val meta = selectedCourse() ?: return
        val code = eventCode.text.toString().trim().uppercase()
        val currentJoined = if (code.isNotBlank()) store.joined(code) else null
        val baseConfig = if (currentJoined != null && currentJoined.localCourseId == meta.id) currentJoined.config
        else RaceGateMath.practiceConfig(meta.id, repo.loadCourse(meta.id), 4)
        eventStatus.text = if (baseConfig.eventCode == "PRACTICE") "연습 RACE · 4개 섹터 · 서버 순위에는 등록하지 않음" else "최신 선두 기준 기록 불러오는 중…"
        Thread {
            val reference = runCatching { client.fetchReference(baseConfig.eventCode) }.getOrDefault(baseConfig.reference)
            val cfg = baseConfig.copy(reference = reference)
            runOnUiThread {
                store.saveActiveConfig(cfg, meta.id, reference)
                val i = Intent(this, RaceTimingService::class.java).apply {
                    action = RaceTimingService.ACTION_ARM; putExtra(RaceTimingService.EXTRA_CONFIG, cfg.toJson().toString()); putExtra(RaceTimingService.EXTRA_COURSE_ID, meta.id)
                }
                startForegroundService(i)
                eventStatus.text = if (cfg.eventCode == "PRACTICE") "연습 RACE 준비 완료" else "✓ LIVE RACE 준비 · 선두 비교 ${if (reference.isEmpty()) "기록 없음" else "ON"}"
            }
        }.start()
    }

    private fun onStopRace() {
        val s = store.snapshot()
        if (s.state == "RUNNING") {
            AlertDialog.Builder(this).setTitle("현재 RACE를 중단할까요?")
                .setMessage("완주하지 않은 현재 Run은 공식 순위에 등록하지 않습니다. 이미 FINISH한 이전 Run 기록은 그대로 보존됩니다.")
                .setPositiveButton("현재 Run 폐기 후 STOP") { _, _ -> sendStop() }
                .setNegativeButton("계속 주행", null).show()
        } else sendStop()
    }
    private fun sendStop() { startService(Intent(this, RaceTimingService::class.java).apply { action = RaceTimingService.ACTION_STOP }) }

    private fun render() {
        val s = store.snapshot()
        stateText.text = when (s.state) { "ARMED" -> "ARMED · START 게이트 대기"; "RUNNING" -> "RUNNING · ${s.currentSector.ifBlank { "RACE" }}"; "FINISHED" -> "FINISH · ${s.validation}"; else -> "STOPPED" }
        val elapsed = when { s.state == "RUNNING" && s.startedAtMs > 0 -> (System.currentTimeMillis() - s.startedAtMs).coerceAtLeast(0L); else -> s.elapsedMs }
        timerText.text = formatRaceTime(elapsed)
        if (s.deltaMs == null || s.state != "RUNNING") { deltaText.text = "DELTA —"; deltaText.setTextColor(Color.LTGRAY) }
        else {
            val d = s.deltaMs; deltaText.text = "%s%.3f".format(if (d <= 0) "−" else "+", kotlin.math.abs(d) / 1000.0)
            deltaText.setTextColor(if (d <= 0) Color.rgb(30, 220, 110) else Color.rgb(255, 80, 80))
        }
        progress.progress = if (s.totalM > 1.0) ((s.routeM / s.totalM).coerceIn(0.0, 1.0) * 1000).roundToInt() else 0
        gpsText.text = "%.0f / %.0f m · GPS ±%.0fm · 최고 %.1f km/h · %s".format(s.routeM, s.totalM, s.gpsAccuracyM, s.maxSpeedKph, s.serverStatus)
        sectorText.text = if (s.sectors.isEmpty()) "Sector 기록 대기" else s.sectors.joinToString("\n") { r -> "S${r.index}  ${formatRaceTime(r.sectorMs)}   SPLIT ${formatRaceTime(r.splitMs)}${r.rank?.let { "   ${it}위" }.orEmpty()}" }
        startButton.isEnabled = s.state != "RUNNING" && s.state != "ARMED"
        startButton.text = if (s.state == "FINISHED") "START · 다음 Run 준비" else "START · 계측 준비"
        stopButton.isEnabled = s.state == "ARMED" || s.state == "RUNNING"
    }

    private fun showRecords() {
        val runs = store.completed().sortedByDescending { it.finishedAtMs }.take(30)
        val text = if (runs.isEmpty()) "아직 완주 기록이 없습니다." else runs.joinToString("\n\n") { r ->
            "${r.eventName} · Run ${r.runNumber}\n${formatRaceTime(r.elapsedMs)} · ${r.status}\n" + r.sectors.joinToString("  ") { "S${it.index} ${formatRaceTime(it.sectorMs)}" }
        }
        AlertDialog.Builder(this).setTitle("내 RACE 기록").setMessage(text).setPositiveButton("확인", null).show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
