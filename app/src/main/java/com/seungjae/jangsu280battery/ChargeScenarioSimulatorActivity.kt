package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * v0.26.8 충전 시나리오 시뮬레이터.
 * 실제 주행/실제배터리/Proto/개인학습 저장소에는 쓰지 않는다.
 * 선택 코스와 등록 충전소/학습 모델은 읽기 전용으로 사용한다.
 */
class ChargeScenarioSimulatorActivity : Activity() {
    private lateinit var repo: CourseRepository
    private lateinit var courseMeta: CourseMeta
    private lateinit var course: CourseData
    private lateinit var chargingStore: ChargingStationStore
    private lateinit var learningStore: BatteryLearningStore
    private lateinit var replanReadOnlyStore: RideReplanStore
    private lateinit var plan: BatteryPlan
    private lateinit var stations: List<ChargingStation>

    private lateinit var tvCourse: TextView
    private lateinit var tvKm: TextView
    private lateinit var tvSoc: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var seekKm: SeekBar
    private lateinit var seekSoc: SeekBar
    private lateinit var seekSpeed: SeekBar
    private lateinit var etTime: EditText
    private lateinit var tvDecision: TextView
    private lateinit var tvTimeline: TextView
    private lateinit var btnToggleSkip: Button
    private lateinit var btnEmergencySearch: Button
    private lateinit var panelEmergency: LinearLayout
    private lateinit var tvEmergency: TextView
    private lateinit var btnEmergencyStep: Button

    private val skippedStationIds = linkedSetOf<String>()
    private var selectedEmergency: EvaluatedEmergencyCandidate? = null
    private var emergencyAnchorKm: Double? = null
    private var emergencyPhase = SimEmergencyPhase.NONE
    private var simTimeMinutes = 0.0
    private var emergencyTargetPct = 70.0

