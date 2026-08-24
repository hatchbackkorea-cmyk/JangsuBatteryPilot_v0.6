package com.seungjae.jangsu280battery

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : Activity() {
    companion object {
        private const val REQ_LOCATION = 1001
        private const val REQ_NOTIFICATIONS = 1002
        private const val REQ_MICROPHONE = 1003
        private const val REQ_SPEECH = 1004
        private const val PREFS = "ride_state"
        private const val KEY_LAST_KM = "last_km"
        private const val KEY_VOICE = "voice_enabled"
        private const val KEY_VOICE_LEVEL = "voice_level"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_FINISH_TARGET = "finish_target"
    }

    private lateinit var course: CourseData
    private lateinit var basePlan: BatteryPlan
    private lateinit var actualStore: BatteryActualStore
    private lateinit var plan: AdaptiveBatteryPlan
    private lateinit var chargeStore: ChargingSessionStore
    private lateinit var rideSession: RideSessionStore

    private lateinit var tvGpsStatus: TextView
    private lateinit var tvCurrentKm: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvBatteryRange: TextView
    private lateinit var progressBattery: ProgressBar
    private lateinit var tvRiskStatus: TextView
    private lateinit var tvRiskDetail: TextView
    private lateinit var tvActualBattery: TextView
    private lateinit var tvActualDetail: TextView
    private lateinit var tvMicHint: TextView
    private lateinit var btnMicBattery: ImageButton
    private lateinit var btnUndoActual: Button
    private lateinit var tvNextCheckpoint: TextView
    private lateinit var tvNextCheckpointDetail: TextView
    private lateinit var tvEta: TextView
    private lateinit var tvFinishEta: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvElevationAhead: TextView
    private lateinit var tvTenKmBattery: TextView
    private lateinit var tvAssist: TextView
    private lateinit var tvNextClimb: TextView
    private lateinit var tvNextClimbDetail: TextView
    private lateinit var tvCourseStatus: TextView
    private lateinit var tvNextPoi: TextView
    private lateinit var tvChargeTimer: TextView
    private lateinit var tvChargeDetail: TextView
    private lateinit var btnChargeToggle: Button
    private lateinit var tvFinishTarget: TextView
    private lateinit var seekFinishTarget: SeekBar
    private lateinit var tvVersion: TextView
    private lateinit var profileView: ElevationProfileView
    private lateinit var switchVoice: Switch
    private lateinit var btnVoiceLevel: Button
    private lateinit var switchKeepScreen: Switch
    private lateinit var btnSpeakNow: Button
    private lateinit var btnRideReport: Button
    private lateinit var btnVersionInfo: Button
    private lateinit var switchTestMode: Switch
    private lateinit var tvTestKm: TextView
    private lateinit var seekTestKm: SeekBar
    private lateinit var btnResetProgress: Button

    private var latestRouteKm = 0.0
    private var latestOffCourseM = 0.0
    private var latestAccuracyM = -1f
    private var latestSpeedKmh = 0.0
    private var latestCourseElevation = 0.0
    private var testMode = false
    private var receiverRegistered = false
    private var speechPendingAfterPermission = false
    private var voiceLevel = VoiceLevel.NORMAL
    private var finishTargetPct = 15.0

    private val handler = Handler(Looper.getMainLooper())
    private val chargeTicker = object : Runnable {
        override fun run() {
            if (::chargeStore.isInitialized) renderChargeCard()
            handler.postDelayed(this, 1000L)
        }
    }

    private val rideReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != RideService.ACTION_UPDATE || testMode) return
            latestRouteKm = intent.getDoubleExtra(RideService.EXTRA_ROUTE_KM, latestRouteKm)
            latestOffCourseM = intent.getDoubleExtra(RideService.EXTRA_OFF_COURSE_M, latestOffCourseM)
            latestAccuracyM = intent.getFloatExtra(RideService.EXTRA_ACCURACY_M, latestAccuracyM)
            latestSpeedKmh = intent.getDoubleExtra(RideService.EXTRA_SPEED_KMH, latestSpeedKmh)
            latestCourseElevation = intent.getDoubleExtra(RideService.EXTRA_COURSE_ELEVATION, latestCourseElevation)
            renderAtKm(latestRouteKm, simulated = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        try {
            course = CourseData.load(this, R.raw.jangsu_stage1_battery)
            basePlan = BatteryPlan(course)
            actualStore = BatteryActualStore(this)
            plan = AdaptiveBatteryPlan(basePlan, actualStore)
            chargeStore = ChargingSessionStore(this)
            rideSession = RideSessionStore(this).also { it.ensureStarted() }
        } catch (e: Exception) {
            Toast.makeText(this, "GPX 읽기 실패: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        profileView.setCourse(course)
        tvVersion.text = "Jangsu Battery Pilot v${appVersionName()} · Adaptive Rally Copilot"

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        latestRouteKm = prefs.getFloat(KEY_LAST_KM, 0f).toDouble().coerceIn(0.0, course.totalKm)
        switchVoice.isChecked = prefs.getBoolean(KEY_VOICE, true)
        switchKeepScreen.isChecked = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false)
        voiceLevel = runCatching { VoiceLevel.valueOf(prefs.getString(KEY_VOICE_LEVEL, VoiceLevel.NORMAL.name) ?: VoiceLevel.NORMAL.name) }
            .getOrDefault(VoiceLevel.NORMAL)
        finishTargetPct = prefs.getFloat(KEY_FINISH_TARGET, 15f).toDouble().coerceIn(5.0, 30.0)
        applyKeepScreenOn(switchKeepScreen.isChecked)
        updateVoiceLevelButton()

        seekFinishTarget.max = 25
        seekFinishTarget.progress = (finishTargetPct - 5.0).roundToInt()
        updateFinishTargetLabel()

        seekTestKm.max = (course.totalKm * 10).roundToInt()
        seekTestKm.progress = (latestRouteKm * 10).roundToInt()
        tvTestKm.text = "테스트 위치 ${RideFormatter.one(latestRouteKm)} km"

        switchVoice.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_VOICE, checked).apply()
            if (!testMode) sendServiceAction(RideService.ACTION_SET_VOICE) { putExtra(RideService.EXTRA_VOICE_ENABLED, checked) }
        }

        btnVoiceLevel.setOnClickListener {
            voiceLevel = voiceLevel.next()
            prefs.edit().putString(KEY_VOICE_LEVEL, voiceLevel.name).apply()
            updateVoiceLevelButton()
            if (!testMode) sendServiceAction(RideService.ACTION_SET_VOICE_LEVEL) { putExtra(RideService.EXTRA_VOICE_LEVEL, voiceLevel.name) }
        }

        switchKeepScreen.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, checked).apply()
            applyKeepScreenOn(checked)
        }

        seekFinishTarget.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                finishTargetPct = (progress + 5).toDouble()
                prefs.edit().putFloat(KEY_FINISH_TARGET, finishTargetPct.toFloat()).apply()
                updateFinishTargetLabel()
                renderAtKm(latestRouteKm, simulated = testMode)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        btnSpeakNow.setOnClickListener { speakCurrentSummary() }
        btnMicBattery.setOnClickListener { requestVoiceCommand() }
        btnUndoActual.setOnClickListener { undoActual() }
        btnChargeToggle.setOnClickListener {
            if (chargeStore.state().active) stopCharging() else startCharging()
        }
        btnRideReport.setOnClickListener { showRideReport() }
        btnVersionInfo.setOnClickListener { showVersionInfo() }

        switchTestMode.setOnCheckedChangeListener { _, checked ->
            testMode = checked
            seekTestKm.isEnabled = checked
            if (checked) {
                stopRideService()
                tvGpsStatus.text = "테스트 모드 · 슬라이더로 코스 진행 확인"
                val km = seekTestKm.progress / 10.0
                latestRouteKm = km
                renderAtKm(km, simulated = true)
            } else {
                tvGpsStatus.text = "GPS 추적 서비스 시작 중…"
                ensurePermissionsAndStart()
            }
        }

        seekTestKm.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!testMode) return
                val km = progress / 10.0
                tvTestKm.text = "테스트 위치 ${RideFormatter.one(km)} km"
                latestRouteKm = km
                latestSpeedKmh = 17.0
                latestOffCourseM = 0.0
                latestAccuracyM = 5f
                latestCourseElevation = course.pointAtKm(km).ele
                renderAtKm(km, simulated = true)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        btnResetProgress.setOnClickListener {
            prefs.edit().putFloat(KEY_LAST_KM, 0f).apply()
            actualStore.clear()
            chargeStore.clearSession()
            rideSession.reset()
            latestRouteKm = 0.0
            latestSpeedKmh = 0.0
            seekTestKm.progress = 0
            if (!testMode) sendServiceAction(RideService.ACTION_RESET)
            renderAtKm(0.0, simulated = testMode)
            Toast.makeText(this, "새 주행으로 초기화했습니다.", Toast.LENGTH_SHORT).show()
        }

        renderAtKm(latestRouteKm, simulated = false)
        ensurePermissionsAndStart()
    }

    private fun bindViews() {
        tvGpsStatus = findViewById(R.id.tvGpsStatus)
        tvCurrentKm = findViewById(R.id.tvCurrentKm)
        tvBattery = findViewById(R.id.tvBattery)
        tvBatteryRange = findViewById(R.id.tvBatteryRange)
        progressBattery = findViewById(R.id.progressBattery)
        tvRiskStatus = findViewById(R.id.tvRiskStatus)
        tvRiskDetail = findViewById(R.id.tvRiskDetail)
        tvActualBattery = findViewById(R.id.tvActualBattery)
        tvActualDetail = findViewById(R.id.tvActualDetail)
        tvMicHint = findViewById(R.id.tvMicHint)
        btnMicBattery = findViewById(R.id.btnMicBattery)
        btnUndoActual = findViewById(R.id.btnUndoActual)
        tvNextCheckpoint = findViewById(R.id.tvNextCheckpoint)
        tvNextCheckpointDetail = findViewById(R.id.tvNextCheckpointDetail)
        tvEta = findViewById(R.id.tvEta)
        tvFinishEta = findViewById(R.id.tvFinishEta)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvElevationAhead = findViewById(R.id.tvElevationAhead)
        tvTenKmBattery = findViewById(R.id.tvTenKmBattery)
        tvAssist = findViewById(R.id.tvAssist)
        tvNextClimb = findViewById(R.id.tvNextClimb)
        tvNextClimbDetail = findViewById(R.id.tvNextClimbDetail)
        tvCourseStatus = findViewById(R.id.tvCourseStatus)
        tvNextPoi = findViewById(R.id.tvNextPoi)
        tvChargeTimer = findViewById(R.id.tvChargeTimer)
        tvChargeDetail = findViewById(R.id.tvChargeDetail)
        btnChargeToggle = findViewById(R.id.btnChargeToggle)
        tvFinishTarget = findViewById(R.id.tvFinishTarget)
        seekFinishTarget = findViewById(R.id.seekFinishTarget)
        tvVersion = findViewById(R.id.tvVersion)
        profileView = findViewById(R.id.profileView)
        switchVoice = findViewById(R.id.switchVoice)
        btnVoiceLevel = findViewById(R.id.btnVoiceLevel)
        switchKeepScreen = findViewById(R.id.switchKeepScreen)
        btnSpeakNow = findViewById(R.id.btnSpeakNow)
        btnRideReport = findViewById(R.id.btnRideReport)
        btnVersionInfo = findViewById(R.id.btnVersionInfo)
        switchTestMode = findViewById(R.id.switchTestMode)
        tvTestKm = findViewById(R.id.tvTestKm)
        seekTestKm = findViewById(R.id.seekTestKm)
        btnResetProgress = findViewById(R.id.btnResetProgress)
    }

    private fun requestVoiceCommand() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            speechPendingAfterPermission = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MICROPHONE)
            return
        }
        launchVoiceCommand()
    }

    private fun launchVoiceCommand() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "자연스럽게 말하세요 · 예: 지금 배터리 48프로야 · 보급소까지 얼마나 남았어?")
        }
        tvMicHint.text = "🎤 듣는 중… 평소 말투로 자연스럽게 말씀하세요"
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_SPEECH)
        } catch (_: ActivityNotFoundException) {
            tvMicHint.text = "음성 인식 앱을 찾지 못했습니다."
            Toast.makeText(this, "이 휴대폰에서 음성 인식을 사용할 수 없습니다.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Android, retained for minSdk 26 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SPEECH) return
        if (resultCode != RESULT_OK) {
            tvMicHint.text = "큰 마이크를 누르고 자연스럽게 말하세요 · 예: ‘지금 48프로야’"
            return
        }
        val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty()
        val parsed = results.map { VoiceCommandParser.parse(it) to it }.firstOrNull { it.first !is VoiceCommand.Unknown }
        if (parsed == null) {
            tvMicHint.text = "명령을 못 알아들었습니다 · 다시 말해주세요"
            Toast.makeText(this, "음성 명령을 인식하지 못했습니다.", Toast.LENGTH_LONG).show()
            return
        }
        handleVoiceCommand(parsed.first, parsed.second)
    }

    private fun handleVoiceCommand(command: VoiceCommand, heardText: String) {
        when (command) {
            is VoiceCommand.Battery -> saveActualBattery(command.percent, heardText, command.forcePostCharge)
            is VoiceCommand.FinishTarget -> {
                finishTargetPct = command.percent.toDouble().coerceIn(5.0, 30.0)
                seekFinishTarget.progress = (finishTargetPct - 5).roundToInt()
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat(KEY_FINISH_TARGET, finishTargetPct.toFloat()).apply()
                updateFinishTargetLabel()
                renderAtKm(latestRouteKm, simulated = testMode)
                speakText("종점 목표 잔량을 ${finishTargetPct.roundToInt()}퍼센트로 설정했습니다.")
            }
            VoiceCommand.Repeat, VoiceCommand.CurrentStatus -> speakCurrentSummary()
            VoiceCommand.NextCheckpoint -> speakNextCheckpoint()
            VoiceCommand.FinishInfo -> speakFinishInfo()
            VoiceCommand.RemainingOverview -> speakRemainingOverview()
            VoiceCommand.NextClimb -> speakNextClimb()
            VoiceCommand.LocationInfo -> speakLocationInfo()
            is VoiceCommand.SetVoiceLevel -> setVoiceLevelFromCommand(command.level)
            VoiceCommand.ChargeStart -> startCharging()
            VoiceCommand.ChargeStop -> stopCharging()
            VoiceCommand.UndoActual -> undoActual()
            VoiceCommand.Help -> speakVoiceHelp()
            VoiceCommand.Unknown -> Unit
        }
        tvMicHint.text = "인식: ‘$heardText’"
    }

    private fun saveActualBattery(percent: Int, heardText: String, forcePostCharge: Boolean = false) {
        val km = latestRouteKm.coerceIn(0.0, course.totalKm)
        val kind = plan.classifyInput(km, percent.toDouble(), forcePostCharge)
        actualStore.save(percent.toDouble(), km, kind)
        if (chargeStore.state().active) {
            val state = chargeStore.observe(percent.toDouble())
            if (percent >= state.targetPct - 0.5) chargeStore.stop(percent.toDouble())
        }
        renderAtKm(km, simulated = testMode)

        val kindText = when (kind) {
            ActualEntryKind.ARRIVAL -> "도착 잔량"
            ActualEntryKind.POST_CHARGE -> "충전 후 잔량"
            ActualEntryKind.RIDING -> "주행 중 잔량"
        }
        tvMicHint.text = "인식: ‘$heardText’ · ${kindText}로 저장"
        Toast.makeText(this, "${RideFormatter.one(km)}km · 실제 $percent% 저장", Toast.LENGTH_LONG).show()
        val reserve = plan.reserveStatus(km, finishTargetPct)
        val message = "배터리 ${percent}퍼센트로 반영했습니다. ${reserve.targetName} 예상 ${reserve.predictedPct.roundToInt()}퍼센트, 상태 ${reserve.label}."
        speakText(message)
    }

    private fun undoActual() {
        val removed = actualStore.undoLast()
        if (removed == null) Toast.makeText(this, "취소할 실제 배터리 입력이 없습니다.", Toast.LENGTH_SHORT).show()
        else {
            renderAtKm(latestRouteKm, simulated = testMode)
            Toast.makeText(this, "마지막 실제 배터리 입력을 취소했습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCharging() {
        val cp = plan.checkpointAt(latestRouteKm, 0.45)
        val target = cp?.chargeToPct
        if (target == null) {
            Toast.makeText(this, "현재 위치는 계획 충전 지점이 아닙니다.", Toast.LENGTH_LONG).show()
            speakText("현재 위치는 계획 충전 지점이 아닙니다.")
            return
        }
        val actual = actualStore.latest()?.takeIf { abs(it.routeKm - latestRouteKm) <= 0.6 }?.percent
        val startPct = actual ?: plan.estimate(latestRouteKm).percent
        chargeStore.start(latestRouteKm, startPct, target)
        renderChargeCard()
        speakText("충전을 시작했습니다. 현재 약 ${startPct.roundToInt()}퍼센트, 목표 ${target.roundToInt()}퍼센트입니다.")
    }

    private fun stopCharging() {
        val s = chargeStore.state()
        if (!s.active) return
        chargeStore.stop()
        renderChargeCard()
        speakText("충전 타이머를 종료했습니다.")
    }

    private fun renderChargeCard() {
        val s = chargeStore.state()
        val cp = if (::plan.isInitialized) plan.checkpointAt(latestRouteKm, 0.45) else null
        if (!s.active) {
            tvChargeTimer.text = "충전 대기"
            tvChargeDetail.text = cp?.chargeToPct?.let { "${cp.name} · 목표 ${it.roundToInt()}% · 도착하면 충전 시작" }
                ?: "계획 충전 지점에서 사용할 수 있습니다."
            btnChargeToggle.text = "충전 시작"
            btnChargeToggle.isEnabled = cp?.chargeToPct != null || testMode
            return
        }
        val elapsedSec = s.elapsedMs() / 1000L
        val mm = elapsedSec / 60
        val ss = elapsedSec % 60
        tvChargeTimer.text = String.format(Locale.US, "%02d:%02d · %.0f%% → %.0f%%", mm, ss, s.lastPct, s.targetPct)
        val rate = s.effectiveRate()
        val remain = s.remainingMinutes()
        tvChargeDetail.text = if (rate != null && remain != null) {
            "측정 충전속도 ${String.format(Locale.US, "%.2f", rate)}%/분 · 목표까지 약 ${remain}분"
        } else {
            "충전속도 학습 중 · 충전 중간에 마이크로 실제 배터리를 한 번 더 말하면 ETA 계산"
        }
        btnChargeToggle.text = "충전 종료"
        btnChargeToggle.isEnabled = true
    }

    private fun speakCurrentSummary() {
        val km = latestRouteKm
        val battery = plan.estimate(km)
        val cp = plan.currentOrNextCheckpoint(km)
        val stats = course.elevationAhead(km, 10.0)
        val reserve = plan.reserveStatus(km, finishTargetPct)
        val cpText = cp?.let { " ${it.name}까지 ${RideFormatter.one((it.km - km).coerceAtLeast(0.0))}킬로미터." }.orEmpty()
        speakText("현재 ${RideFormatter.one(km)}킬로미터. 예상 배터리 ${battery.percent.roundToInt()}퍼센트. 상태 ${reserve.label}. ${reserve.targetName} 예상 ${reserve.predictedPct.roundToInt()}퍼센트. 앞으로 10킬로미터 상승 ${stats.ascentM.roundToInt()}미터.$cpText")
    }

    private fun speakNextCheckpoint() {
        val cp = plan.currentOrNextCheckpoint(latestRouteKm) ?: return speakText("스테이지1 종점에 도착했습니다.")
        val remain = (cp.km - latestRouteKm).coerceAtLeast(0.0)
        val predicted = plan.forecast(latestRouteKm, cp.km).percent
        speakText("${cp.name}까지 ${RideFormatter.one(remain)}킬로미터. 예상 도착 배터리 ${predicted.roundToInt()}퍼센트입니다.")
    }

    private fun speakFinishInfo() {
        val remain = (course.totalKm - latestRouteKm).coerceAtLeast(0.0)
        val predicted = plan.forecast(latestRouteKm, course.totalKm).percent
        speakText("종점까지 ${RideFormatter.one(remain)}킬로미터. 현재 기준 종점 예상 배터리 ${predicted.roundToInt()}퍼센트. 목표 ${finishTargetPct.roundToInt()}퍼센트입니다.")
    }


    private fun speakRemainingOverview() {
        val finishRemain = (course.totalKm - latestRouteKm).coerceAtLeast(0.0)
        val cp = plan.currentOrNextCheckpoint(latestRouteKm)
        val cpText = cp?.let {
            val remain = (it.km - latestRouteKm).coerceAtLeast(0.0)
            val predicted = plan.forecast(latestRouteKm, it.km).percent.roundToInt()
            "다음 ${it.name}까지 ${RideFormatter.one(remain)}킬로미터, 예상 배터리 ${predicted}퍼센트. "
        }.orEmpty()
        val finishPred = plan.forecast(latestRouteKm, course.totalKm).percent.roundToInt()
        speakText("$cpText 종점까지 ${RideFormatter.one(finishRemain)}킬로미터, 종점 예상 배터리 ${finishPred}퍼센트입니다.")
    }

    private fun speakNextClimb() {
        val climb = course.nextMajorClimb(latestRouteKm)
        if (climb == null) {
            speakText("앞 22킬로미터 안에는 큰 연속 업힐이 없습니다.")
            return
        }
        val remain = (climb.startKm - latestRouteKm).coerceAtLeast(0.0)
        val whenText = if (remain <= 0.2) "현재 주요 업힐입니다." else "약 ${RideFormatter.one(remain)}킬로미터 후 주요 업힐입니다."
        speakText("$whenText 길이 ${RideFormatter.one(climb.distanceKm)}킬로미터, 상승 약 ${climb.ascentM.roundToInt()}미터, 평균 경사 ${String.format(Locale.US, "%.1f", climb.averageGradePct)}퍼센트입니다.")
    }

    private fun speakLocationInfo() {
        val poi = course.nextPoi(latestRouteKm)
        val poiText = poi?.let { " 다음 포인트 ${it.name}까지 ${RideFormatter.one((it.routeKm - latestRouteKm).coerceAtLeast(0.0))}킬로미터입니다." }.orEmpty()
        speakText("현재 코스 ${RideFormatter.one(latestRouteKm)}킬로미터 지점, 고도 약 ${latestCourseElevation.roundToInt()}미터입니다.$poiText")
    }

    private fun setVoiceLevelFromCommand(requested: RequestedVoiceLevel) {
        voiceLevel = when (requested) {
            RequestedVoiceLevel.QUIET -> VoiceLevel.QUIET
            RequestedVoiceLevel.NORMAL -> VoiceLevel.NORMAL
            RequestedVoiceLevel.CHATTY -> VoiceLevel.CHATTY
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_VOICE_LEVEL, voiceLevel.name).apply()
        updateVoiceLevelButton()
        if (!testMode) sendServiceAction(RideService.ACTION_SET_VOICE_LEVEL) { putExtra(RideService.EXTRA_VOICE_LEVEL, voiceLevel.name) }
        speakText("음성 안내를 ${voiceLevel.label} 모드로 변경했습니다.")
    }

    private fun speakVoiceHelp() {
        speakText("자연스럽게 말씀하시면 됩니다. 예를 들면 지금 배터리 48프로야, 70까지 충전했어, 보급소까지 얼마나 남았어, 이대로 가도 돼, 앞에 힘든 업힐 있어, 종점까지 얼마나 남았어처럼 말해보세요.")
    }

    private fun speakText(text: String) {
        if (testMode) Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        else sendServiceAction(RideService.ACTION_SPEAK_TEXT) { putExtra(RideService.EXTRA_SPEAK_TEXT, text) }
    }

    private fun ensurePermissionsAndStart() {
        if (testMode) return
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
            return
        }
        startRideService()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_LOCATION -> if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) ensurePermissionsAndStart() else tvGpsStatus.text = "위치 권한이 필요합니다 · 테스트 모드는 사용 가능"
            REQ_NOTIFICATIONS -> startRideService()
            REQ_MICROPHONE -> {
                val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
                if (granted && speechPendingAfterPermission) launchVoiceCommand() else Toast.makeText(this, "음성 입력을 사용하려면 마이크 권한이 필요합니다.", Toast.LENGTH_LONG).show()
                speechPendingAfterPermission = false
            }
        }
    }

    private fun startRideService() {
        val intent = Intent(this, RideService::class.java).apply { action = RideService.ACTION_START }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        sendServiceAction(RideService.ACTION_SET_VOICE_LEVEL) { putExtra(RideService.EXTRA_VOICE_LEVEL, voiceLevel.name) }
        tvGpsStatus.text = "GPS 추적 중 · 화면을 꺼도 음성 안내 유지"
    }

    private fun stopRideService() { sendServiceAction(RideService.ACTION_STOP) }

    private fun sendServiceAction(action: String, block: Intent.() -> Unit = {}) {
        if (action != RideService.ACTION_STOP) {
            val hasLocation = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!hasLocation) return
        }
        val intent = Intent(this, RideService::class.java).apply { this.action = action; block() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && action != RideService.ACTION_STOP) startForegroundService(intent) else startService(intent)
    }

    private fun renderAtKm(kmValue: Double, simulated: Boolean) {
        val km = kmValue.coerceIn(0.0, course.totalKm)
        latestRouteKm = km
        val point = course.pointAtKm(km)
        val battery = plan.estimate(km)
        val range = plan.confidenceRange(km)
        val cp = plan.currentOrNextCheckpoint(km)
        val poi = course.nextPoi(km)
        val stats10 = course.elevationAhead(km, 10.0)
        val battery10 = plan.forecast(km, (km + 10.0).coerceAtMost(course.totalKm))
        val actualStatus = plan.latestStatus(km)
        val reserve = plan.reserveStatus(km, finishTargetPct)
        val climb = course.nextMajorClimb(km)

        tvCurrentKm.text = "${RideFormatter.one(km)} km"
        val pct = battery.percent.roundToInt().coerceIn(0, 100)
        tvBattery.text = "$pct%"
        tvBattery.setTextColor(batteryColor(battery.percent))
        progressBattery.progress = pct
        progressBattery.progressTintList = android.content.res.ColorStateList.valueOf(batteryColor(battery.percent))
        tvBatteryRange.text = if (battery.calibrated) "실제값 소비율 보정 · 예상 범위 ${range.start.roundToInt()}~${range.endInclusive.roundToInt()}%" else "GPX 계획값 · 참고 범위 ${range.start.roundToInt()}~${range.endInclusive.roundToInt()}%"

        tvRiskStatus.text = reserve.label
        tvRiskStatus.setTextColor(when (reserve.label) { "여유" -> getColor(R.color.good); "주의" -> getColor(R.color.warn); else -> getColor(R.color.danger) })
        val diffAbs = abs(reserve.differencePct).roundToInt()
        val differenceText = if (reserve.differencePct >= 0) "목표보다 ${diffAbs}% 여유" else "목표보다 ${diffAbs}% 부족 · 앞으로 ${diffAbs}% 절약 필요"
        tvRiskDetail.text = "${reserve.targetName} 예상 ${reserve.predictedPct.roundToInt()}% / 목표 ${reserve.targetPct.roundToInt()}% · $differenceText · 소비계수 ${String.format(Locale.US, "%.2f", reserve.consumptionFactor)}x"

        val latestActual = actualStatus?.entry
        if (latestActual == null) {
            tvActualBattery.text = "실제값 없음"
            tvActualBattery.setTextColor(getColor(R.color.text_secondary))
            tvActualDetail.text = "마이크를 눌러 실제 배터리 %를 말하면 소비율을 학습해 이후 예측을 다시 계산합니다."
            btnUndoActual.isEnabled = false
        } else {
            tvActualBattery.text = "실제 ${latestActual.percent.roundToInt()}%"
            tvActualBattery.setTextColor(batteryColor(latestActual.percent))
            val delta = actualStatus.delta.roundToInt()
            val deltaText = if (delta >= 0) "+$delta" else "$delta"
            val phase = when (latestActual.kind) { ActualEntryKind.ARRIVAL -> "충전 전 도착값"; ActualEntryKind.POST_CHARGE -> "충전 후 기준값"; ActualEntryKind.RIDING -> "주행 중 기준값" }
            val activeText = if (actualStatus.activeForCurrentSegment) "현재 예측 반영" else "보정 종료"
            tvActualDetail.text = "${RideFormatter.one(latestActual.routeKm)}km · 계획 대비 $deltaText% · $phase · $activeText · 소비 ${String.format(Locale.US, "%.2f", actualStatus.consumptionFactor)}x · ${timeText(latestActual.timestampMs)}"
            btnUndoActual.isEnabled = true
        }

        val remainFinish = (course.totalKm - km).coerceAtLeast(0.0)
        tvSpeed.text = if (latestSpeedKmh >= 2.0) "이동 평균 ${RideFormatter.one(latestSpeedKmh)} km/h" else "이동 속도 계산 중"
        tvFinishEta.text = "종점 ${RideFormatter.one(remainFinish)}km · ${RideFormatter.etaClock(remainFinish, latestSpeedKmh)}"

        if (cp != null) {
            val remain = (cp.km - km).coerceAtLeast(0.0)
            val atCurrent = abs(cp.km - km) <= 0.15
            val predictedArrival = plan.forecast(km, cp.km).percent.roundToInt()
            val currentPostCharge = atCurrent && latestActual?.kind == ActualEntryKind.POST_CHARGE && abs(latestActual.routeKm - cp.km) <= 0.35
            tvNextCheckpoint.text = if (atCurrent) "현재 · ${cp.name}" else cp.name
            tvNextCheckpointDetail.text = if (cp.chargeToPct != null) {
                when {
                    currentPostCharge -> "충전 완료 실제 ${latestActual!!.percent.roundToInt()}% · 다음 구간 기준 저장됨"
                    atCurrent -> "현재 예상 $predictedArrival% → ${cp.chargeToPct.roundToInt()}%까지 충전"
                    else -> "${RideFormatter.one(remain)}km 남음 · 도착 예상 $predictedArrival% · ${cp.chargeToPct.roundToInt()}% 충전"
                }
            } else "${RideFormatter.one(remain)}km 남음 · 종점 예상 $predictedArrival%"
            tvEta.text = "예상 도착 ${RideFormatter.etaClock(remain, latestSpeedKmh)} · ${RideFormatter.duration(remain, latestSpeedKmh)}"
        } else {
            tvNextCheckpoint.text = "스테이지1 완료"; tvNextCheckpointDetail.text = "종점 도착"; tvEta.text = "완료"
        }

        tvElevationAhead.text = "앞 10km  ▲${stats10.ascentM.roundToInt()}m   ▼${stats10.descentM.roundToInt()}m"
        tvTenKmBattery.text = if (plan.hasChargeBetween(km, km + 10.0)) "10km 안에 충전 지점 있음 · 이후 계획 기준 리셋" else "10km 후 예상 ${battery10.percent.roundToInt()}%${if (battery10.calibrated) " · 실제 소비율 반영" else ""}"

        tvAssist.text = when {
            reserve.label == "위험" -> "⚠ 목표 달성을 위해 약 ${(-reserve.differencePct).coerceAtLeast(0.0).roundToInt()}% 절약 필요 · 보조 강도 한 단계 낮추기 권장"
            reserve.label == "주의" -> "배터리 목표선 근처 · 긴 업힐에서 보조 강도 절약"
            else -> plan.assistText(km, battery, stats10)
        }
        tvAssist.setTextColor(when { reserve.label == "위험" -> getColor(R.color.danger); reserve.label == "주의" -> getColor(R.color.warn); stats10.ascentM >= 600.0 -> getColor(R.color.orange); else -> getColor(R.color.good) })

        if (climb == null) {
            tvNextClimb.text = "주요 업힐 없음"
            tvNextClimbDetail.text = "앞 22km에서 큰 연속 업힐이 감지되지 않았습니다."
        } else {
            val remain = (climb.startKm - km).coerceAtLeast(0.0)
            tvNextClimb.text = if (remain <= 0.2) "현재 주요 업힐" else "${RideFormatter.one(remain)}km 후 주요 업힐"
            tvNextClimbDetail.text = "길이 ${RideFormatter.one(climb.distanceKm)}km · 상승 +${climb.ascentM.roundToInt()}m · 평균 ${String.format(Locale.US, "%.1f", climb.averageGradePct)}%"
        }

        tvNextPoi.text = poi?.let { "다음 포인트 · ${it.name} · ${RideFormatter.one((it.routeKm - km).coerceAtLeast(0.0))}km" } ?: "다음 포인트 · 종점"
        val accText = if (latestAccuracyM >= 0) "±${latestAccuracyM.roundToInt()}m" else "-"
        val offText = if (simulated) "테스트" else "코스 이탈 ${latestOffCourseM.roundToInt()}m"
        val ele = if (latestCourseElevation > 0) latestCourseElevation else point.ele
        tvCourseStatus.text = "고도 ${ele.roundToInt()}m · GPS $accText · $offText"

        if (!simulated) {
            tvGpsStatus.text = when { latestOffCourseM >= 150 -> "⚠ 코스에서 ${latestOffCourseM.roundToInt()}m 벗어남 · 방향 확인"; latestAccuracyM > 50 -> "GPS 정확도 낮음 ${latestAccuracyM.roundToInt()}m"; else -> "GPS 추적 정상 · 화면 꺼짐 상태에서도 안내 유지" }
            tvGpsStatus.setTextColor(if (latestOffCourseM >= 150) getColor(R.color.danger) else getColor(R.color.text_secondary))
        }
        profileView.setCurrentKm(km)
        renderChargeCard()
    }

    private fun updateVoiceLevelButton() { btnVoiceLevel.text = "안내 수준: ${voiceLevel.label}" }
    private fun updateFinishTargetLabel() { tvFinishTarget.text = "종점 목표 잔량 ${finishTargetPct.roundToInt()}%" }

    private fun showRideReport() {
        AlertDialog.Builder(this)
            .setTitle("주행 리포트")
            .setMessage(rideSession.summaryText(actualStore))
            .setPositiveButton("확인", null)
            .show()
    }

    private fun showVersionInfo() {
        val msg = """
            현재 버전: ${appVersionName()} (versionCode 7)

            v0.6.0 주요 변경
            • 오프라인 자연어 Voice Copilot
            • “지금 48프로야”, “70까지 채웠어” 같은 일상 표현 인식
            • 보급소/종점/업힐/현재상태 자연어 질문
            • 음성으로 조용/기본/수다쟁이 모드 변경
            • 고유어 숫자(마흔여덟, 일흔 등) 인식 강화
            • 숫자 거리 질문을 배터리 입력으로 오인하지 않도록 문맥 판정
            • v0.5.0 Adaptive Rally Copilot 기능 전체 포함
        """.trimIndent()
        AlertDialog.Builder(this).setTitle("Jangsu Battery Pilot").setMessage(msg).setPositiveButton("확인", null).show()
    }

    private fun batteryColor(percent: Double): Int = when { percent >= 60.0 -> getColor(R.color.good); percent >= 40.0 -> getColor(R.color.warn); percent >= 25.0 -> getColor(R.color.orange); else -> getColor(R.color.danger) }
    private fun applyKeepScreenOn(enabled: Boolean) { if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    private fun appVersionName(): String = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0.6.0"
    } catch (_: Exception) {
        "0.6.0"
    }
    private fun timeText(timestampMs: Long): String = if (timestampMs <= 0) "" else SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(timestampMs))

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            val filter = IntentFilter(RideService.ACTION_UPDATE)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(rideReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(rideReceiver, filter)
            }
            receiverRegistered = true
        }
        handler.removeCallbacks(chargeTicker)
        handler.post(chargeTicker)
        renderAtKm(latestRouteKm, simulated = testMode)
    }

    override fun onStop() {
        if (receiverRegistered) { try { unregisterReceiver(rideReceiver) } catch (_: Exception) {}; receiverRegistered = false }
        handler.removeCallbacks(chargeTicker)
        super.onStop()
    }
}
