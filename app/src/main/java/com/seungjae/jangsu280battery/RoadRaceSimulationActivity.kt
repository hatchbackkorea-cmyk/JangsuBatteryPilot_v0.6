package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs

class RoadRaceSimulationActivity : Activity() {
    companion object {
        private const val PREFS = "road_granfondo_ui_v1"
        private const val KEY_COURSE_ID = "road_course_id"
        private const val KEY_TARGET_HOUR = "target_hour"
        private const val KEY_TARGET_MINUTE = "target_minute"
        private const val KEY_TARGET_SPEED_INDEX = "target_speed_index"
        private const val KEY_PLAN_BASIS = "plan_basis"
        private const val KEY_CUTOFF_DERIVED_SEC = "cutoff_derived_riding_sec"
        private const val KEY_START_HOUR = "start_hour"
        private const val KEY_START_MINUTE = "start_minute"
        private const val KEY_NICK = "group_nick"
        private const val MAX_RIDERS = 20
        private const val BASIS_TIME = "time"
        private const val BASIS_SPEED = "speed"
        private const val BASIS_CUTOFF = "cutoff"
        private const val BASIS_FTP = "ftp"
        private const val BASIS_WKG = "wkg"
        private const val BASIS_STRAVA = "strava"
        private const val AUTO_TARGET_REAL_SEC = 50.0
        private const val MIN_AUTO_MULTIPLIER = 1.0
        private const val MAX_AUTO_MULTIPLIER = 2400.0
        private val TARGET_SPEEDS = (100..500 step 5).map { it / 10.0 }
        private val STOP_MINUTES = (0..60).toList()
    }

    private data class RiderAidInput(val poi: RoutePoi, val check: CheckBox, val spinner: Spinner)
    private data class RiderCutoffCandidate(val name: String, val km: Double)

    private lateinit var courseRepo: CourseRepository
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private var course: CourseData? = null
    private val riderConfigs = mutableListOf<SimulationRiderConfig>()
    private var riderPlans: List<SimulationRiderPlan> = emptyList()

    private lateinit var tvCourse: TextView
    private lateinit var tvRiders: TextView
    private lateinit var riderCards: LinearLayout
    private lateinit var tvClock: TextView
    private lateinit var tvStandings: TextView
    private lateinit var liveView: RoadRaceSimulationView
    private lateinit var summaryView: RoadSimulationSummaryView
    private lateinit var tvSummary: TextView
    private lateinit var btnPlay: Button

