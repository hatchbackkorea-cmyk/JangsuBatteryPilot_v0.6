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
import android.speech.RecognizerIntent
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
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
        private const val REQ_EXPORT = 1006
    }

    private lateinit var courseRepo: CourseRepository
    private lateinit var courseMeta: CourseMeta
    private lateinit var course: CourseData
    private lateinit var learningStore: BatteryLearningStore
    private lateinit var logManager: RideLogManager
    private lateinit var basePlan: BatteryPlan
    private lateinit var actualStore: BatteryActualStore
    private lateinit var plan: AdaptiveBatteryPlan

    private lateinit var tvCourseName: TextView
    private lateinit var tvCourseSummary: TextView
    private lateinit var btnCourseMenu: Button
    private lateinit var btnSettingsMenu: Button
    private lateinit var btnRideToggle: Button
    private lateinit var btnExportLast: Button
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
    private lateinit var tvVersion: TextView
    private lateinit var profileView: ElevationProfileView
    private lateinit var btnSpeakNow: Button
    private lateinit var btnRideReport: Button

    private var latestRouteKm = 0.0
    private var latestOffCourseM = 0.0
    private var latestAccuracyM = -1f
    private var latestSpeedKmh = 0.0
    private var latestCourseElevation = 0.0
    private var testMode = false
    private var receiverRegistered = false
    private var speechPendingAfterPermission = false
    private var finishTargetPct = AppSettings.DEFAULT_FINISH_TARGET.toDouble()
    private var pendingExportFile: File? = null
    private var loadedCourseId: String? = null

    private val rideReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != RideService.ACTION_UPDATE || testMode) return
            latestRouteKm = intent.getDoubleExtra(RideService.EXTRA_ROUTE_KM, latestRouteKm)
            latestOffCourseM = intent.getDoubleExtra(RideService.EXTRA_OFF_COURSE_M, latestOffCourseM)
            latestAccuracyM = intent.getFloatExtra(RideService.EXTRA_ACCURACY_M, latestAccuracyM)
            latestSpeedKmh = intent.getDoubleExtra(RideService.EXTRA_SPEED_KMH, latestSpeedKmh)
            latestCourseElevation = intent.getDoubleExtra(RideService.EXTRA_COURSE_ELEVATION, latestCourseElevation)
            renderAtKm(latestRouteKm, false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        courseRepo = CourseRepository(this)
        learningStore = BatteryLearningStore(this)
        logManager = RideLogManager(this)
        actualStore = BatteryActualStore(this)

        if (!logManager.isActive()) actualStore.clear()

        // 앱이 재시작된 경우 진행 중 세션의 코스를 우선 복구.
        logManager.activeRide()?.let { active -> runCatching { courseRepo.setActive(active.courseId) } }
        if (!loadSelectedCourse(resetProgress = false)) return
        applySettings()

        btnCourseMenu.setOnClickListener { startActivity(Intent(this, CourseActivity::class.java)) }
        btnSettingsMenu.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        btnRideToggle.setOnClickListener { if (logManager.isActive()) confirmEndRide() else startRide() }
        btnExportLast.setOnClickListener { exportLastLog() }
        btnSpeakNow.setOnClickListener { speakCurrentSummary() }
        btnMicBattery.setOnClickListener { requestVoiceCommand() }
        btnUndoActual.setOnClickListener { undoActual() }
        btnRideReport.setOnClickListener { showRideReport() }

        renderRideState()
        renderCurrentMode()
    }

    private fun bindViews() {
        tvCourseName = findViewById(R.id.tvCourseName)
        tvCourseSummary = findViewById(R.id.tvCourseSummary)
        btnCourseMenu = findViewById(R.id.btnCourseMenu)
        btnSettingsMenu = findViewById(R.id.btnSettingsMenu)
        btnRideToggle = findViewById(R.id.btnRideToggle)
        btnExportLast = findViewById(R.id.btnExportLast)
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
        tvVersion = findViewById(R.id.tvVersion)
        profileView = findViewById(R.id.profileView)
        btnSpeakNow = findViewById(R.id.btnSpeakNow)
        btnRideReport = findViewById(R.id.btnRideReport)
    }

    private fun loadSelectedCourse(resetProgress: Boolean): Boolean {
        return try {
            courseMeta = courseRepo.activeMeta()
            course = courseRepo.loadCourse(courseMeta.id)
            loadedCourseId = courseMeta.id
            basePlan = BatteryPlan(course, learningStore)
            plan = AdaptiveBatteryPlan(basePlan, actualStore)
            profileView.setCourse(course)
            val prefs = AppSettings.prefs(this)
            if (resetProgress) {
                prefs.edit().putFloat(AppSettings.KEY_LAST_KM, 0f).putFloat(AppSettings.KEY_TEST_KM, 0f).apply()
            }
            latestRouteKm = prefs.getFloat(AppSettings.KEY_LAST_KM, 0f).toDouble().coerceIn(0.0, course.totalKm)
            latestCourseElevation = course.pointAtKm(latestRouteKm).ele
            tvVersion.text = "GPX Battery Copilot v${appVersionName()} · GPX Ride Copilot"
            updateCourseHeader()
            true
        } catch (e: Exception) {
            Toast.makeText(this, "GPX 읽기 실패: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun applySettings() {
        finishTargetPct = AppSettings.finishTarget(this).coerceIn(1.0, 99.0)
        testMode = AppSettings.testMode(this)
        applyKeepScreenOn(AppSettings.keepScreenOn(this))
        updateCourseHeader()

        if (logManager.isActive() && !testMode) {
            sendVoiceSettingsToService()
        }
    }

    private fun renderCurrentMode() {
        if (testMode) {
            stopRideService()
            latestRouteKm = AppSettings.testKm(this).coerceIn(0.0, course.totalKm)
            latestSpeedKmh = 17.0
            latestOffCourseM = 0.0
            latestAccuracyM = 5f
            latestCourseElevation = course.pointAtKm(latestRouteKm).ele
            tvGpsStatus.text = "테스트 모드 · 설정 메뉴의 테스트 위치 사용"
            renderAtKm(latestRouteKm, true)
        } else {
            val stored = AppSettings.prefs(this).getFloat(AppSettings.KEY_LAST_KM, latestRouteKm.toFloat()).toDouble()
            latestRouteKm = stored.coerceIn(0.0, course.totalKm)
            renderAtKm(latestRouteKm, false)
            if (logManager.isActive()) ensurePermissionsAndStart()
        }
    }

    private fun updateCourseHeader() {
        if (!::plan.isInitialized) return
        tvCourseName.text = courseMeta.name
        val elev = if (course.hasElevation) "▲${course.totalAscentM.roundToInt()}m · ▼${course.totalDescentM.roundToInt()}m" else "고도 데이터 없음"
        val predictedUse = plan.predictedTotalUsePct()
        val chargeKm = plan.recommendedChargeKm(finishTargetPct)
        val batteryLine = when {
            plan.isLegacyPlan() -> "장수 전용 충전 계획 유지"
            predictedUse <= 100.0 - finishTargetPct -> "무충전 종점 예상 ${(100.0 - predictedUse).coerceAtLeast(0.0).roundToInt()}%"
            chargeKm != null -> "예상 사용 ${predictedUse.roundToInt()}% · 약 ${RideFormatter.one(chargeKm)}km 부근 충전 검토"
            else -> "예상 사용 ${predictedUse.roundToInt()}%"
        }
        tvCourseSummary.text = "${RideFormatter.one(course.totalKm)} km · $elev\n$batteryLine · 목표 ${finishTargetPct.roundToInt()}% · ${plan.modelLabel()}"
    }

    private fun startRide() {
        if (logManager.isActive()) return
        actualStore.clear()
        AppSettings.prefs(this).edit().putFloat(AppSettings.KEY_LAST_KM, 0f).also { if (testMode) it.putFloat(AppSettings.KEY_TEST_KM, 0f) }.apply()
        latestRouteKm = 0.0
        latestSpeedKmh = 0.0
        latestOffCourseM = 0.0
        logManager.start(courseMeta)
        renderRideState()
        renderAtKm(0.0, testMode)
        if (!testMode) ensurePermissionsAndStart()
        Toast.makeText(this, "주행 기록을 시작했습니다. GPS 로그는 계속 자동 저장됩니다.", Toast.LENGTH_LONG).show()
    }

    private fun confirmEndRide() {
        AlertDialog.Builder(this)
            .setTitle("주행 종료")
            .setMessage("주행을 종료하고 GPX · CSV · JSON 로그를 저장할까요?")
            .setPositiveButton("종료 및 저장") { _, _ -> endRide() }
            .setNegativeButton("계속 주행", null)
            .show()
    }

    private fun endRide() {
        if (!logManager.isActive()) return
        stopRideService()
        try {
            val archive = logManager.finalizeRide(course, actualStore, learningStore)
            renderRideState()
            updateCourseHeader()
            AlertDialog.Builder(this)
                .setTitle("주행 로그 저장 완료")
                .setMessage("${archive.courseName}\n${RideFormatter.one(archive.maxRouteKm)} km\n\nGPX · CSV · JSON · ZIP 저장 완료\n개인 배터리 학습 ${archive.learnedSamples}개 구간 반영")
                .setPositiveButton("ZIP 내보내기") { _, _ -> exportFile(archive.zipFile) }
                .setNegativeButton("확인", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "로그 저장 실패: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun renderRideState() {
        val active = logManager.isActive()
        btnRideToggle.text = if (active) "■ 주행 종료 · 로그 저장" else "▶ 주행 시작"
        btnExportLast.isEnabled = logManager.lastZipFile() != null
        if (!active) {
            tvGpsStatus.text = if (testMode) "테스트 모드 · 설정 메뉴의 테스트 위치 사용" else "주행 대기 · 코스 메뉴에서 GPX를 선택하세요"
        }
    }

    private fun exportLastLog() {
        val file = logManager.lastZipFile() ?: return Toast.makeText(this, "내보낼 주행 로그가 없습니다.", Toast.LENGTH_SHORT).show()
        exportFile(file)
    }

    private fun exportFile(file: File) {
        pendingExportFile = file
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, file.name)
        }
        startActivityForResult(intent, REQ_EXPORT)
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
            putExtra(RecognizerIntent.EXTRA_PROMPT, "자연스럽게 말하세요 · 배터리 48프로야 · 종점 목표 20 · 5킬로마다 알려줘 · 앞에 업힐 있어?")
        }
        tvMicHint.text = "🎤 듣는 중… 평소 말투로 말씀하세요"
        try { startActivityForResult(intent, REQ_SPEECH) }
        catch (_: ActivityNotFoundException) { tvMicHint.text = "음성 인식 앱을 찾지 못했습니다." }
    }

    @Deprecated("Deprecated in Android, retained for minSdk 26 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_EXPORT -> {
                if (resultCode == RESULT_OK) {
                    val uri = data?.data
                    val source = pendingExportFile
                    if (uri != null && source != null) try {
                        contentResolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                        Toast.makeText(this, "주행 로그 ZIP을 저장했습니다.", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "내보내기 실패: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
                pendingExportFile = null
            }
            REQ_SPEECH -> {
                if (resultCode != RESULT_OK) {
                    tvMicHint.text = "큰 마이크를 누르고 자연스럽게 말하세요"
                    return
                }
                val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty()
                val parsed = results.map { VoiceCommandParser.parse(it) to it }.firstOrNull { it.first !is VoiceCommand.Unknown }
                if (parsed == null) {
                    tvMicHint.text = "명령을 못 알아들었습니다 · 다시 말해주세요"
                    return
                }
                handleVoiceCommand(parsed.first, parsed.second)
            }
        }
    }

    private fun handleVoiceCommand(command: VoiceCommand, heardText: String) {
        when (command) {
            is VoiceCommand.Battery -> saveActualBattery(command.percent, heardText, command.forcePostCharge)
            is VoiceCommand.FinishTarget -> {
                finishTargetPct = command.percent.toDouble().coerceIn(1.0, 99.0)
                AppSettings.prefs(this).edit().putInt(AppSettings.KEY_FINISH_TARGET, finishTargetPct.roundToInt()).apply()
                updateCourseHeader()
                renderAtKm(latestRouteKm, testMode)
                speakText("종점 목표 잔량을 ${finishTargetPct.roundToInt()}퍼센트로 설정했습니다.")
            }
            is VoiceCommand.SetVoiceEnabled -> {
                AppSettings.prefs(this).edit().putBoolean(AppSettings.KEY_VOICE, command.enabled).apply()
                sendVoiceSettingsToService()
                Toast.makeText(this, if (command.enabled) "음성 안내를 켰습니다." else "음성 안내를 껐습니다.", Toast.LENGTH_SHORT).show()
            }
            is VoiceCommand.SetDistanceInterval -> {
                AppSettings.prefs(this).edit().putInt(AppSettings.KEY_ANNOUNCE_DISTANCE_KM, command.km.coerceIn(0, 50)).apply()
                sendVoiceSettingsToService()
                speakText(if (command.km == 0) "거리 기준 자동 안내를 껐습니다." else "거리 기준 안내를 ${command.km}킬로미터마다로 설정했습니다.")
            }
            is VoiceCommand.SetTimeInterval -> {
                AppSettings.prefs(this).edit().putInt(AppSettings.KEY_ANNOUNCE_TIME_MIN, command.minutes.coerceIn(0, 120)).apply()
                sendVoiceSettingsToService()
                speakText(if (command.minutes == 0) "시간 기준 자동 안내를 껐습니다." else "시간 기준 안내를 ${command.minutes}분마다로 설정했습니다.")
            }
            VoiceCommand.Repeat, VoiceCommand.CurrentStatus -> speakCurrentSummary()
            VoiceCommand.NextCheckpoint -> speakNextCheckpoint()
            VoiceCommand.FinishInfo -> speakFinishInfo()
            VoiceCommand.RemainingOverview -> speakRemainingOverview()
            VoiceCommand.NextClimb -> speakNextClimb()
            VoiceCommand.LocationInfo -> speakLocationInfo()
            VoiceCommand.CourseInfo -> speakCourseInfo()
            VoiceCommand.UndoActual -> undoActual()
            VoiceCommand.RideStart -> startRide()
            VoiceCommand.RideStop -> confirmEndRide()
            VoiceCommand.AddSupplyPoint -> addCurrentSupplyPoint()
            VoiceCommand.Help -> speakVoiceHelp()
            VoiceCommand.Unknown -> Unit
        }
        tvMicHint.text = "인식: ‘$heardText’"
    }

    private fun saveActualBattery(percent: Int, heardText: String, forcePostCharge: Boolean = false) {
        val pct = percent.coerceIn(0, 100)
        val km = latestRouteKm.coerceIn(0.0, course.totalKm)
        val kind = plan.classifyInput(km, pct.toDouble(), forcePostCharge)
        actualStore.save(pct.toDouble(), km, kind)
        if (logManager.isActive()) {
            logManager.recordEvent("BATTERY", "$pct% · ${kind.name}", km, pct.toDouble())
            if (kind == ActualEntryKind.POST_CHARGE) logManager.recordEvent("CHARGE_COMPLETE", "충전 후 $pct%", km, pct.toDouble())
        }
        renderAtKm(km, testMode)
        val kindText = when (kind) {
            ActualEntryKind.ARRIVAL -> "도착 잔량"
            ActualEntryKind.POST_CHARGE -> "충전 후 잔량"
            ActualEntryKind.RIDING -> "주행 중 잔량"
        }
        tvMicHint.text = "인식: ‘$heardText’ · ${kindText}로 저장"
        val reserve = plan.reserveStatus(km, finishTargetPct)
        speakText("배터리 ${pct}퍼센트로 반영했습니다. ${reserve.targetName} 예상 ${reserve.predictedPct.roundToInt()}퍼센트, 상태 ${reserve.label}.")
    }

    private fun undoActual() {
        val removed = actualStore.undoLast()
        if (removed == null) {
            Toast.makeText(this, "취소할 실제 배터리 입력이 없습니다.", Toast.LENGTH_SHORT).show()
        } else {
            if (logManager.isActive()) logManager.recordEvent("BATTERY_UNDO", "마지막 배터리 입력 취소", latestRouteKm, null)
            renderAtKm(latestRouteKm, testMode)
        }
    }

    private fun addCurrentSupplyPoint() {
        val poi = courseRepo.addCustomSupplyPoint(courseMeta.id, latestRouteKm)
        if (logManager.isActive()) logManager.recordEvent("USER_SUPPLY", poi.name, latestRouteKm, actualStore.latest()?.percent)
        val wasActive = logManager.isActive() && !testMode
        if (wasActive) stopRideService()
        loadSelectedCourse(false)
        renderAtKm(latestRouteKm, testMode)
        if (wasActive) ensurePermissionsAndStart()
        speakText("현재 ${RideFormatter.one(latestRouteKm)}킬로미터 지점을 ${poi.name}로 등록했습니다.")
    }

    private fun speakCurrentSummary() {
        val km = latestRouteKm
        val battery = plan.estimate(km)
        val reserve = plan.reserveStatus(km, finishTargetPct)
        val stats = course.elevationAhead(km, 10.0)
        val cp = plan.currentOrNextCheckpoint(km)
        val cpText = cp?.takeIf { it.km < course.totalKm - 0.05 }?.let {
            " 다음 ${it.name}까지 ${RideFormatter.one((it.km - km).coerceAtLeast(0.0))}킬로미터."
        }.orEmpty()
        val elevText = if (course.hasElevation) "앞으로 10킬로미터 상승 ${stats.ascentM.roundToInt()}미터." else "GPX에 고도 데이터가 없습니다."
        speakText("현재 ${RideFormatter.one(km)}킬로미터. 예상 배터리 ${battery.percent.roundToInt()}퍼센트. 상태 ${reserve.label}. 종점 예상 ${plan.forecast(km, course.totalKm).percent.roundToInt()}퍼센트. 목표 ${finishTargetPct.roundToInt()}퍼센트. $elevText$cpText")
    }

    private fun speakNextCheckpoint() {
        val cp = plan.currentOrNextCheckpoint(latestRouteKm) ?: return speakText("종점에 도착했습니다.")
        val remain = (cp.km - latestRouteKm).coerceAtLeast(0.0)
        val predicted = plan.forecast(latestRouteKm, cp.km).percent
        speakText("${cp.name}까지 ${RideFormatter.one(remain)}킬로미터. 예상 배터리 ${predicted.roundToInt()}퍼센트입니다.")
    }

    private fun speakFinishInfo() {
        speakText("종점까지 ${RideFormatter.one((course.totalKm - latestRouteKm).coerceAtLeast(0.0))}킬로미터. 종점 예상 배터리 ${plan.forecast(latestRouteKm, course.totalKm).percent.roundToInt()}퍼센트, 목표 ${finishTargetPct.roundToInt()}퍼센트입니다.")
    }

    private fun speakRemainingOverview() = speakNextCheckpoint()

    private fun speakNextClimb() {
        if (!course.hasElevation) return speakText("이 GPX에는 고도 데이터가 없어 업힐 분석을 할 수 없습니다.")
        val climb = course.nextMajorClimb(latestRouteKm) ?: return speakText("앞 22킬로미터 안에는 큰 연속 업힐이 없습니다.")
        val remain = (climb.startKm - latestRouteKm).coerceAtLeast(0.0)
        speakText("${if (remain <= 0.2) "현재 주요 업힐입니다." else "약 ${RideFormatter.one(remain)}킬로미터 후 주요 업힐입니다."} 길이 ${RideFormatter.one(climb.distanceKm)}킬로미터, 상승 ${climb.ascentM.roundToInt()}미터, 평균 경사 ${String.format(Locale.US, "%.1f", climb.averageGradePct)}퍼센트입니다.")
    }

    private fun speakLocationInfo() {
        speakText("현재 코스 ${RideFormatter.one(latestRouteKm)}킬로미터 지점${if (course.hasElevation) ", 고도 약 ${latestCourseElevation.roundToInt()}미터" else ""}입니다.")
    }

    private fun speakCourseInfo() {
        val elev = if (course.hasElevation) "누적 상승 ${course.totalAscentM.roundToInt()}미터." else "고도 데이터는 없습니다."
        speakText("${courseMeta.name}. 전체 ${RideFormatter.one(course.totalKm)}킬로미터. $elev 예상 총 배터리 사용량 ${plan.predictedTotalUsePct().roundToInt()}퍼센트입니다.")
    }

    private fun speakVoiceHelp() {
        speakText("자연스럽게 말하세요. 지금 배터리 48프로야, 종점 목표 20프로, 5킬로마다 알려줘, 10분마다 알려줘, 앞에 업힐 있어, 종점까지 얼마나 남았어, 여기를 보급소로 등록해처럼 말할 수 있습니다.")
    }

    private fun speakText(text: String) {
        if (testMode || !logManager.isActive()) Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        else sendServiceAction(RideService.ACTION_SPEAK_TEXT) { putExtra(RideService.EXTRA_SPEAK_TEXT, text) }
    }

    private fun ensurePermissionsAndStart() {
        if (testMode || !logManager.isActive()) return
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
            REQ_LOCATION -> if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) ensurePermissionsAndStart() else tvGpsStatus.text = "위치 권한이 필요합니다 · 설정에서 테스트 모드는 사용 가능"
            REQ_NOTIFICATIONS -> startRideService()
            REQ_MICROPHONE -> {
                val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
                if (granted && speechPendingAfterPermission) launchVoiceCommand()
                else Toast.makeText(this, "음성 입력을 사용하려면 마이크 권한이 필요합니다.", Toast.LENGTH_LONG).show()
                speechPendingAfterPermission = false
            }
        }
    }

    private fun startRideService() {
        val intent = Intent(this, RideService::class.java).apply { action = RideService.ACTION_START }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        sendVoiceSettingsToService()
        tvGpsStatus.text = "GPS 추적 + 로그 자동 저장 중 · 화면을 꺼도 유지"
    }

    private fun sendVoiceSettingsToService() {
        if (!logManager.isActive() || testMode) return
        sendServiceAction(RideService.ACTION_SET_VOICE) {
            putExtra(RideService.EXTRA_VOICE_ENABLED, AppSettings.voiceEnabled(this@MainActivity))
        }
        sendServiceAction(RideService.ACTION_SET_VOICE_INTERVALS) {
            putExtra(RideService.EXTRA_DISTANCE_INTERVAL_KM, AppSettings.distanceIntervalKm(this@MainActivity))
            putExtra(RideService.EXTRA_TIME_INTERVAL_MIN, AppSettings.timeIntervalMin(this@MainActivity))
        }
    }

    private fun stopRideService() {
        try { stopService(Intent(this, RideService::class.java)) } catch (_: Exception) { }
    }

    private fun sendServiceAction(action: String, block: Intent.() -> Unit = {}) {
        if (!logManager.isActive() && action != RideService.ACTION_STOP) return
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
        tvBatteryRange.text = "${battery.note} · 예상 범위 ${range.start.roundToInt()}~${range.endInclusive.roundToInt()}%"

        tvRiskStatus.text = reserve.label
        tvRiskStatus.setTextColor(when (reserve.label) {
            "여유" -> getColor(R.color.good)
            "주의" -> getColor(R.color.warn)
            else -> getColor(R.color.danger)
        })
        val diffAbs = abs(reserve.differencePct).roundToInt()
        val differenceText = if (reserve.differencePct >= 0) "목표보다 ${diffAbs}% 여유" else "목표보다 ${diffAbs}% 부족 · 약 ${diffAbs}% 절약 필요"
        tvRiskDetail.text = "${reserve.targetName} 예상 ${reserve.predictedPct.roundToInt()}% / 목표 ${reserve.targetPct.roundToInt()}% · $differenceText · 실시간계수 ${String.format(Locale.US, "%.2f", reserve.consumptionFactor)}x"

        val latestActual = actualStatus?.entry
        if (latestActual == null) {
            tvActualBattery.text = "실제값 없음"
            tvActualBattery.setTextColor(getColor(R.color.text_secondary))
            tvActualDetail.text = "실제 배터리를 말하면 이 코스의 이후 소비 예측을 즉시 보정합니다."
            btnUndoActual.isEnabled = false
        } else {
            tvActualBattery.text = "실제 ${latestActual.percent.roundToInt()}%"
            tvActualBattery.setTextColor(batteryColor(latestActual.percent))
            val delta = actualStatus.delta.roundToInt()
            val phase = when (latestActual.kind) {
                ActualEntryKind.ARRIVAL -> "도착값"
                ActualEntryKind.POST_CHARGE -> "충전 후 기준"
                ActualEntryKind.RIDING -> "주행 기준"
            }
            tvActualDetail.text = "${RideFormatter.one(latestActual.routeKm)}km · 기준 대비 ${if (delta >= 0) "+" else ""}$delta% · $phase · 소비 ${String.format(Locale.US, "%.2f", actualStatus.consumptionFactor)}x · ${timeText(latestActual.timestampMs)}"
            btnUndoActual.isEnabled = true
        }

        val remainFinish = (course.totalKm - km).coerceAtLeast(0.0)
        tvSpeed.text = if (latestSpeedKmh >= 2.0) "이동 평균 ${RideFormatter.one(latestSpeedKmh)} km/h" else "이동 속도 계산 중"
        tvFinishEta.text = "종점 ${RideFormatter.one(remainFinish)}km · ${RideFormatter.etaClock(remainFinish, latestSpeedKmh)}"

        if (cp != null) {
            val remain = (cp.km - km).coerceAtLeast(0.0)
            val atCurrent = abs(cp.km - km) <= 0.15
            val predicted = plan.forecast(km, cp.km).percent.roundToInt()
            tvNextCheckpoint.text = if (atCurrent) "현재 · ${cp.name}" else cp.name
            tvNextCheckpointDetail.text = when {
                cp.chargeToPct != null && atCurrent -> "도착 예상 $predicted% → 계획 ${cp.chargeToPct.roundToInt()}% 충전"
                cp.chargeToPct != null -> "${RideFormatter.one(remain)}km · 도착 예상 $predicted% · 계획 ${cp.chargeToPct.roundToInt()}% 충전"
                cp.km >= course.totalKm - 0.05 -> "${RideFormatter.one(remain)}km 남음 · 종점 예상 $predicted%"
                else -> "${RideFormatter.one(remain)}km 남음 · 예상 배터리 $predicted% · 보급/충전 가능 지점"
            }
            tvEta.text = "예상 도착 ${RideFormatter.etaClock(remain, latestSpeedKmh)} · ${RideFormatter.duration(remain, latestSpeedKmh)}"
        } else {
            tvNextCheckpoint.text = "코스 완료"
            tvNextCheckpointDetail.text = "종점 도착"
            tvEta.text = "완료"
        }

        if (course.hasElevation) {
            tvElevationAhead.text = "앞 10km  ▲${stats10.ascentM.roundToInt()}m   ▼${stats10.descentM.roundToInt()}m"
            if (climb == null) {
                tvNextClimb.text = "주요 업힐 없음"
                tvNextClimbDetail.text = "앞 22km에서 큰 연속 업힐이 감지되지 않았습니다."
            } else {
                val rem = (climb.startKm - km).coerceAtLeast(0.0)
                tvNextClimb.text = if (rem <= 0.2) "현재 주요 업힐" else "${RideFormatter.one(rem)}km 후 주요 업힐"
                tvNextClimbDetail.text = "길이 ${RideFormatter.one(climb.distanceKm)}km · 상승 +${climb.ascentM.roundToInt()}m · 평균 ${String.format(Locale.US, "%.1f", climb.averageGradePct)}%"
            }
        } else {
            tvElevationAhead.text = "GPX 고도 데이터 없음 · 거리 기반 모드"
            tvNextClimb.text = "업힐 분석 불가"
            tvNextClimbDetail.text = "이 GPX에는 ele 고도 데이터가 충분하지 않습니다."
        }

        tvTenKmBattery.text = "10km 후 예상 ${battery10.percent.roundToInt()}%${if (battery10.calibrated) " · 실측 보정" else ""}"
        tvAssist.text = when {
            reserve.label == "위험" -> "⚠ 목표 달성을 위해 약 ${(-reserve.differencePct).coerceAtLeast(0.0).roundToInt()}% 절약 필요"
            reserve.label == "주의" -> "배터리 목표선 근처 · 긴 업힐에서 보조 강도 절약"
            else -> plan.assistText(km, battery, stats10)
        }
        tvAssist.setTextColor(when {
            reserve.label == "위험" -> getColor(R.color.danger)
            reserve.label == "주의" -> getColor(R.color.warn)
            else -> getColor(R.color.good)
        })
        tvNextPoi.text = poi?.let { "다음 포인트 · ${it.name} · ${RideFormatter.one((it.routeKm - km).coerceAtLeast(0.0))}km" } ?: "다음 포인트 · 종점"

        val accText = if (latestAccuracyM >= 0) "±${latestAccuracyM.roundToInt()}m" else "-"
        val offText = if (simulated) "테스트" else "코스 이탈 ${latestOffCourseM.roundToInt()}m"
        val ele = if (latestCourseElevation > 0) latestCourseElevation else point.ele
        tvCourseStatus.text = if (course.hasElevation) "고도 ${ele.roundToInt()}m · GPS $accText · $offText" else "GPS $accText · $offText · 고도 없음"

        if (!simulated) {
            tvGpsStatus.text = when {
                !logManager.isActive() -> "주행 대기 · 코스 메뉴에서 GPX를 선택하세요"
                latestOffCourseM >= 150 -> "⚠ 코스에서 ${latestOffCourseM.roundToInt()}m 벗어남 · 방향 확인"
                latestAccuracyM > 50 -> "GPS 정확도 낮음 ${latestAccuracyM.roundToInt()}m"
                else -> "GPS 추적 + 로그 자동 저장 정상"
            }
            tvGpsStatus.setTextColor(if (latestOffCourseM >= 150 && logManager.isActive()) getColor(R.color.danger) else getColor(R.color.text_secondary))
        }
        profileView.setCurrentKm(km)
        renderRideState()
    }

    private fun showRideReport() {
        val text = if (logManager.isActive()) logManager.activeSummaryText() + "\n\n" + learningStore.summaryText()
        else logManager.lastReportText() + "\n\n" + learningStore.summaryText()
        AlertDialog.Builder(this)
            .setTitle(if (logManager.isActive()) "현재 주행" else "최근 주행 리포트")
            .setMessage(text)
            .setPositiveButton("확인", null)
            .show()
    }

    private fun batteryColor(percent: Double): Int = when {
        percent >= 60 -> getColor(R.color.good)
        percent >= 40 -> getColor(R.color.warn)
        percent >= 25 -> getColor(R.color.orange)
        else -> getColor(R.color.danger)
    }

    private fun applyKeepScreenOn(enabled: Boolean) {
        if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun appVersionName(): String = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0.8.0"
    } catch (_: Exception) { "0.8.0" }

    private fun timeText(timestampMs: Long): String = if (timestampMs <= 0) "" else SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(timestampMs))

    override fun onResume() {
        super.onResume()
        if (!::courseRepo.isInitialized) return

        // 코스 메뉴에서 선택한 코스가 바뀌었으면 즉시 재로딩.
        val activeId = runCatching { courseRepo.activeMeta().id }.getOrNull()
        if (activeId != null && activeId != loadedCourseId && !logManager.isActive()) {
            actualStore.clear()
            loadSelectedCourse(resetProgress = false)
        }
        applySettings()
        renderCurrentMode()
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            val filter = IntentFilter(RideService.ACTION_UPDATE)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(rideReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else {
                @Suppress("DEPRECATION")
                registerReceiver(rideReceiver, filter)
            }
            receiverRegistered = true
        }
        renderRideState()
        if (::course.isInitialized) renderAtKm(latestRouteKm, testMode)
        if (::logManager.isInitialized && logManager.isActive() && !testMode) ensurePermissionsAndStart()
    }

    override fun onStop() {
        if (receiverRegistered) {
            try { unregisterReceiver(rideReceiver) } catch (_: Exception) { }
            receiverRegistered = false
        }
        super.onStop()
    }
}
