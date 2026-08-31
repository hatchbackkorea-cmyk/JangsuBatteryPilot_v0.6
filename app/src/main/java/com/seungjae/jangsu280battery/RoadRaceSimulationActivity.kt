package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs

class RoadRaceSimulationActivity : Activity() {
    companion object {
        private const val REQ_RIDER_FITS = 401
        private const val PREFS = "road_granfondo_ui_v1"
        private const val KEY_COURSE_ID = "road_course_id"
        private const val MAX_RIDERS = 20
        private const val AUTO_TARGET_REAL_SEC = 50.0
        private const val MIN_AUTO_MULTIPLIER = 1.0
        private const val MAX_AUTO_MULTIPLIER = 2400.0
    }

    private data class PendingRider(
        val nickname: String,
        val targetSec: Double?,
        val startOffsetSec: Double,
        val aidStopSec: Double,
        val power: RoadPowerProfile
    )

    private lateinit var courseRepo: CourseRepository
    private var course: CourseData? = null
    private val riderConfigs = mutableListOf<SimulationRiderConfig>()
    private var riderPlans: List<SimulationRiderPlan> = emptyList()
    private var pendingRider: PendingRider? = null

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
        findViewById<Button>(R.id.btnSimAddRider).setOnClickListener { showRiderDialog(requireFit = true) }
        findViewById<Button>(R.id.btnSimAddPowerRider).setOnClickListener { showRiderDialog(requireFit = false) }
        findViewById<Button>(R.id.btnSimClearRiders).setOnClickListener {
            pauseSimulation(); riderConfigs.clear(); riderPlans = emptyList(); simSec = 0.0; refreshRiders(); renderFrame()
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
        val id = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_COURSE_ID, null)
        course = id?.let { runCatching { courseRepo.loadCourse(it) }.getOrNull() }
        val c = course
        tvCourse.text = if (c == null) {
            "ROAD 화면에서 먼저 대회 GPX를 불러와 주세요."
        } else {
            val aids = RoadRaceSimulationEngine.aidStations(c)
            "${c.name}\n거리 ${one(c.totalKm)} km · 상승 ${c.totalAscentM.toInt()} m · 보급/급수 ${aids.size}곳"
        }
        liveView.setData(c, emptyList())
    }

    private fun showRiderDialog(requireFit: Boolean) {
        if (riderConfigs.size >= MAX_RIDERS) {
            Toast.makeText(this, "시뮬레이션은 최대 20명입니다.", Toast.LENGTH_LONG).show(); return
        }
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(38, 8, 38, 0) }
        fun field(hint: String, decimal: Boolean = false): EditText {
            val e = EditText(this).apply {
                this.hint = hint
                inputType = if (decimal) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_TEXT
            }
            wrap.addView(e, LinearLayout.LayoutParams(-1, -2))
            return e
        }
        val name = field("참가자 이름/닉네임")
        val target = field("개인 목표시간 선택 · 예: 06:30")
        val startDelay = field("출발 지연(분) · 동시출발이면 0", true).apply { setText("0") }
        val aidStop = field("보급소 기본 휴식(분) · 모두 패스면 0", true).apply { setText("0") }
        val p1 = field("1분 파워 W · 선택", true)
        val p5 = field("5분 파워 W · 선택", true)
        val p20 = field("20분 파워 W · 선택", true)
        val p60 = field("60분/FTP 근처 W · 선택", true)

        AlertDialog.Builder(this)
            .setTitle(if (requireFit) "참가자 FIT 추가" else "파워 기반 참가자 추가")
            .setMessage(if (requireFit) "한 참가자의 최근 ROAD FIT 여러 개를 한 번에 선택할 수 있습니다." else "FIT가 없을 때만 사용하세요. 20분 또는 60분 파워가 있으면 더 좋습니다.")
            .setView(wrap)
            .setPositiveButton(if (requireFit) "FIT 선택" else "추가") { _, _ ->
                val nickname = name.text.toString().trim().ifBlank { "라이더${riderConfigs.size + 1}" }
                val pending = PendingRider(
                    nickname = nickname,
                    targetSec = parseTargetSeconds(target.text.toString().trim()),
                    startOffsetSec = (startDelay.text.toString().toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0) * 60.0,
                    aidStopSec = (aidStop.text.toString().toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0) * 60.0,
                    power = RoadPowerProfile(p1.num(), p5.num(), p20.num(), p60.num())
                )
                if (requireFit) {
                    pendingRider = pending
                    pickRiderFits()
                } else {
                    if (pending.power.sustainableW() == null) {
                        Toast.makeText(this, "파워만 추가할 때는 1/5/20/60분 중 하나 이상 입력해 주세요.", Toast.LENGTH_LONG).show()
                    } else {
                        riderConfigs += SimulationRiderConfig(nickname, RoadSimulationProfileBuilder.fromFits(emptyList(), pending.power), pending.targetSec, pending.startOffsetSec, pending.aidStopSec)
                        refreshRiders(); rebuildPlans()
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun pickRiderFits() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(i, REQ_RIDER_FITS)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_RIDER_FITS || resultCode != RESULT_OK || data == null) return
        val pending = pendingRider ?: return
        val uris = mutableListOf<Uri>()
        data.data?.let(uris::add)
        data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri }
        if (uris.isEmpty()) return
        tvRiders.text = "${pending.nickname} FIT ${uris.size}개 분석 중…"
        Thread {
            val analyses = mutableListOf<HistoricalRideAnalysis>()
            val errors = mutableListOf<String>()
            uris.distinct().forEach { uri ->
                runCatching { HistoricalRideImporter.analyze(this, uri, HistoricalSourceType.FIT) }
                    .onSuccess { analyses += it }
                    .onFailure { errors += (it.message ?: "FIT 분석 실패") }
            }
            runOnUiThread {
                pendingRider = null
                if (analyses.isEmpty()) {
                    tvRiders.text = "FIT 분석 실패 · ${errors.firstOrNull() ?: "파일을 확인해 주세요."}"
                    return@runOnUiThread
                }
                val profile = RoadSimulationProfileBuilder.fromFits(analyses, pending.power)
                riderConfigs += SimulationRiderConfig(pending.nickname, profile, pending.targetSec, pending.startOffsetSec, pending.aidStopSec)
                refreshRiders(); rebuildPlans()
                Toast.makeText(this, "${pending.nickname} · FIT ${analyses.size}개 반영", Toast.LENGTH_LONG).show()
            }
        }.start()
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
                standings.take(14).forEach { s -> s.riders.firstOrNull()?.let { (name, sec) -> append("\n${s.checkpointName} · $name ${duration(sec)}") } }
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
        tvStandings.text = if (states.isEmpty()) "참가자 FIT을 추가하고 재생하세요." else buildString {
            val order = states.sortedWith(compareByDescending<SimulationRiderState> { it.routeKm }.thenBy { riderPlans.firstOrNull { r -> r.nickname == it.nickname }?.finishRaceSec ?: Double.MAX_VALUE })
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
            "참가자 없음 · 한 사람당 최근 ROAD FIT 2~5개 권장"
        } else buildString {
            append("참가자 ${riderConfigs.size}/20")
            riderConfigs.forEach { r ->
                append("\n• ${r.nickname} · FIT ${r.profile.fitCount}개")
                r.profile.power.sustainableW()?.let { append(" · 지속파워 ${it.toInt()}W") }
                if (r.startOffsetSec > 0) append(" · 출발 +${duration(r.startOffsetSec)}")
                append(if (r.defaultAidStopSec > 0) " · 보급 ${duration(r.defaultAidStopSec)}" else " · 보급 PASS")
            }
        }
    }

    private fun updateAutoMultiplier() {
        val end = riderPlans.maxOfOrNull { it.finishRaceSec } ?: return
        if (!end.isFinite() || end <= 0.0) return
        multiplier = (end / AUTO_TARGET_REAL_SEC).coerceIn(MIN_AUTO_MULTIPLIER, MAX_AUTO_MULTIPLIER)
    }

    private fun parseTargetSeconds(text: String): Double? {
        if (text.isBlank()) return null
        val p = text.split(":")
        if (p.size != 2) return null
        val h = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        return if (h >= 0 && m in 0..59) (h * 3600 + m * 60).toDouble() else null
    }

    private fun duration(secRaw: Double): String {
        val sec = secRaw.toLong().coerceAtLeast(0L)
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun one(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun EditText.num(): Double? = text.toString().trim().toDoubleOrNull()?.takeIf { it > 0.0 }
}