    private enum class SimEmergencyPhase { NONE, OUTBOUND, CHARGING, RETURN }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_charge_simulator)

        repo = CourseRepository(this)
        chargingStore = ChargingStationStore(this)
        learningStore = BatteryLearningStore(this)
        replanReadOnlyStore = RideReplanStore(this)
        courseMeta = repo.activeMeta()
        course = repo.loadCourse(courseMeta.id)
        stations = chargingStore.list(courseMeta.id).filter { it.routeKm in 0.0..course.totalKm }.sortedBy { it.routeKm }
        plan = BatteryPlan(course, learningStore, stations)

        tvCourse = findViewById(R.id.tvSimCourse)
        tvKm = findViewById(R.id.tvSimKm)
        tvSoc = findViewById(R.id.tvSimSoc)
        tvSpeed = findViewById(R.id.tvSimSpeed)
        seekKm = findViewById(R.id.seekSimKm)
        seekSoc = findViewById(R.id.seekSimSoc)
        seekSpeed = findViewById(R.id.seekSimSpeed)
        etTime = findViewById(R.id.etSimTime)
        tvDecision = findViewById(R.id.tvSimDecision)
        tvTimeline = findViewById(R.id.tvSimTimeline)
        btnToggleSkip = findViewById(R.id.btnSimToggleSkip)
        btnEmergencySearch = findViewById(R.id.btnSimEmergencySearch)
        panelEmergency = findViewById(R.id.panelSimEmergency)
        tvEmergency = findViewById(R.id.tvSimEmergency)
        btnEmergencyStep = findViewById(R.id.btnSimEmergencyStep)

        findViewById<Button>(R.id.btnSimBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnSimRecalc).setOnClickListener { recalcFromControls() }
        findViewById<Button>(R.id.btnSimReset).setOnClickListener { resetSimulation() }
        findViewById<Button>(R.id.btnSimPrevStation).setOnClickListener { jumpStation(-1) }
        findViewById<Button>(R.id.btnSimNextStation).setOnClickListener { jumpStation(+1) }
        findViewById<Button>(R.id.btnSimEmergencyCancel).setOnClickListener { clearEmergency() }
        btnToggleSkip.setOnClickListener { toggleSkipForRelevantStation() }
        btnEmergencySearch.setOnClickListener { searchEmergencyCandidates() }
        btnEmergencyStep.setOnClickListener { advanceEmergencySimulation() }

        seekKm.max = (course.totalKm * 10.0).roundToInt().coerceAtLeast(1)
        seekKm.progress = 0
        seekSoc.max = 100
        seekSoc.progress = 100
        seekSpeed.max = 50 // 5.0~30.0km/h, 0.5 단위
        seekSpeed.progress = 24 // 17km/h
        simTimeMinutes = currentMinutesOfDay()
        etTime.setText(formatClock(simTimeMinutes))

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateInputLabels()
                if (fromUser) recalc(false)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = recalcFromControls()
        }
        seekKm.setOnSeekBarChangeListener(listener)
        seekSoc.setOnSeekBarChangeListener(listener)
        seekSpeed.setOnSeekBarChangeListener(listener)

        tvCourse.text = "${courseMeta.name} · ${RideFormatter.one(course.totalKm)}km · 등록 충전소 ${stations.size}개"
        updateInputLabels()
        recalcFromControls()
    }

    private fun currentKm(): Double = (seekKm.progress / 10.0).coerceIn(0.0, course.totalKm)
    private fun currentSoc(): Double = seekSoc.progress.toDouble().coerceIn(0.0, 100.0)
    private fun currentSpeed(): Double = (5.0 + seekSpeed.progress * 0.5).coerceIn(5.0, 30.0)

    private fun updateInputLabels() {
        val km = currentKm()
        val p = course.pointAtKm(km)
        tvKm.text = "가상 위치 ${RideFormatter.one(km)} / ${RideFormatter.one(course.totalKm)}km · 고도 ${p.ele.roundToInt()}m"
        tvSoc.text = "가상 배터리 ${currentSoc().roundToInt()}%"
        tvSpeed.text = "예상 이동평균 ${String.format(Locale.US, "%.1f", currentSpeed())}km/h"
    }

    private fun recalcFromControls() {
        simTimeMinutes = parseClock(etTime.text?.toString()).also {
            if (it == null) Toast.makeText(this, "시각은 HH:mm 형식으로 입력하세요.", Toast.LENGTH_SHORT).show()
        } ?: simTimeMinutes
        etTime.setText(formatClock(simTimeMinutes))
        recalc(true)
    }

    private fun recalc(showToast: Boolean) {
        updateInputLabels()
        renderDecision()
        renderTimeline()
        renderEmergencyPanel()
        if (showToast) Toast.makeText(this, "시뮬레이션만 다시 계산했습니다. 실제 데이터는 변경되지 않습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun nextUnpassedStation(km: Double): ChargingStation? = stations.firstOrNull { it.routeKm > km + 0.08 }

    private fun stationAtOrNext(km: Double): ChargingStation? {
        stations.firstOrNull { kotlin.math.abs(it.routeKm - km) <= 0.55 }?.let { return it }
        return nextUnpassedStation(km)
    }

    private fun renderDecision() {
        val km = currentKm()
        val soc = currentSoc()
        val hard = AppSettings.hardReserve(this).toDouble()
        val recommended = AppSettings.finishTarget(this)
        val target = nextUnpassedStation(km)
        if (target == null) {
            val finishSoc = (soc - plan.internalConsumption(km, course.totalKm)).coerceIn(0.0, 100.0)
            tvDecision.text = when {
                finishSoc < hard -> "🔴 긴급 충전 필요\n종점 도착 예상 ${finishSoc.roundToInt()}% · 하드 리저브 ${hard.roundToInt()}% 미만"
                finishSoc < recommended -> "🟠 ECO 절약 연결\n종점 도착 예상 ${finishSoc.roundToInt()}% · 권장잔량 ${recommended.roundToInt()}% 미만, 하드 리저브 이상"
                else -> "🟢 정상\n종점 도착 예상 ${finishSoc.roundToInt()}% · 현재 계획 유지 가능"
            }
            btnToggleSkip.isEnabled = false
            btnToggleSkip.text = "남은 계획 충전소 없음"
            return
        }

        val arrival = predictedSocTo(km, soc, target.routeKm, target.detourKm)
        val status = when {
            arrival < hard -> "🔴 긴급 충전 필요"
            arrival < recommended -> "🟠 ECO 절약 연결 가능"
            else -> "🟢 정상"
        }
        val detail = when {
            arrival < hard -> "${target.name} 도착 예상 ${arrival.roundToInt()}% · 하드 리저브 ${hard.roundToInt()}% 아래로 예상되어 코스 주변 비상충전 후보 탐색이 필요합니다."
            arrival < recommended -> "${target.name} 도착 예상 ${arrival.roundToInt()}% · 권장 ${recommended.roundToInt()}%보다 낮지만 하드 리저브 ${hard.roundToInt()}% 이상이라 ECO로 연결 가능한 구간입니다."
            else -> "${target.name} 도착 예상 ${arrival.roundToInt()}% · 현재 계획 범위입니다."
        }
        val skipCandidate = stationAtOrNext(km)
        val skipText = skipCandidate?.let { skipFeasibilityText(it, km, soc, hard) }.orEmpty()
        tvDecision.text = "$status\n$detail${if (skipText.isNotBlank()) "\n\n$skipText" else ""}"

        if (skipCandidate != null) {
            btnToggleSkip.isEnabled = true
            val skipped = skippedStationIds.contains(skipCandidate.id)
            btnToggleSkip.text = if (skipped) "↩ ${skipCandidate.name} 충전 생략 취소" else "⏭ ${skipCandidate.name} 충전 생략 테스트"
        } else {
            btnToggleSkip.isEnabled = false
            btnToggleSkip.text = "생략 테스트할 충전소 없음"
        }
    }

    private fun skipFeasibilityText(station: ChargingStation, km: Double, soc: Double, hard: Double): String {
        val startKm = if (km <= station.routeKm) km else station.routeKm
        val socAtStation = if (km < station.routeKm - 0.05) predictedSocTo(km, soc, station.routeKm, station.detourKm) else soc
        val next = stations.firstOrNull { it.routeKm > station.routeKm + 0.08 }
        val targetKm = next?.routeKm ?: course.totalKm
        val targetName = next?.name ?: "종점"
        val afterSkip = (socAtStation - plan.internalConsumption(startKm.coerceAtLeast(station.routeKm), targetKm)).coerceIn(0.0, 100.0)
        return if (afterSkip >= hard) {
            "충전 생략 검토 · ${station.name}에서 충전하지 않으면 $targetName 도착 약 ${afterSkip.roundToInt()}% · 하드 리저브 +${(afterSkip - hard).roundToInt()}%"
        } else {
            "충전 생략 위험 · ${station.name}을 건너뛰면 $targetName 도착 약 ${afterSkip.roundToInt()}% · 하드 리저브보다 ${(hard - afterSkip).roundToInt()}% 부족"
        }
    }

    private fun renderTimeline() {
        val km0 = currentKm()
        var cursorKm = km0
        var cursorSoc = currentSoc()
        var cursorTime = simTimeMinutes
        val speed = currentSpeed()
        val lines = mutableListOf<String>()
        lines += "기준 ${formatClock(cursorTime)} · ${RideFormatter.one(km0)}km · ${cursorSoc.roundToInt()}% · ${String.format(Locale.US, "%.1f", speed)}km/h"

        val timelineStations = stations.filter { it.routeKm > km0 + 0.05 || kotlin.math.abs(it.routeKm - km0) <= 0.55 }
        for (station in timelineStations) {
            val atCurrentStation = kotlin.math.abs(station.routeKm - cursorKm) <= 0.55
            val travel = if (atCurrentStation) 0.0 else ((station.routeKm - cursorKm).coerceAtLeast(0.0) / speed) * 60.0
            cursorTime += travel
            if (!atCurrentStation) cursorSoc = predictedSocTo(cursorKm, cursorSoc, station.routeKm, station.detourKm)
            val skipped = skippedStationIds.contains(station.id)
            lines += "◆ ${RideFormatter.one(station.routeKm)}km · 도착 ${formatClock(cursorTime)} · ${station.name} · 예상 ${cursorSoc.roundToInt()}%"
            if (skipped) {
                lines += "   ↳ 충전 생략 시뮬레이션 · 바로 출발 ${formatClock(cursorTime)}"
            } else {
                val chargeMin = AvinoxChargeCurve.minutesBetween(cursorSoc, station.chargeToPct)
                cursorTime += chargeMin
                cursorSoc = maxOf(cursorSoc, station.chargeToPct).coerceAtMost(100.0)
                lines += "   ↳ ${station.chargeToPct.roundToInt()}% 충전 · ${AvinoxChargeCurve.minutesText(chargeMin)} · 예상 출발 ${formatClock(cursorTime)}"
            }
            cursorKm = maxOf(cursorKm, station.routeKm)
        }

        if (course.totalKm > cursorKm + 0.01) {
            cursorTime += ((course.totalKm - cursorKm) / speed) * 60.0
            cursorSoc = (cursorSoc - plan.internalConsumption(cursorKm, course.totalKm)).coerceIn(0.0, 100.0)
        }
        lines += "🏁 ${RideFormatter.one(course.totalKm)}km · 종점 ${formatClock(cursorTime)} · 예상 ${cursorSoc.roundToInt()}%"
        lines += "\n※ 시뮬레이터 시간축은 입력한 이동평균속도 + 등록 충전계획을 사용합니다. 실제 기록에는 저장되지 않습니다."
        tvTimeline.text = lines.joinToString("\n")
    }

    private fun toggleSkipForRelevantStation() {
        val st = stationAtOrNext(currentKm()) ?: return
        if (!skippedStationIds.add(st.id)) skippedStationIds.remove(st.id)
        recalc(false)
    }

    private fun jumpStation(direction: Int) {
        if (stations.isEmpty()) return
        val km = currentKm()
        val target = if (direction > 0) {
            stations.firstOrNull { it.routeKm > km + 0.15 } ?: stations.last()
        } else {
            stations.lastOrNull { it.routeKm < km - 0.15 } ?: stations.first()
        }
        seekKm.progress = (target.routeKm * 10.0).roundToInt().coerceIn(0, seekKm.max)
        recalc(false)
    }

    private fun searchEmergencyCandidates() {
        if (BuildConfig.KAKAO_REST_API_KEY.isBlank()) {
            Toast.makeText(this, "Kakao REST API 키가 빌드에 주입되지 않았습니다.", Toast.LENGTH_LONG).show()
            return
        }
        val anchor = course.pointAtKm(currentKm())
        val soc = currentSoc()
        val hard = AppSettings.hardReserve(this).toDouble()
        btnEmergencySearch.isEnabled = false
        btnEmergencySearch.text = "장수 GPX 가상 위치 주변 검색 중…"
        Thread {
            val result = runCatching {
                val client = KakaoEmergencyChargeClient(BuildConfig.KAKAO_REST_API_KEY)
                val all = linkedMapOf<String, KakaoPlaceCandidate>()
                replanReadOnlyStore.history().forEach { h ->
                    if (Geo.distanceMeters(anchor.lat, anchor.lon, h.lat, h.lon) <= 20_000.0) {
                        all[h.id] = KakaoPlaceCandidate(h.id, h.name, h.lat, h.lon, h.address, "", "과거 실제 충전 성공", "A", "과거 실제 충전 성공 ${h.successCount}회", Geo.distanceMeters(anchor.lat, anchor.lon, h.lat, h.lon))
                    }
                }
                stations.forEach { st ->
                    if (st.lat != 0.0 && st.lon != 0.0 && Geo.distanceMeters(anchor.lat, anchor.lon, st.lat, st.lon) <= 20_000.0) {
                        all[st.id] = KakaoPlaceCandidate(st.id, st.name, st.lat, st.lon, st.address, "", "등록 충전소", "A", "내가 등록한 충전소", Geo.distanceMeters(anchor.lat, anchor.lon, st.lat, st.lon))
                    }
                }
                client.searchAround(anchor.lat, anchor.lon).forEach { all.putIfAbsent(it.id, it) }
                all.values.sortedWith(compareBy<KakaoPlaceCandidate>({ it.confidence }, { it.straightDistanceM })).take(6).mapNotNull { place ->
                    runCatching {
                        val out = client.bicycleRoute(anchor.lat, anchor.lon, place.lat, place.lon)
                        val back = client.bicycleRoute(place.lat, place.lon, anchor.lat, anchor.lon)
                        val arrival = (soc - emergencyDetourUse(out.distanceKm)).coerceIn(0.0, 100.0)
                        EvaluatedEmergencyCandidate(place, out, back, arrival)
                    }.getOrNull()
                }.sortedWith(
                    compareByDescending<EvaluatedEmergencyCandidate> { if (it.predictedArrivalSoc >= hard) 1 else 0 }
                        .thenBy { it.place.confidence }
                        .thenBy { it.outbound.distanceKm }
                )
            }
            runOnUiThread {
                btnEmergencySearch.isEnabled = true
                btnEmergencySearch.text = "Kakao 비상 충전 후보 검색"
                result.onSuccess { showCandidatePicker(it, anchor.routeKm) }
                    .onFailure { e -> AlertDialog.Builder(this).setTitle("비상 충전 검색 실패").setMessage(e.message ?: "Kakao API/네트워크를 확인하세요.").setPositiveButton("확인", null).show() }
            }
        }.start()
    }

    private fun showCandidatePicker(items: List<EvaluatedEmergencyCandidate>, anchorKm: Double) {
        if (items.isEmpty()) {
            AlertDialog.Builder(this).setTitle("후보 없음").setMessage("이 가상 GPX 위치 주변에서 자전거 경로를 계산할 후보를 찾지 못했습니다.").setPositiveButton("확인", null).show()
            return
        }
        val hard = AppSettings.hardReserve(this)
        val labels = items.map { c ->
            val safe = if (c.predictedArrivalSoc >= hard) "도달 가능" else "위험"
            "${c.place.confidence} · $safe · ${c.place.name}\n편도 ${RideFormatter.one(c.outbound.distanceKm)}km · 도착 ${c.predictedArrivalSoc.roundToInt()}% · 왕복 ${RideFormatter.one(c.roundTripKm)}km\n${c.place.confidenceLabel}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("비상 충전 후보 · 가상 ${RideFormatter.one(anchorKm)}km")
            .setMessage("선택해도 실제 코스 진행/주행기록에는 저장되지 않습니다. 시뮬레이터 안에서만 이탈점을 고정합니다.")
            .setItems(labels) { _, which -> selectEmergencyCandidate(items[which], anchorKm) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun selectEmergencyCandidate(c: EvaluatedEmergencyCandidate, anchorKm: Double) {
        selectedEmergency = c
        emergencyAnchorKm = anchorKm
        emergencyPhase = SimEmergencyPhase.OUTBOUND
        emergencyTargetPct = calculateEmergencyTarget(c, anchorKm)
        panelEmergency.visibility = View.VISIBLE
        renderEmergencyPanel()
    }

    private fun calculateEmergencyTarget(c: EvaluatedEmergencyCandidate, anchorKm: Double): Double {
        val next = stations.firstOrNull { it.routeKm > anchorKm + 0.08 }
        val nextKm = next?.routeKm ?: course.totalKm
        val courseUse = plan.internalConsumption(anchorKm, nextKm)
        val returnUse = emergencyDetourUse(c.back.distanceKm)
        return ceil(AppSettings.finishTarget(this) + courseUse + returnUse).coerceIn(20.0, 100.0)
    }

    private fun renderEmergencyPanel() {
        val c = selectedEmergency
        val anchor = emergencyAnchorKm
        if (c == null || anchor == null || emergencyPhase == SimEmergencyPhase.NONE) {
            panelEmergency.visibility = View.GONE
            return
        }
        panelEmergency.visibility = View.VISIBLE
        val phaseText = when (emergencyPhase) {
            SimEmergencyPhase.OUTBOUND -> "OUTBOUND · 원래 이탈점 ${RideFormatter.one(anchor)}km 고정"
            SimEmergencyPhase.CHARGING -> "CHARGING · 비상 충전 중"
            SimEmergencyPhase.RETURN -> "RETURN · 동일 이탈점 ${RideFormatter.one(anchor)}km 복귀 중"
            else -> ""
        }
        tvEmergency.text = buildString {
            append("$phaseText\n${c.place.confidence}급 · ${c.place.name}\n")
            append("편도 ${RideFormatter.one(c.outbound.distanceKm)}km / ${c.outbound.minutes.roundToInt()}분 · 도착 예상 ${c.predictedArrivalSoc.roundToInt()}%\n")
            append("충전 목표 ${emergencyTargetPct.roundToInt()}% · 복귀 ${RideFormatter.one(c.back.distanceKm)}km / ${c.back.minutes.roundToInt()}분\n")
            append("충전 후 반드시 ${RideFormatter.one(anchor)}km 이탈점으로 돌아온 뒤 경기코스를 재개하는 시나리오입니다.")
        }
        btnEmergencyStep.text = when (emergencyPhase) {
            SimEmergencyPhase.OUTBOUND -> "1단계 · 충전소 도착 시뮬레이션"
            SimEmergencyPhase.CHARGING -> "2단계 · 충전 완료 시뮬레이션"
            SimEmergencyPhase.RETURN -> "3단계 · 동일 이탈점 복귀 완료"
            else -> "다음 단계"
        }
    }

    private fun advanceEmergencySimulation() {
        val c = selectedEmergency ?: return
        when (emergencyPhase) {
            SimEmergencyPhase.OUTBOUND -> {
                simTimeMinutes += c.outbound.minutes
                seekSoc.progress = c.predictedArrivalSoc.roundToInt().coerceIn(0, 100)
                etTime.setText(formatClock(simTimeMinutes))
                emergencyPhase = SimEmergencyPhase.CHARGING
            }
            SimEmergencyPhase.CHARGING -> {
                val from = currentSoc()
                val min = AvinoxChargeCurve.minutesBetween(from, emergencyTargetPct)
                simTimeMinutes += min
                seekSoc.progress = emergencyTargetPct.roundToInt().coerceIn(0, 100)
                etTime.setText(formatClock(simTimeMinutes))
                emergencyPhase = SimEmergencyPhase.RETURN
            }
            SimEmergencyPhase.RETURN -> {
                simTimeMinutes += c.back.minutes
                val afterReturnSoc = (currentSoc() - emergencyDetourUse(c.back.distanceKm)).coerceIn(0.0, 100.0)
                seekSoc.progress = afterReturnSoc.roundToInt().coerceIn(0, 100)
                etTime.setText(formatClock(simTimeMinutes))
                emergencyAnchorKm?.let { seekKm.progress = (it * 10.0).roundToInt().coerceIn(0, seekKm.max) }
                Toast.makeText(this, "동일 이탈점 복귀 완료 · 여기서 원래 GPX ETA를 다시 계산합니다.", Toast.LENGTH_LONG).show()
                selectedEmergency = null
                emergencyAnchorKm = null
                emergencyPhase = SimEmergencyPhase.NONE
            }
            else -> Unit
        }
        recalc(false)
    }

    private fun clearEmergency() {
        selectedEmergency = null
        emergencyAnchorKm = null
        emergencyPhase = SimEmergencyPhase.NONE
        panelEmergency.visibility = View.GONE
        recalc(false)
    }

    private fun resetSimulation() {
        skippedStationIds.clear()
        clearEmergency()
        seekKm.progress = 0
        seekSoc.progress = 100
        seekSpeed.progress = 24
        simTimeMinutes = currentMinutesOfDay()
        etTime.setText(formatClock(simTimeMinutes))
        recalc(false)
    }

    private fun predictedSocTo(fromKm: Double, fromSoc: Double, toKm: Double, detourKm: Double = 0.0): Double {
        if (toKm <= fromKm) return fromSoc.coerceIn(0.0, 100.0)
        val use = plan.internalConsumption(fromKm, toKm) + plannedDetourUse(detourKm)
        return (fromSoc - use).coerceIn(0.0, 100.0)
    }

    private fun plannedDetourUse(distanceKm: Double): Double {
        if (distanceKm <= 0.0 || course.totalKm <= 0.1) return 0.0
        return distanceKm * (plan.internalTotalUsePct() / course.totalKm)
    }

    private fun emergencyDetourUse(distanceKm: Double): Double {
        if (distanceKm <= 0.0 || course.totalKm <= 0.1) return 0.0
        return distanceKm * (plan.internalTotalUsePct() / course.totalKm) * 1.15
    }

    private fun currentMinutesOfDay(): Double {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60.0 + c.get(Calendar.MINUTE)
    }

    private fun parseClock(text: String?): Double? {
        val parts = text.orEmpty().trim().split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60.0 + m
    }

    private fun formatClock(minutes: Double): String {
        val total = ((minutes.roundToInt() % (24 * 60)) + 24 * 60) % (24 * 60)
        return String.format(Locale.KOREA, "%02d:%02d", total / 60, total % 60)
    }
}