    private val handler = Handler(Looper.getMainLooper())
    private var playing = false
    private var simSec = 0.0
    private var multiplier = 60.0
    private var lastTickMs = 0L
    private val expandedRiderCards = mutableSetOf<SimulationRiderConfig>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_road_race_simulation)
        courseRepo = CourseRepository(this)

        tvCourse = findViewById(R.id.tvSimCourse)
        tvRiders = findViewById(R.id.tvSimRiders)
        riderCards = findViewById(R.id.simRiderCards)
        tvClock = findViewById(R.id.tvSimClock)
        tvStandings = findViewById(R.id.tvSimStandings)
        liveView = findViewById(R.id.roadSimulationView)
        summaryView = findViewById(R.id.roadSimulationSummaryView)
        tvSummary = findViewById(R.id.tvSimSummary)
        btnPlay = findViewById(R.id.btnSimPlay)

        findViewById<Button>(R.id.btnSimBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnSimAddRider).setOnClickListener { showRiderDialog() }
        findViewById<Button>(R.id.btnSimClearRiders).setOnClickListener {
            pauseSimulation()
            riderConfigs.clear()
            expandedRiderCards.clear()
            riderPlans = emptyList()
            simSec = 0.0
            ensureDefaultSelfRider(force = true)
            refreshRiders()
            rebuildPlans()
            renderFrame()
        }
        btnPlay.setOnClickListener { if (playing) pauseSimulation() else playSimulation() }
        findViewById<Button>(R.id.btnSimReset).setOnClickListener { resetSimulation() }

        loadCourse()
        refreshRiders()
        renderFrame()
    }

    override fun onPause() {
        pauseSimulation()
        super.onPause()
    }

    private fun loadCourse() {
        val id = prefs.getString(KEY_COURSE_ID, null)
        course = id?.let { runCatching { courseRepo.loadCourse(it) }.getOrNull() }
        val c = course
        tvCourse.text = if (c == null) {
            "ROAD 화면에서 먼저 대회 GPX를 불러와 주세요."
        } else {
            val aids = RoadRaceSimulationEngine.aidStations(c)
            "${c.name}\n거리 ${one(c.totalKm)} km · 상승 ${c.totalAscentM.toInt()} m · 보급/급수 ${aids.size}곳"
        }
        liveView.setData(c, emptyList())
        ensureDefaultSelfRider()
        rebuildPlans()
    }

    /** ROAD 계획 화면에서 저장된 내 목표와 보급 선택을 시뮬레이터의 기본 참가자로 복사한다. */
    private fun ensureDefaultSelfRider(force: Boolean = false) {
        val c = course ?: return
        if (!force && riderConfigs.any { it.isSelf }) return
        if (force) riderConfigs.removeAll { it.isSelf }
        val targetSec = savedSelfTargetSec(c)
        if (targetSec < 600.0) return
        val targetBasis = when (prefs.getString(KEY_PLAN_BASIS, BASIS_TIME)) {
            BASIS_SPEED -> BASIS_SPEED
            BASIS_CUTOFF -> BASIS_CUTOFF
            BASIS_STRAVA -> BASIS_STRAVA
            else -> BASIS_TIME
        }
        val nickname = prefs.getString(KEY_NICK, null)?.trim().orEmpty().ifBlank { "나" }
        val selfStrava = if (targetBasis == BASIS_STRAVA) StravaPerformanceEstimator.snapshot(this) else null
        val self = SimulationRiderConfig(
            nickname = nickname,
            targetSec = targetSec,
            targetBasis = targetBasis,
            startOffsetSec = 0.0,
            aidSelections = savedSelfAidSelections(c),
            cutoffSelections = savedRaceCutoffs(c),
            isSelf = true,
            weightKg = selfStrava?.weightKg,
            ftpW = selfStrava?.effectiveFtpW,
            wattsPerKg = selfStrava?.wattsPerKg,
            stravaYear = selfStrava?.year,
            powerCurve = selfStrava?.powerCurve,
            performanceSource = selfStrava?.sourceLabel.orEmpty()
        )
        riderConfigs.add(0, self)
    }

    private fun savedSelfTargetSec(c: CourseData): Double {
        return when (prefs.getString(KEY_PLAN_BASIS, BASIS_TIME)) {
            BASIS_SPEED -> {
                val defaultIdx = TARGET_SPEEDS.indexOfFirst { it >= 25.0 }.coerceAtLeast(0)
                val idx = prefs.getInt(KEY_TARGET_SPEED_INDEX, defaultIdx).coerceIn(TARGET_SPEEDS.indices)
                val speed = TARGET_SPEEDS[idx]
                c.totalKm / speed * 3600.0
            }
            BASIS_CUTOFF -> runCatching {
                RoadGranfondoEngine.solveCutoffTarget(
                    c,
                    savedRaceStartMinuteOfDay(),
                    savedRaceCutoffs(c),
                    savedSelfAidSelections(c)
                ).ridingTargetSec
            }.getOrElse { prefs.getLong(KEY_CUTOFF_DERIVED_SEC, 0L).toDouble() }
            BASIS_STRAVA -> {
                val snap = StravaPerformanceEstimator.snapshot(this) ?: return 0.0
                val w = snap.weightKg ?: return 0.0
                val f = snap.effectiveFtpW ?: return 0.0
                runCatching { RoadPowerPaceEstimator.estimate(c, w, f, snap.powerCurve).ridingTargetSec }.getOrDefault(0.0)
            }
            else -> {
                val h = prefs.getInt(KEY_TARGET_HOUR, 5).coerceIn(0, 20)
                val m = prefs.getInt(KEY_TARGET_MINUTE, 0).coerceIn(0, 59)
                h * 3600.0 + m * 60.0
            }
        }
    }

    private fun savedSelfAidSelections(c: CourseData): List<RoadAidSelection> {
        val courseKey = RoadGranfondoEngine.courseKey(c)
        return RoadRaceSimulationEngine.aidStations(c).mapNotNull { poi ->
            val suffix = aidPrefSuffix(courseKey, poi)
            val checked = prefs.getBoolean("aid_${suffix}_checked", false)
            val min = prefs.getInt("aid_${suffix}_min", 5).coerceIn(0, 60)
            if (!checked || min <= 0) null
            else RoadAidSelection(poi.name.ifBlank { "보급소" }, poi.routeKm, min * 60.0)
        }
    }

    private fun showRiderDialog(editIndex: Int? = null) {
        if (editIndex == null && riderConfigs.size >= MAX_RIDERS) {
            Toast.makeText(this, "시뮬레이션은 최대 20명입니다.", Toast.LENGTH_LONG).show(); return
        }
        val c = course ?: run {
            Toast.makeText(this, "ROAD 화면에서 대회 GPX를 먼저 불러와 주세요.", Toast.LENGTH_LONG).show(); return
        }
        val existing = editIndex?.let { riderConfigs.getOrNull(it) }
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(4), dp(18), dp(12)) }
        val name = EditText(this).apply {
            hint = "참가자 이름/닉네임"
            setText(existing?.nickname.orEmpty())
        }
        wrap.addView(name, LinearLayout.LayoutParams(-1, -2))

        fun label(text: String) {
            wrap.addView(TextView(this).apply { this.text = text; setPadding(0, dp(10), 0, dp(2)) }, LinearLayout.LayoutParams(-1, -2))
        }
        fun spinner(values: List<String>): Spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@RoadRaceSimulationActivity, android.R.layout.simple_spinner_dropdown_item, values)
        }

        wrap.addView(TextView(this).apply {
            text = "목표시간·평속·컷오프뿐 아니라 FTP·W/kg·Strava 기준으로도 참가자 페이스를 자동 추정합니다. 체중/FTP/Wkg는 서로 연동됩니다."
            setPadding(0, 0, 0, dp(4))
        })

        label("목표 기준 · 보급시간은 별도")
        val existingTarget = existing?.targetSec ?: 5.0 * 3600.0
        val existingBasis = existing?.targetBasis ?: BASIS_TIME
        val basisValues = listOf(
            "목표 주행시간",
            "목표 평속",
            "컷오프 페이스 · 자동 평속",
            "FTP 기준 · 체중 포함 자동추정",
            "W/kg 기준 · FTP 자동계산",
            "Strava 기준 · 선택연도 자동분석"
        )
        val basis = spinner(basisValues).apply {
            setSelection(when (existingBasis) {
                BASIS_SPEED -> 1
                BASIS_CUTOFF -> 2
                BASIS_FTP -> 3
                BASIS_WKG -> 4
                BASIS_STRAVA -> 5
                else -> 0
            })
        }
        wrap.addView(basis, LinearLayout.LayoutParams(-1, -2))

        label("목표 순수 주행시간")
        val timeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val targetHour = (existingTarget / 3600.0).toInt().coerceIn(0, 20)
        val targetMinute = ((existingTarget.toLong() % 3600L) / 60L).toInt().coerceIn(0, 59)
        val hour = spinner((0..20).map { "${it}시간" }).apply { setSelection(targetHour) }
        val minute = spinner((0..59).map { String.format(Locale.US, "%02d분", it) }).apply { setSelection(targetMinute) }
        timeRow.addView(hour, LinearLayout.LayoutParams(0, -2, 1f))
        timeRow.addView(minute, LinearLayout.LayoutParams(0, -2, 1f))
        wrap.addView(timeRow, LinearLayout.LayoutParams(-1, -2))

        label("목표 평속")
        val currentSpeed = if (existingTarget > 0.0) c.totalKm / (existingTarget / 3600.0) else 25.0
        val speedIndex = TARGET_SPEEDS.indices.minByOrNull { kotlin.math.abs(TARGET_SPEEDS[it] - currentSpeed) } ?: 0
        val speed = spinner(TARGET_SPEEDS.map { "${one(it)} km/h" }).apply { setSelection(speedIndex) }
        wrap.addView(speed, LinearLayout.LayoutParams(-1, -2))

        val cutoffPreview = TextView(this).apply { setPadding(0, dp(5), 0, dp(3)) }
        wrap.addView(cutoffPreview, LinearLayout.LayoutParams(-1, -2))

        // FTP / Wkg / Strava 능력 입력 영역
        val powerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(2))
        }
        label("라이더 능력 · FTP/Wkg/Strava 기준에서 사용")
        val metricRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // 새 참가자에게 관리자 자신의 FTP/체중이 자동 복사되면 잘못된 시뮬레이션이 되므로 비워 둔다.
        // 기본 '나' 참가자를 수정할 때는 Rider Control Center에 동기화된 내 값까지 fallback으로 사용한다.
        val syncProfile = RiderServerSync(this)
        val defaultWeight = existing?.weightKg ?: if (existing?.isSelf == true) syncProfile.weightKg() else null
        val defaultFtp = existing?.ftpW ?: if (existing?.isSelf == true) syncProfile.ftpW() else null
        val defaultWkg = existing?.wattsPerKg ?: if (defaultWeight != null && defaultFtp != null && defaultWeight > 0.0) defaultFtp / defaultWeight else null
        val weight = EditText(this).apply {
            hint = "체중 kg"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(defaultWeight?.let { String.format(Locale.US, "%.1f", it) }.orEmpty())
        }
        val ftp = EditText(this).apply {
            hint = "FTP W"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(defaultFtp?.let { String.format(Locale.US, "%.0f", it) }.orEmpty())
        }
        val wkg = EditText(this).apply {
            hint = "W/kg"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(defaultWkg?.let { String.format(Locale.US, "%.2f", it) }.orEmpty())
        }
        metricRow.addView(weight, LinearLayout.LayoutParams(0, -2, 1f))
        metricRow.addView(ftp, LinearLayout.LayoutParams(0, -2, 1f))
        metricRow.addView(wkg, LinearLayout.LayoutParams(0, -2, 1f))
        powerBox.addView(metricRow, LinearLayout.LayoutParams(-1, -2))

        val stravaStore = StravaReviewStore(this)
        val stravaSecure = StravaSecureStore(this)
        val activeStrava = stravaStore.loadActive()
        val stravaYears = activeStrava?.availableYears.orEmpty()
        val stravaYearRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val stravaYear = spinner(if (stravaYears.isEmpty()) listOf("연도 없음") else stravaYears.map { "${it}년" }).apply {
            isEnabled = stravaYears.isNotEmpty()
            val wanted = existing?.stravaYear ?: activeStrava?.resolvedYear()
            if (wanted != null && wanted in stravaYears) setSelection(stravaYears.indexOf(wanted))
        }
        val stravaManage = Button(this).apply {
            text = if (stravaSecure.isConnected()) "Strava 관리" else "Strava 연결"
            setOnClickListener { startActivity(Intent(this@RoadRaceSimulationActivity, StravaReviewActivity::class.java)) }
        }
        stravaYearRow.addView(stravaYear, LinearLayout.LayoutParams(0, -2, 1f))
        stravaYearRow.addView(stravaManage, LinearLayout.LayoutParams(dp(122), -2))
        powerBox.addView(stravaYearRow, LinearLayout.LayoutParams(-1, -2))

        val powerPreview = TextView(this).apply {
            setPadding(0, dp(4), 0, dp(4))
            textSize = 12f
        }
        powerBox.addView(powerPreview, LinearLayout.LayoutParams(-1, -2))
        wrap.addView(powerBox, LinearLayout.LayoutParams(-1, -2))

        var syncingMetrics = false
        fun metricDouble(e: EditText): Double? = e.text.toString().trim().toDoubleOrNull()
        fun setMetric(e: EditText, text: String) {
            if (e.text.toString() != text) e.setText(text)
        }
        fun syncFromFtp() {
            if (syncingMetrics) return
            val ww = metricDouble(weight) ?: return
            val ff = metricDouble(ftp) ?: return
            if (ww <= 0.0) return
            syncingMetrics = true
            setMetric(wkg, String.format(Locale.US, "%.2f", ff / ww))
            syncingMetrics = false
        }
        fun syncFromWkg() {
            if (syncingMetrics) return
            val ww = metricDouble(weight) ?: return
            val ratio = metricDouble(wkg) ?: return
            if (ww <= 0.0) return
            syncingMetrics = true
            setMetric(ftp, String.format(Locale.US, "%.0f", ww * ratio))
            syncingMetrics = false
        }
        fun addWatcher(e: EditText, action: () -> Unit) {
            e.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if (!syncingMetrics) action() }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        addWatcher(ftp) { syncFromFtp() }
        addWatcher(wkg) { syncFromWkg() }
        addWatcher(weight) {
            if (wkg.hasFocus()) syncFromWkg() else syncFromFtp()
        }

        fun selectedStravaYear(): Int? = if (stravaYears.isEmpty()) null else stravaYears.getOrNull(stravaYear.selectedItemPosition)

        fun applyStravaSnapshot(refreshNetwork: Boolean = false) {
            val year = selectedStravaYear() ?: activeStrava?.resolvedYear()
            val snap = StravaPerformanceEstimator.snapshot(this, year)
            if (snap == null) {
                powerPreview.text = "Strava 연동 프로필이 없습니다. 'Strava 연결'에서 전체 ROAD 분석 후 기준연도를 적용해 주세요."
                return
            }
            syncingMetrics = true
            snap.weightKg?.let { setMetric(weight, String.format(Locale.US, "%.1f", it)) }
            snap.effectiveFtpW?.let { setMetric(ftp, String.format(Locale.US, "%.0f", it)) }
            if (snap.weightKg != null && snap.effectiveFtpW != null) setMetric(wkg, String.format(Locale.US, "%.2f", snap.effectiveFtpW / snap.weightKg))
            syncingMetrics = false
            powerPreview.text = buildString {
                append("${snap.sourceLabel}")
                snap.yearEstimatedFtpW?.let { append(" · 연도추정 ${it.toInt()}W") }
                snap.currentProfileFtpW?.let { append(" · 현재프로필 ${it.toInt()}W") }
                snap.weightKg?.let { append(" · ${one(it)}kg") }
                snap.wattsPerKg?.let { append(" · ${String.format(Locale.US, "%.2f", it)} W/kg") }
                if (!stravaSecure.hasProfileRead()) append("\n※ Strava 체중/현재 FTP 자동갱신은 profile:read_all 권한 재연결이 필요합니다.")
            }
            if (refreshNetwork && stravaSecure.isConnected() && stravaSecure.hasProfileRead()) {
                Thread {
                    val result = runCatching {
                        val token = StravaClient.ensureAccessToken(stravaSecure)
                        StravaClient.getAuthenticatedAthlete(token)
                    }
                    runOnUiThread {
                        result.onSuccess { athlete ->
                            stravaSecure.saveAthleteProfile(athlete.weightKg, athlete.ftpW)
                            if (basis.selectedItemPosition == 5) applyStravaSnapshot(false)
                        }
                    }
                }.start()
            }
        }

        stravaYear.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (basis.selectedItemPosition == 5) applyStravaSnapshot(false)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        label("출발 지연")
        val startDelay = spinner((0..60).map { "${it}분" }).apply {
            setSelection(((existing?.startOffsetSec ?: 0.0) / 60.0).toInt().coerceIn(0, 60))
        }
        wrap.addView(startDelay, LinearLayout.LayoutParams(-1, -2))

        label("보급소별 선택 · 각 참가자마다 별도 설정")
        val aidInputs = mutableListOf<RiderAidInput>()
        val aids = RoadRaceSimulationEngine.aidStations(c)
        if (aids.isEmpty()) {
            wrap.addView(TextView(this).apply {
                text = "이 GPX에는 보급/급수 포인트가 없습니다."
                setPadding(0, dp(5), 0, dp(5))
            })
        } else {
            aids.forEach { poi ->
                val saved = existing?.aidSelections?.minByOrNull { abs(it.km - poi.routeKm) }
                    ?.takeIf { abs(it.km - poi.routeKm) <= 0.03 }
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(3), 0, dp(3)) }
                val check = CheckBox(this).apply {
                    text = "${one(poi.routeKm)}km  ${poi.name.ifBlank { "보급소" }}"
                    isChecked = saved != null
                }
                val stopMin = ((saved?.stopSec ?: 0.0) / 60.0).toInt().coerceIn(0, 60)
                val stop = spinner(STOP_MINUTES.map { "${it}분" }).apply {
                    setSelection(stopMin)
                    isEnabled = check.isChecked
                }
                check.setOnCheckedChangeListener { _, checked -> stop.isEnabled = checked }
                row.addView(check, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(stop, LinearLayout.LayoutParams(dp(104), -2))
                wrap.addView(row, LinearLayout.LayoutParams(-1, -2))
                aidInputs += RiderAidInput(poi, check, stop)
            }
        }

        fun currentAids(): List<RoadAidSelection> = aidInputs.mapNotNull { input ->
            val stopMin = STOP_MINUTES.getOrElse(input.spinner.selectedItemPosition) { 0 }
            if (!input.check.isChecked || stopMin <= 0) null
            else RoadAidSelection(input.poi.name.ifBlank { "보급소" }, input.poi.routeKm, stopMin * 60.0)
        }

        fun updateCutoffPreview() {
            val isCutoff = basis.selectedItemPosition == 2
            cutoffPreview.visibility = if (isCutoff) View.VISIBLE else View.GONE
            if (!isCutoff) return
            val raceCutoffs = savedRaceCutoffs(c)
            if (raceCutoffs.isEmpty()) {
                cutoffPreview.text = "⚠ ROAD 페이스 계획에서 컷오프 지점과 마감시각을 먼저 설정해 주세요."
                return
            }
            val delayMin = startDelay.selectedItemPosition.coerceIn(0, 60)
            val participantStart = (savedRaceStartMinuteOfDay() + delayMin) % 1440
            val result = runCatching { RoadGranfondoEngine.solveCutoffTarget(c, participantStart, raceCutoffs, currentAids()) }
            cutoffPreview.text = result.fold(
                onSuccess = { sol ->
                    "컷오프 ${raceCutoffs.size}곳 적용 · 출발 ${clockMinuteOfDay(participantStart)} · 필요 최소 평속 ${one(sol.requiredAvgKph)} km/h · 순수주행 ${duration(sol.ridingTargetSec)} · 기준 ${sol.controlling.name}"
                },
                onFailure = { "⚠ 컷오프 계산 확인: ${it.message}" }
            )
        }

        fun applyBasisUi() {
            val byTime = basis.selectedItemPosition == 0
            val bySpeed = basis.selectedItemPosition == 1
            val byPower = basis.selectedItemPosition in 3..5
            val byStrava = basis.selectedItemPosition == 5
            hour.isEnabled = byTime
            minute.isEnabled = byTime
            speed.isEnabled = bySpeed
            timeRow.alpha = if (byTime) 1.0f else 0.42f
            speed.alpha = if (bySpeed) 1.0f else 0.42f
            powerBox.visibility = if (byPower) View.VISIBLE else View.GONE
            stravaYear.isEnabled = byStrava && stravaYears.isNotEmpty()
            stravaManage.visibility = if (byStrava) View.VISIBLE else View.GONE
            if (byStrava) applyStravaSnapshot(true)
            else if (byPower) powerPreview.text = "체중 + FTP/Wkg를 GPX 경사 물리모델에 넣어 예상 순수 주행시간을 자동 계산합니다."
            updateCutoffPreview()
        }
        basis.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = applyBasisUi()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        startDelay.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = updateCutoffPreview()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        aidInputs.forEach { input ->
            input.check.setOnCheckedChangeListener { _, checked ->
                input.spinner.isEnabled = checked
                updateCutoffPreview()
            }
            input.spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = updateCutoffPreview()
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        applyBasisUi()

        val title = if (existing == null) "참가자 추가" else if (existing.isSelf) "내 시뮬레이션 설정" else "참가자 수정"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(4)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(wrap) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val buttonBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(5))
        }
        val deleteButton = Button(this).apply { text = "삭제"; visibility = if (existing != null) View.VISIBLE else View.GONE }
        val cancelButton = Button(this).apply { text = "취소" }
        val saveButton = Button(this).apply { text = if (existing == null) "참가자 추가" else "저장" }
        if (existing != null) buttonBar.addView(deleteButton, LinearLayout.LayoutParams(0, -2, 1f))
        else buttonBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        buttonBar.addView(cancelButton, LinearLayout.LayoutParams(-2, -2))
        buttonBar.addView(saveButton, LinearLayout.LayoutParams(-2, -2))
        root.addView(buttonBar, LinearLayout.LayoutParams(-1, -2))

        val dialog = AlertDialog.Builder(this).setTitle(title).setView(root).create()

        cancelButton.setOnClickListener { dialog.dismiss() }
        deleteButton.setOnClickListener {
            if (existing?.isSelf == true) {
                Toast.makeText(this, "내 참가자는 기본 참가자라 삭제되지 않습니다.", Toast.LENGTH_LONG).show()
            } else {
                editIndex?.let {
                    riderConfigs.getOrNull(it)?.let(expandedRiderCards::remove)
                    riderConfigs.removeAt(it)
                }
                pauseSimulation(); simSec = 0.0; refreshRiders(); rebuildPlans(); renderFrame(); dialog.dismiss()
            }
        }
        saveButton.setOnClickListener {
            val nickname = name.text.toString().trim().ifBlank {
                if (existing?.isSelf == true) "나" else "라이더${(editIndex ?: riderConfigs.size) + 1}"
            }
            val targetBasis = when (basis.selectedItemPosition) {
                1 -> BASIS_SPEED
                2 -> BASIS_CUTOFF
                3 -> BASIS_FTP
                4 -> BASIS_WKG
                5 -> BASIS_STRAVA
                else -> BASIS_TIME
            }
            val riderAids = currentAids()
            val delaySec = startDelay.selectedItemPosition * 60.0
            val raceCutoffs = savedRaceCutoffs(c)
            val selectedSpeed = TARGET_SPEEDS.getOrElse(speed.selectedItemPosition) { 25.0 }

            var storedWeight: Double? = existing?.weightKg
            var storedFtp: Double? = existing?.ftpW
            var storedWkg: Double? = existing?.wattsPerKg
            var storedYear: Int? = existing?.stravaYear
            var storedCurve: StravaPowerCurve? = existing?.powerCurve
            var source = existing?.performanceSource.orEmpty()

            val targetSec = when (targetBasis) {
                BASIS_SPEED -> c.totalKm / selectedSpeed.coerceAtLeast(1.0) * 3600.0
                BASIS_CUTOFF -> {
                    if (raceCutoffs.isEmpty()) {
                        Toast.makeText(this, "ROAD 페이스 계획에서 컷오프 지점과 마감시각을 먼저 설정해 주세요.", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    val participantStart = (savedRaceStartMinuteOfDay() + startDelay.selectedItemPosition) % 1440
                    runCatching { RoadGranfondoEngine.solveCutoffTarget(c, participantStart, raceCutoffs, riderAids).ridingTargetSec }
                        .getOrElse {
                            Toast.makeText(this, "컷오프 페이스 계산 실패: ${it.message}", Toast.LENGTH_LONG).show()
                            return@setOnClickListener
                        }
                }
                BASIS_FTP, BASIS_WKG, BASIS_STRAVA -> {
                    if (targetBasis == BASIS_STRAVA) applyStravaSnapshot(false)
                    val ww = metricDouble(weight)
                    val ratio = metricDouble(wkg)
                    val ff = when (targetBasis) {
                        BASIS_WKG -> if (ww != null && ratio != null) ww * ratio else null
                        else -> metricDouble(ftp)
                    }
                    if (ww == null || ww !in 30.0..200.0 || ff == null || ff !in 50.0..600.0) {
                        Toast.makeText(this, "체중/FTP/Wkg 값을 확인해 주세요.", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    if (targetBasis == BASIS_STRAVA) {
                        val year = selectedStravaYear()
                        val snap = StravaPerformanceEstimator.snapshot(this, year)
                        if (snap == null || year == null) {
                            Toast.makeText(this, "Strava ROAD 분석에서 기준연도를 먼저 연동해 주세요.", Toast.LENGTH_LONG).show()
                            return@setOnClickListener
                        }
                        storedYear = year
                        storedCurve = snap.powerCurve
                        source = snap.sourceLabel
                    } else {
                        storedYear = null
                        storedCurve = null
                        source = if (targetBasis == BASIS_WKG) "W/kg 직접입력" else "FTP 직접입력"
                    }
                    storedWeight = ww
                    storedFtp = ff
                    storedWkg = ff / ww
                    val estimate = runCatching { RoadPowerPaceEstimator.estimate(c, ww, ff, storedCurve) }.getOrElse {
                        Toast.makeText(this, "파워 페이스 계산 실패: ${it.message}", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    Toast.makeText(this, "${source} · 예상 순수주행 ${duration(estimate.ridingTargetSec)} · ${one(estimate.averageKph)} km/h", Toast.LENGTH_LONG).show()
                    estimate.ridingTargetSec
                }
                else -> hour.selectedItemPosition * 3600.0 + minute.selectedItemPosition * 60.0
            }
            if (targetSec < 600.0) {
                Toast.makeText(this, "목표 주행시간/평속/컷오프/파워 조건을 확인해 주세요.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val updated = SimulationRiderConfig(
                nickname = nickname,
                targetSec = targetSec,
                targetBasis = targetBasis,
                startOffsetSec = delaySec,
                aidSelections = riderAids,
                cutoffSelections = if (targetBasis == BASIS_CUTOFF) raceCutoffs else emptyList(),
                isSelf = existing?.isSelf == true,
                weightKg = storedWeight,
                ftpW = storedFtp,
                wattsPerKg = storedWkg,
                stravaYear = storedYear,
                powerCurve = storedCurve,
                performanceSource = source
            )
            if (editIndex == null) riderConfigs += updated
            else {
                val wasExpanded = existing?.let { it in expandedRiderCards } == true
                existing?.let(expandedRiderCards::remove)
                riderConfigs[editIndex] = updated
                if (wasExpanded) expandedRiderCards.add(updated)
            }
            if (updated.isSelf) prefs.edit().putString(KEY_NICK, nickname).apply()
            pauseSimulation(); simSec = 0.0; refreshRiders(); rebuildPlans(); renderFrame(); dialog.dismiss()
        }

        dialog.setOnShowListener {
            val dm = resources.displayMetrics
            dialog.window?.setLayout((dm.widthPixels * 0.96f).toInt(), (dm.heightPixels * 0.90f).toInt())
            scroll.post { scroll.scrollTo(0, 0) }
        }
        dialog.show()
    }

    private fun showEditRiderPicker() {
        if (riderConfigs.isEmpty()) {
            ensureDefaultSelfRider(force = true)
            refreshRiders()
        }
        if (riderConfigs.isEmpty()) {
            Toast.makeText(this, "수정할 참가자가 없습니다.", Toast.LENGTH_SHORT).show(); return
        }
        val labels = riderConfigs.map { if (it.isSelf) "⭐ ${it.nickname} (나)" else it.nickname }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("수정할 참가자 선택")
            .setItems(labels) { _, which -> showRiderDialog(which) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun rebuildPlans(): Boolean {
        val c = course ?: return false
        riderPlans = riderConfigs.mapNotNull { cfg -> runCatching { RoadRaceSimulationEngine.buildRiderPlan(c, cfg) }.getOrNull() }
        summaryView.setData(c, riderPlans)
        updateAutoMultiplier()
        return riderPlans.isNotEmpty()
    }

    private fun playSimulation() {
        if (course == null) { Toast.makeText(this, "ROAD 화면에서 대회 GPX를 먼저 불러와 주세요.", Toast.LENGTH_LONG).show(); return }
        if (riderConfigs.isEmpty()) { Toast.makeText(this, "참가자를 한 명 이상 추가해 주세요.", Toast.LENGTH_LONG).show(); return }
        if (riderPlans.size != riderConfigs.size && !rebuildPlans()) return
        val end = riderPlans.maxOfOrNull { it.finishRaceSec } ?: 0.0
        updateAutoMultiplier()
        if (simSec >= end - 0.5) simSec = 0.0
        playing = true
        lastTickMs = System.currentTimeMillis()
        btnPlay.text = "Ⅱ 일시정지"
        summaryView.visibility = View.GONE
        tvSummary.visibility = View.GONE
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun pauseSimulation() {
        playing = false
        btnPlay.text = "▶ 시뮬레이션 재생"
        handler.removeCallbacks(tick)
    }

    private fun resetSimulation() {
        pauseSimulation()
        simSec = 0.0
        summaryView.visibility = View.GONE
        tvSummary.visibility = View.GONE
        renderFrame()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!playing) return
            val now = System.currentTimeMillis()
            val dt = ((now - lastTickMs).coerceAtLeast(0L) / 1000.0).coerceAtMost(1.0)
            lastTickMs = now
            simSec += dt * multiplier
            val end = riderPlans.maxOfOrNull { it.finishRaceSec } ?: 0.0
            if (simSec >= end) {
                simSec = end
                renderFrame()
                finishSimulation()
                return
            }
            renderFrame()
            handler.postDelayed(this, 200L)
        }
    }

    private fun finishSimulation() {
        pauseSimulation()
        val c = course ?: return
        val sorted = riderPlans.sortedBy { it.finishRaceSec }
        tvSummary.text = buildString {
            append("🏁 시뮬레이션 완료\n")
            sorted.forEachIndexed { i, r ->
                append("${i + 1}위 ${r.nickname} · ${duration(r.finishRaceSec)}")
                val stop = r.aidStops.sumOf { it.durationSec }
                append(" · 보급체류 ${duration(stop)}")
                if (i < sorted.lastIndex) append("\n")
            }
            val standings = RoadRaceSimulationEngine.checkpointStandings(riderPlans)
            if (standings.isNotEmpty()) {
                append("\n\n포인트별 예상 1위")
                standings.take(14).forEach { s ->
                    s.riders.firstOrNull()?.let { (name, sec) -> append("\n${s.checkpointName} · $name ${duration(sec)}") }
                }
            }
        }
        tvSummary.visibility = View.VISIBLE
        summaryView.setData(c, riderPlans)
        summaryView.visibility = View.VISIBLE
    }

    private fun renderFrame() {
        val c = course
        val states = if (c != null && riderPlans.isNotEmpty()) riderPlans.map { RoadRaceSimulationEngine.stateAt(c, it, simSec) } else emptyList()
        liveView.setData(c, states)
        val expectedReal = riderPlans.maxOfOrNull { it.finishRaceSec }?.let { it / multiplier }
        tvClock.text = if (expectedReal != null && expectedReal.isFinite()) {
            "대회 경과 ${duration(simSec)} · 자동 ${multiplier.toInt()}배속 · 약 ${expectedReal.toInt().coerceAtLeast(1)}초"
        } else {
            "대회 경과 ${duration(simSec)} · 자동 배속"
        }
        tvStandings.text = if (states.isEmpty()) "참가자 설정을 확인하고 재생하세요." else buildString {
            val order = states.sortedWith(compareByDescending<SimulationRiderState> { it.routeKm }.thenBy {
                riderPlans.firstOrNull { r -> r.nickname == it.nickname }?.finishRaceSec ?: Double.MAX_VALUE
            })
            order.forEachIndexed { i, s ->
                append("${i + 1}. ${s.nickname} · ${one(s.routeKm)} km · ${s.status}")
                if (s.aidName != null) append(" ${duration(s.aidElapsedSec)}")
                if (s.speedKph > 0.1) append(" · ${one(s.speedKph)} km/h")
                if (i < order.lastIndex) append("\n")
            }
        }
    }

    private fun refreshRiders() {
        tvRiders.text = "참가자 ${riderConfigs.size}/20 · 참가자별 카드 · 보급설정 독립"
        riderCards.removeAllViews()
        if (riderConfigs.isEmpty()) {
            riderCards.addView(TextView(this).apply {
                text = "참가자 없음"
                textSize = 14f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, dp(8), 0, dp(8))
            }, LinearLayout.LayoutParams(-1, -2))
            return
        }

        riderConfigs.forEachIndexed { index, rider ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_sim_rider_card)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            val cardLp = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }

            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val name = TextView(this).apply {
                text = buildString {
                    if (rider.isSelf) append("⭐ ")
                    append(rider.nickname)
                    if (rider.isSelf) append(" (나)")
                }
                textSize = 19f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(getColor(if (rider.isSelf) R.color.warn else R.color.text_primary))
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val edit = Button(this).apply {
                text = "수정"
                textSize = 12f
                minHeight = 0
                minimumHeight = 0
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener { showRiderDialog(index) }
            }
            header.addView(name, LinearLayout.LayoutParams(0, dp(42), 1f))
            header.addView(edit, LinearLayout.LayoutParams(dp(72), dp(40)))
            card.addView(header, LinearLayout.LayoutParams(-1, -2))

            val avg = course?.let { c -> if (rider.targetSec > 0.0) c.totalKm / (rider.targetSec / 3600.0) else 0.0 } ?: 0.0
            val targetLabel = when (rider.targetBasis) {
                BASIS_SPEED -> "목표평속 ${one(avg)} km/h · 주행 ${duration(rider.targetSec)}"
                BASIS_CUTOFF -> "컷오프 기준 · 필요 ${one(avg)} km/h · 주행 ${duration(rider.targetSec)}"
                BASIS_FTP -> "FTP 기준 · 주행 ${duration(rider.targetSec)} · ${one(avg)} km/h"
                BASIS_WKG -> "W/kg 기준 · 주행 ${duration(rider.targetSec)} · ${one(avg)} km/h"
                BASIS_STRAVA -> "Strava ${rider.stravaYear ?: "-"}년 기준 · 주행 ${duration(rider.targetSec)} · ${one(avg)} km/h"
                else -> "목표시간 ${duration(rider.targetSec)} · 환산 ${one(avg)} km/h"
            }
            card.addView(TextView(this).apply {
                text = buildString {
                    append(targetLabel)
                    if (rider.startOffsetSec > 0) append(" · 출발 +${duration(rider.startOffsetSec)}")
                    if (rider.weightKg != null && rider.ftpW != null) {
                        append("\n${one(rider.weightKg)}kg · FTP ${rider.ftpW.toInt()}W")
                        rider.wattsPerKg?.let { append(" · ${String.format(Locale.US, "%.2f", it)} W/kg") }
                        if (rider.performanceSource.isNotBlank()) append(" · ${rider.performanceSource}")
                    }
                }
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, dp(2), 0, dp(7))
            }, LinearLayout.LayoutParams(-1, -2))

            if (rider.aidSelections.isEmpty()) {
                card.addView(TextView(this).apply {
                    text = "보급 PASS"
                    textSize = 13f
                    setTextColor(getColor(R.color.text_secondary))
                    setBackgroundResource(R.drawable.bg_sim_aid_row)
                    setPadding(dp(9), dp(7), dp(9), dp(7))
                }, LinearLayout.LayoutParams(-1, -2))
            } else {
                val total = rider.aidSelections.sumOf { it.stopSec }
                val expanded = rider in expandedRiderCards
                val toggle = TextView(this).apply {
                    text = "보급 ${rider.aidSelections.size}곳 · 총 ${duration(total)}   ${if (expanded) "▲" else "▼"}"
                    textSize = 13f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(getColor(R.color.accent))
                    setBackgroundResource(R.drawable.bg_sim_aid_row)
                    setPadding(dp(9), dp(8), dp(9), dp(8))
                    setOnClickListener {
                        if (rider in expandedRiderCards) expandedRiderCards.remove(rider) else expandedRiderCards.add(rider)
                        refreshRiders()
                    }
                }
                card.addView(toggle, LinearLayout.LayoutParams(-1, -2))

                if (expanded) {
                    rider.aidSelections.sortedBy { it.km }.forEach { aid ->
                        val row = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = android.view.Gravity.CENTER_VERTICAL
                            setBackgroundResource(R.drawable.bg_sim_aid_row)
                            setPadding(dp(9), dp(6), dp(9), dp(6))
                        }
                        val left = TextView(this).apply {
                            text = "${one(aid.km)}km  ${aid.name}"
                            textSize = 12f
                            setTextColor(getColor(R.color.text_primary))
                            setSingleLine(true)
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        }
                        val right = TextView(this).apply {
                            text = duration(aid.stopSec)
                            textSize = 12f
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            setTextColor(getColor(R.color.good))
                            gravity = android.view.Gravity.END
                            setPadding(dp(8), 0, 0, 0)
                        }
                        row.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
                        row.addView(right, LinearLayout.LayoutParams(dp(58), -2))
                        card.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                    }
                }
            }
            riderCards.addView(card, cardLp)
        }
    }

    private fun updateAutoMultiplier() {
        val end = riderPlans.maxOfOrNull { it.finishRaceSec } ?: return
        if (!end.isFinite() || end <= 0.0) return
        multiplier = (end / AUTO_TARGET_REAL_SEC).coerceIn(MIN_AUTO_MULTIPLIER, MAX_AUTO_MULTIPLIER)
    }

    private fun duration(secRaw: Double): String {
        val sec = secRaw.toLong().coerceAtLeast(0L)
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun savedRaceStartMinuteOfDay(): Int {
        val h = prefs.getInt(KEY_START_HOUR, 8).coerceIn(0, 23)
        val m = prefs.getInt(KEY_START_MINUTE, 0).coerceIn(0, 59)
        return h * 60 + m
    }

    private fun savedRaceCutoffs(c: CourseData): List<RoadCutoffSelection> {
        val courseKey = RoadGranfondoEngine.courseKey(c)
        return riderCutoffCandidates(c).mapNotNull { candidate ->
            val suffix = "${courseKey}_${(candidate.km * 1000).toInt()}"
            val checked = prefs.getBoolean("cutoff_${suffix}_checked", false)
            if (!checked) return@mapNotNull null
            val hour = prefs.getInt("cutoff_${suffix}_hour", 0).coerceIn(0, 23)
            val minute = prefs.getInt("cutoff_${suffix}_minute", 0).coerceIn(0, 59)
            RoadCutoffSelection(candidate.name, candidate.km, hour * 60 + minute)
        }
    }

    private fun riderCutoffCandidates(c: CourseData): List<RiderCutoffCandidate> {
        val pois = c.pois.filter { poi ->
            val text = "${poi.name} ${poi.desc} ${poi.type}".lowercase()
            poi.isSupplyLike() || text.contains("컷오프") || text.contains("cutoff") || text.contains("cut-off") ||
                text.contains("체크포인트") || text.contains("checkpoint")
        }.map { RiderCutoffCandidate(it.name.ifBlank { "체크포인트" }, it.routeKm) }
            .filter { it.km in 0.05..(c.totalKm - 0.05) }
        val merged = mutableListOf<RiderCutoffCandidate>()
        (pois.sortedBy { it.km } + RiderCutoffCandidate("FINISH", c.totalKm)).forEach { candidate ->
            val near = merged.indexOfFirst { abs(it.km - candidate.km) <= 0.08 }
            if (near < 0) merged += candidate
            else if (candidate.name == "FINISH") merged[near] = candidate
        }
        return merged.sortedBy { it.km }
    }

    private fun clockMinuteOfDay(minuteOfDay: Int): String {
        val v = ((minuteOfDay % 1440) + 1440) % 1440
        return String.format(Locale.US, "%02d:%02d", v / 60, v % 60)
    }

    private fun aidPrefSuffix(courseKey: String, poi: RoutePoi): String =
        "${courseKey}_${(poi.routeKm * 1000).toInt()}"

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun one(v: Double) = String.format(Locale.US, "%.1f", v)
}
