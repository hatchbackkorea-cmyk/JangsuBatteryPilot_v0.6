package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
        private const val KEY_NICK = "group_nick"
        private const val MAX_RIDERS = 20
        private const val BASIS_TIME = "time"
        private const val BASIS_SPEED = "speed"
        private const val AUTO_TARGET_REAL_SEC = 50.0
        private const val MIN_AUTO_MULTIPLIER = 1.0
        private const val MAX_AUTO_MULTIPLIER = 2400.0
        private val TARGET_SPEEDS = (100..500 step 5).map { it / 10.0 }
        private val STOP_MINUTES = (0..60).toList()
    }

    private data class RiderAidInput(val poi: RoutePoi, val check: CheckBox, val spinner: Spinner)

    private lateinit var courseRepo: CourseRepository
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private var course: CourseData? = null
    private val riderConfigs = mutableListOf<SimulationRiderConfig>()
    private var riderPlans: List<SimulationRiderPlan> = emptyList()

    private lateinit var tvCourse: TextView
    private lateinit var tvRiders: TextView
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_road_race_simulation)
        courseRepo = CourseRepository(this)

        tvCourse = findViewById(R.id.tvSimCourse)
        tvRiders = findViewById(R.id.tvSimRiders)
        tvClock = findViewById(R.id.tvSimClock)
        tvStandings = findViewById(R.id.tvSimStandings)
        liveView = findViewById(R.id.roadSimulationView)
        summaryView = findViewById(R.id.roadSimulationSummaryView)
        tvSummary = findViewById(R.id.tvSimSummary)
        btnPlay = findViewById(R.id.btnSimPlay)

        findViewById<Button>(R.id.btnSimBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnSimAddRider).setOnClickListener { showRiderDialog() }
        findViewById<Button>(R.id.btnSimEditRider).setOnClickListener { showEditRiderPicker() }
        findViewById<Button>(R.id.btnSimClearRiders).setOnClickListener {
            pauseSimulation()
            riderConfigs.clear()
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
        val targetBasis = if (prefs.getString(KEY_PLAN_BASIS, BASIS_TIME) == BASIS_SPEED) BASIS_SPEED else BASIS_TIME
        val nickname = prefs.getString(KEY_NICK, null)?.trim().orEmpty().ifBlank { "나" }
        val self = SimulationRiderConfig(
            nickname = nickname,
            targetSec = targetSec,
            targetBasis = targetBasis,
            startOffsetSec = 0.0,
            aidSelections = savedSelfAidSelections(c),
            isSelf = true
        )
        riderConfigs.add(0, self)
    }

    private fun savedSelfTargetSec(c: CourseData): Double {
        return if (prefs.getString(KEY_PLAN_BASIS, BASIS_TIME) == BASIS_SPEED) {
            val defaultIdx = TARGET_SPEEDS.indexOfFirst { it >= 25.0 }.coerceAtLeast(0)
            val idx = prefs.getInt(KEY_TARGET_SPEED_INDEX, defaultIdx).coerceIn(TARGET_SPEEDS.indices)
            val speed = TARGET_SPEEDS[idx]
            c.totalKm / speed * 3600.0
        } else {
            val h = prefs.getInt(KEY_TARGET_HOUR, 5).coerceIn(0, 20)
            val m = prefs.getInt(KEY_TARGET_MINUTE, 0).coerceIn(0, 59)
            h * 3600.0 + m * 60.0
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
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(38, 8, 38, 0) }
        val name = EditText(this).apply {
            hint = "참가자 이름/닉네임"
            setText(existing?.nickname.orEmpty())
        }
        wrap.addView(name, LinearLayout.LayoutParams(-1, -2))

        fun label(text: String) {
            wrap.addView(TextView(this).apply { this.text = text; setPadding(0, 12, 0, 2) }, LinearLayout.LayoutParams(-1, -2))
        }
        fun spinner(values: List<String>): Spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@RoadRaceSimulationActivity, android.R.layout.simple_spinner_dropdown_item, values)
        }

        label("목표 기준 · 보급시간은 별도")
        val existingTarget = existing?.targetSec ?: 5.0 * 3600.0
        val existingBasis = existing?.targetBasis ?: BASIS_TIME
        val basis = spinner(listOf("목표 주행시간", "목표 평속")).apply {
            setSelection(if (existingBasis == BASIS_SPEED) 1 else 0)
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

        fun applyBasisUi() {
            val bySpeed = basis.selectedItemPosition == 1
            hour.isEnabled = !bySpeed
            minute.isEnabled = !bySpeed
            speed.isEnabled = bySpeed
            timeRow.alpha = if (bySpeed) 0.45f else 1.0f
            speed.alpha = if (bySpeed) 1.0f else 0.45f
        }
        basis.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = applyBasisUi()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        applyBasisUi()

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
                setPadding(0, 5, 0, 5)
            })
        } else {
            aids.forEach { poi ->
                val saved = existing?.aidSelections?.minByOrNull { abs(it.km - poi.routeKm) }
                    ?.takeIf { abs(it.km - poi.routeKm) <= 0.03 }
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 3, 0, 3) }
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

        val title = if (existing == null) "참가자 추가" else if (existing.isSelf) "내 시뮬레이션 설정" else "참가자 수정"
        val scroll = ScrollView(this).apply { addView(wrap) }
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("참가자마다 목표시간 또는 목표평속을 선택하고, 보급소 방문 여부와 정차시간도 각각 따로 설정합니다.")
            .setView(scroll)
            .setPositiveButton(if (existing == null) "추가" else "저장") { _, _ ->
                val nickname = name.text.toString().trim().ifBlank {
                    if (existing?.isSelf == true) "나" else "라이더${(editIndex ?: riderConfigs.size) + 1}"
                }
                val targetBasis = if (basis.selectedItemPosition == 1) BASIS_SPEED else BASIS_TIME
                val selectedSpeed = TARGET_SPEEDS.getOrElse(speed.selectedItemPosition) { 25.0 }
                val targetSec = if (targetBasis == BASIS_SPEED) {
                    c.totalKm / selectedSpeed.coerceAtLeast(1.0) * 3600.0
                } else {
                    hour.selectedItemPosition * 3600.0 + minute.selectedItemPosition * 60.0
                }
                val delaySec = startDelay.selectedItemPosition * 60.0
                val riderAids = aidInputs.mapNotNull { input ->
                    val stopMin = STOP_MINUTES.getOrElse(input.spinner.selectedItemPosition) { 0 }
                    if (!input.check.isChecked || stopMin <= 0) null
                    else RoadAidSelection(input.poi.name.ifBlank { "보급소" }, input.poi.routeKm, stopMin * 60.0)
                }
                if (targetSec < 600.0) {
                    Toast.makeText(this, "목표 주행시간 또는 목표 평속을 확인해 주세요.", Toast.LENGTH_LONG).show()
                } else {
                    val updated = SimulationRiderConfig(
                        nickname = nickname,
                        targetSec = targetSec,
                        targetBasis = targetBasis,
                        startOffsetSec = delaySec,
                        aidSelections = riderAids,
                        isSelf = existing?.isSelf == true
                    )
                    if (editIndex == null) riderConfigs += updated else riderConfigs[editIndex] = updated
                    if (updated.isSelf) prefs.edit().putString(KEY_NICK, nickname).apply()
                    pauseSimulation()
                    simSec = 0.0
                    refreshRiders()
                    rebuildPlans()
                    renderFrame()
                }
            }
            .setNegativeButton("취소", null)

        if (existing != null) {
            builder.setNeutralButton("삭제") { _, _ ->
                if (existing.isSelf) {
                    Toast.makeText(this, "내 참가자는 기본 참가자라 삭제되지 않습니다.", Toast.LENGTH_LONG).show()
                } else {
                    editIndex?.let { riderConfigs.removeAt(it) }
                    pauseSimulation()
                    simSec = 0.0
                    refreshRiders()
                    rebuildPlans()
                    renderFrame()
                }
            }
        }
        builder.show()
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
        tvRiders.text = if (riderConfigs.isEmpty()) {
            "참가자 없음"
        } else buildString {
            append("참가자 ${riderConfigs.size}/20 · 각 참가자 보급설정 독립")
            riderConfigs.forEach { r ->
                append("\n• ")
                if (r.isSelf) append("⭐ ")
                append("${r.nickname}")
                if (r.isSelf) append(" (나)")
                val avg = course?.let { courseData -> if (r.targetSec > 0.0) courseData.totalKm / (r.targetSec / 3600.0) else 0.0 } ?: 0.0
                if (r.targetBasis == BASIS_SPEED) {
                    append(" · 목표평속 ${one(avg)}km/h · 주행 ${duration(r.targetSec)}")
                } else {
                    append(" · 목표시간 ${duration(r.targetSec)} · 환산 ${one(avg)}km/h")
                }
                if (r.startOffsetSec > 0) append(" · 출발 +${duration(r.startOffsetSec)}")
                if (r.aidSelections.isEmpty()) {
                    append(" · 보급 PASS")
                } else {
                    val total = r.aidSelections.sumOf { it.stopSec }
                    append(" · 보급 ${r.aidSelections.size}곳/${duration(total)}")
                    append("\n   ↳ ")
                    append(r.aidSelections.joinToString(" · ") { "${it.name} ${duration(it.stopSec)}" })
                }
            }
            append("\n목록의 [참가자 수정]에서 사람별 보급소와 시간을 바꿀 수 있습니다.")
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

    private fun aidPrefSuffix(courseKey: String, poi: RoutePoi): String =
        "${courseKey}_${(poi.routeKm * 1000).toInt()}"

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun one(v: Double) = String.format(Locale.US, "%.1f", v)
}
