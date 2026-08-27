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
import android.provider.OpenableColumns
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.text.InputType
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : Activity() {
    companion object {
        private const val REQ_LOCATION = 1001
        private const val REQ_NOTIFICATIONS = 1002
        private const val REQ_MICROPHONE = 1003
        private const val REQ_SPEECH = 1004
        private const val REQ_GPX_IMPORT = 1005
        private const val REQ_POST_RIDE_FIT = 1006
        private const val REQ_BLUETOOTH = 1007
    }

    private lateinit var courseRepo: CourseRepository
    private lateinit var courseMeta: CourseMeta
    private lateinit var course: CourseData
    private lateinit var learningStore: BatteryLearningStore
    private lateinit var chargingStore: ChargingStationStore
    private lateinit var logManager: RideLogManager
    private lateinit var basePlan: BatteryPlan
    private lateinit var actualStore: BatteryActualStore
    private lateinit var bleStateStore: AvinoxBleStateStore
    private lateinit var chargingSessionStore: ChargingSessionStore
    private lateinit var avinoxReferenceStore: AvinoxReferenceStore
    private lateinit var assistProfileStore: AvinoxAssistProfileStore
    private lateinit var plan: AdaptiveBatteryPlan
    private lateinit var pacingAdvisor: EnergyPacingAdvisor
    private lateinit var tripPlanner: EnergyTripPlanner

    private lateinit var btnCourseMenu: Button
    private lateinit var btnCourseImportQuick: Button
    private lateinit var tvCourseQuickSelect: TextView
    private lateinit var tvAvinoxReferenceSummary: TextView
    private lateinit var btnAvinoxReferenceEdit: Button
    private lateinit var btnRideToggle: Button
    private lateinit var btnChargeToggle: Button
    private lateinit var tvGpsStatus: TextView
    private lateinit var tvRideMode: TextView
    private lateinit var tvAssistModeCurrent: TextView
    private lateinit var tvAssistModeHint: TextView
    private lateinit var layoutAssistVerify: LinearLayout
    private lateinit var btnAssistModeConfirm: Button
    private lateinit var btnAssistModeMismatch: Button
    private lateinit var btnAssistProfileEdit: Button
    private lateinit var btnAssistEco: Button
    private lateinit var btnAssistAuto: Button
    private lateinit var btnAssistTrail: Button
    private lateinit var btnAssistTurbo: Button
    private lateinit var tvCurrentKm: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvBatteryRange: TextView
    private lateinit var tvCompareActual: TextView
    private lateinit var tvCompareModel: TextView
    private lateinit var tvCompareAvinox: TextView
    private lateinit var tvCompareDetail: TextView
    private lateinit var progressBattery: ProgressBar
    private lateinit var tvRiskStatus: TextView
    private lateinit var tvRiskDetail: TextView
    private lateinit var tvActualBattery: TextView
    private lateinit var tvActualDetail: TextView
    private lateinit var btnManualBattery: Button
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
    private lateinit var btnRideReport: Button
    private lateinit var btnPostRideFit: Button
    private lateinit var btnFeedbackStrava: Button
    private lateinit var btnPostRideAvinox: Button
    private lateinit var btnPostRideCompare: Button
    private lateinit var btnPostRideLearn: Button
    private lateinit var tvChargeStatus: TextView
    private lateinit var switchPageVoice: Switch
    private lateinit var switchPageKeepScreen: Switch
    private lateinit var tvPageDistanceInterval: TextView
    private lateinit var seekPageDistanceInterval: SeekBar
    private lateinit var tvPageTimeInterval: TextView
    private lateinit var seekPageTimeInterval: SeekBar
    private lateinit var tvPageFinishTarget: TextView
    private lateinit var seekPageFinishTarget: SeekBar
    private lateinit var switchPageTestMode: Switch
    private lateinit var tvPageTestKm: TextView
    private lateinit var seekPageTestKm: SeekBar
    private lateinit var tvPageSettingsHint: TextView
    private lateinit var btnPageBleDiagnostic: Button
    private lateinit var tvPageUpdateStatus: TextView
    private lateinit var switchPageBetaUpdates: Switch
    private lateinit var btnPageCheckUpdate: Button
    private lateinit var btnPageResetProgress: Button
    private lateinit var tvLearningPageSummary: TextView
    private lateinit var btnLearningFit: Button
    private lateinit var btnLearningGpx: Button
    private lateinit var btnLearningManage: Button
    private lateinit var btnLearningClear: Button
    private lateinit var pagerFlipper: ViewFlipper
    private lateinit var tvPagerIndicator: TextView
    private lateinit var pagerGesture: GestureDetector

    private var latestRouteKm = 0.0
    private var latestOffCourseM = 0.0
    private var latestAccuracyM = -1f
    private var latestSpeedKmh = 0.0
    private var latestCourseElevation = 0.0
    private var latestFreeAscentM = 0.0
    private var latestBleSoc: Int? = null
    private var latestBleState: String = "BLE 대기"
    private var latestBleUpdatedMs: Long = 0L
    private var latestAssistPrimary: AvinoxAssistMode? = null
    private var latestAssistAlternate: AvinoxAssistMode? = null
    private var latestAssistConfidence: String = ""
    private var latestAssistRawCode: Int? = null
    private var latestAssistUpdatedMs: Long = 0L
    private var lastPlanAssistMode: AvinoxAssistMode? = null
    private var testMode = false
    private var receiverRegistered = false
    private var speechPendingAfterPermission = false
    private var finishTargetPct = AppSettings.DEFAULT_FINISH_TARGET.toDouble()
    private var loadedCourseId: String? = null
    private var voiceInputStartedMs: Long = 0L
    private var voiceInputRouteKm: Double = 0.0
    private var refreshingSettingsUi = false

    private val rideReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != RideService.ACTION_UPDATE || testMode) return
            latestRouteKm = intent.getDoubleExtra(RideService.EXTRA_ROUTE_KM, latestRouteKm)
            latestOffCourseM = intent.getDoubleExtra(RideService.EXTRA_OFF_COURSE_M, latestOffCourseM)
            latestAccuracyM = intent.getFloatExtra(RideService.EXTRA_ACCURACY_M, latestAccuracyM)
            latestSpeedKmh = intent.getDoubleExtra(RideService.EXTRA_SPEED_KMH, latestSpeedKmh)
            latestCourseElevation = intent.getDoubleExtra(RideService.EXTRA_COURSE_ELEVATION, latestCourseElevation)
            latestFreeAscentM = intent.getDoubleExtra(RideService.EXTRA_FREE_ASCENT_M, latestFreeAscentM)
            if (intent.hasExtra(RideService.EXTRA_BLE_SOC)) latestBleSoc = intent.getIntExtra(RideService.EXTRA_BLE_SOC, -1).takeIf { it in 0..100 }
            if (intent.hasExtra(RideService.EXTRA_BLE_STATE)) latestBleState = intent.getStringExtra(RideService.EXTRA_BLE_STATE).orEmpty().ifBlank { latestBleState }
            if (intent.hasExtra(RideService.EXTRA_BLE_UPDATED_MS)) latestBleUpdatedMs = intent.getLongExtra(RideService.EXTRA_BLE_UPDATED_MS, latestBleUpdatedMs)
            if (intent.hasExtra(RideService.EXTRA_ASSIST_PRIMARY)) latestAssistPrimary = runCatching { AvinoxAssistMode.valueOf(intent.getStringExtra(RideService.EXTRA_ASSIST_PRIMARY).orEmpty()) }.getOrNull()
            latestAssistAlternate = if (intent.hasExtra(RideService.EXTRA_ASSIST_ALTERNATE)) runCatching { AvinoxAssistMode.valueOf(intent.getStringExtra(RideService.EXTRA_ASSIST_ALTERNATE).orEmpty()) }.getOrNull() else null
            if (intent.hasExtra(RideService.EXTRA_ASSIST_CONFIDENCE)) latestAssistConfidence = intent.getStringExtra(RideService.EXTRA_ASSIST_CONFIDENCE).orEmpty()
            if (intent.hasExtra(RideService.EXTRA_ASSIST_RAW_CODE)) latestAssistRawCode = intent.getIntExtra(RideService.EXTRA_ASSIST_RAW_CODE, -1).takeIf { it >= 0 }
            if (intent.hasExtra(RideService.EXTRA_ASSIST_UPDATED_MS)) latestAssistUpdatedMs = intent.getLongExtra(RideService.EXTRA_ASSIST_UPDATED_MS, latestAssistUpdatedMs)
            val activeMode = intent.getStringExtra(RideService.EXTRA_ASSIST_ACTIVE_MODE)?.let { runCatching { AvinoxAssistMode.valueOf(it) }.getOrNull() }
            val activeConfidence = intent.getStringExtra(RideService.EXTRA_ASSIST_ACTIVE_CONFIDENCE).orEmpty()
            if (activeMode != null && activeConfidence in setOf("HIGH", "CONFIRMED") && activeMode != lastPlanAssistMode) {
                assistProfileStore.setPreferredMode(activeMode)
                lastPlanAssistMode = activeMode
                rebuildEnergyModelsForSelectedMode()
            }
            renderAssistModeUi()
            if (logManager.isFreeRide()) renderFreeRide() else renderAtKm(latestRouteKm, false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemBarInsets()
        LearningMigration.ensureV0110FreshStart(this)
        bindViews()

        courseRepo = CourseRepository(this)
        learningStore = BatteryLearningStore(this)
        chargingStore = ChargingStationStore(this)
        logManager = RideLogManager(this)
        actualStore = BatteryActualStore(this)
        bleStateStore = AvinoxBleStateStore(this)
        chargingSessionStore = ChargingSessionStore(this)
        avinoxReferenceStore = AvinoxReferenceStore(this)
        assistProfileStore = AvinoxAssistProfileStore(this)

        if (!logManager.isActive()) {
            actualStore.clear()
            chargingSessionStore.clear()
            bleStateStore.clearRuntime(keepAddress = true)
        }
        loadBleSnapshot()

        // 앱이 재시작된 경우 진행 중 세션의 코스를 우선 복구.
        logManager.activeRide()?.takeIf { it.mode == RideMode.PLAN }?.let { active -> runCatching { courseRepo.setActive(active.courseId) } }
        if (!loadSelectedCourse(resetProgress = false)) return
        applySettings()

        btnCourseMenu.setOnClickListener { startActivity(Intent(this, CourseActivity::class.java)) }
        btnCourseImportQuick.setOnClickListener { importGpxQuick() }
        tvCourseQuickSelect.setOnClickListener { showCoursePickerQuick() }
        btnAvinoxReferenceEdit.setOnClickListener { showAvinoxReferenceDialog() }
        btnRideToggle.setOnClickListener { if (logManager.isActive()) confirmEndRide() else showRideStartModeDialog() }
        btnChargeToggle.setOnClickListener { toggleCharging() }
        btnAssistEco.setOnClickListener { selectAssistMode(AvinoxAssistMode.ECO) }
        btnAssistAuto.setOnClickListener { selectAssistMode(AvinoxAssistMode.AUTO) }
        btnAssistTrail.setOnClickListener { selectAssistMode(AvinoxAssistMode.TRAIL) }
        btnAssistTurbo.setOnClickListener { selectAssistMode(AvinoxAssistMode.TURBO) }
        btnAssistModeConfirm.setOnClickListener { confirmDetectedAssistPrimary() }
        btnAssistModeMismatch.setOnClickListener { showDetectedAssistCorrection() }
        tvAssistModeCurrent.setOnClickListener { if (logManager.isActive()) showDetectedAssistCorrection() }
        btnAssistProfileEdit.setOnClickListener { showAssistProfilePicker() }
        btnManualBattery.setOnClickListener { showManualBatteryDialog() }
        btnRideReport.setOnClickListener { showRideReport() }
        btnFeedbackStrava.setOnClickListener { startActivity(Intent(this, StravaActivity::class.java)) }
        btnPostRideFit.setOnClickListener { pickPostRideFit() }
        btnPostRideAvinox.setOnClickListener { showPostRideAvinoxDialog() }
        btnPostRideCompare.setOnClickListener { showPostRideComparison() }
        btnPostRideLearn.setOnClickListener { learnLastFreeRide() }
        setupInlineSettings()
        setupLearningPage()
        setupSwipePager()

        renderCourseQuick()
        refreshInlineSettings()
        refreshLearningPage()
        renderRideState()
        renderAssistModeUi()
        renderCurrentMode()
        UpdateManager.maybeCheckOnLaunch(this)
    }

    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.rootMain)
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                baseLeft + bars.left,
                baseTop + bars.top,
                baseRight + bars.right,
                baseBottom + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun bindViews() {
        btnCourseMenu = findViewById(R.id.btnCourseMenu)
        btnCourseImportQuick = findViewById(R.id.btnCourseImportQuick)
        tvCourseQuickSelect = findViewById(R.id.tvCourseQuickSelect)
        tvAvinoxReferenceSummary = findViewById(R.id.tvAvinoxReferenceSummary)
        btnAvinoxReferenceEdit = findViewById(R.id.btnAvinoxReferenceEdit)
        btnRideToggle = findViewById(R.id.btnRideToggle)
        btnChargeToggle = findViewById(R.id.btnChargeToggle)
        tvGpsStatus = findViewById(R.id.tvGpsStatus)
        tvRideMode = findViewById(R.id.tvRideMode)
        tvAssistModeCurrent = findViewById(R.id.tvAssistModeCurrent)
        tvAssistModeHint = findViewById(R.id.tvAssistModeHint)
        layoutAssistVerify = findViewById(R.id.layoutAssistVerify)
        btnAssistModeConfirm = findViewById(R.id.btnAssistModeConfirm)
        btnAssistModeMismatch = findViewById(R.id.btnAssistModeMismatch)
        btnAssistProfileEdit = findViewById(R.id.btnAssistProfileEdit)
        btnAssistEco = findViewById(R.id.btnAssistEco)
        btnAssistAuto = findViewById(R.id.btnAssistAuto)
        btnAssistTrail = findViewById(R.id.btnAssistTrail)
        btnAssistTurbo = findViewById(R.id.btnAssistTurbo)
        tvCurrentKm = findViewById(R.id.tvCurrentKm)
        tvBattery = findViewById(R.id.tvBattery)
        tvBatteryRange = findViewById(R.id.tvBatteryRange)
        tvCompareActual = findViewById(R.id.tvCompareActual)
        tvCompareModel = findViewById(R.id.tvCompareModel)
        tvCompareAvinox = findViewById(R.id.tvCompareAvinox)
        tvCompareDetail = findViewById(R.id.tvCompareDetail)
        progressBattery = findViewById(R.id.progressBattery)
        tvRiskStatus = findViewById(R.id.tvRiskStatus)
        tvRiskDetail = findViewById(R.id.tvRiskDetail)
        tvActualBattery = findViewById(R.id.tvActualBattery)
        tvActualDetail = findViewById(R.id.tvActualDetail)
        btnManualBattery = findViewById(R.id.btnManualBattery)
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
        btnRideReport = findViewById(R.id.btnRideReport)
        btnPostRideFit = findViewById(R.id.btnPostRideFit)
        btnFeedbackStrava = findViewById(R.id.btnFeedbackStrava)
        btnPostRideAvinox = findViewById(R.id.btnPostRideAvinox)
        btnPostRideCompare = findViewById(R.id.btnPostRideCompare)
        btnPostRideLearn = findViewById(R.id.btnPostRideLearn)
        tvChargeStatus = findViewById(R.id.tvChargeStatus)
        switchPageVoice = findViewById(R.id.switchPageVoice)
        switchPageKeepScreen = findViewById(R.id.switchPageKeepScreen)
        tvPageDistanceInterval = findViewById(R.id.tvPageDistanceInterval)
        seekPageDistanceInterval = findViewById(R.id.seekPageDistanceInterval)
        tvPageTimeInterval = findViewById(R.id.tvPageTimeInterval)
        seekPageTimeInterval = findViewById(R.id.seekPageTimeInterval)
        tvPageFinishTarget = findViewById(R.id.tvPageFinishTarget)
        seekPageFinishTarget = findViewById(R.id.seekPageFinishTarget)
        switchPageTestMode = findViewById(R.id.switchPageTestMode)
        tvPageTestKm = findViewById(R.id.tvPageTestKm)
        seekPageTestKm = findViewById(R.id.seekPageTestKm)
        tvPageSettingsHint = findViewById(R.id.tvPageSettingsHint)
        btnPageBleDiagnostic = findViewById(R.id.btnPageBleDiagnostic)
        tvPageUpdateStatus = findViewById(R.id.tvPageUpdateStatus)
        switchPageBetaUpdates = findViewById(R.id.switchPageBetaUpdates)
        btnPageCheckUpdate = findViewById(R.id.btnPageCheckUpdate)
        btnPageResetProgress = findViewById(R.id.btnPageResetProgress)
        tvLearningPageSummary = findViewById(R.id.tvLearningPageSummary)
        btnLearningFit = findViewById(R.id.btnLearningFit)
        btnLearningGpx = findViewById(R.id.btnLearningGpx)
        btnLearningManage = findViewById(R.id.btnLearningManage)
        btnLearningClear = findViewById(R.id.btnLearningClear)
        pagerFlipper = findViewById(R.id.pagerFlipper)
        tvPagerIndicator = findViewById(R.id.tvPagerIndicator)
    }

    private fun loadSelectedCourse(resetProgress: Boolean): Boolean {
        return try {
            courseMeta = courseRepo.activeMeta()
            course = courseRepo.loadCourse(courseMeta.id)
            loadedCourseId = courseMeta.id
            basePlan = BatteryPlan(course, learningStore, chargingStore.list(courseMeta.id))
            plan = AdaptiveBatteryPlan(basePlan, actualStore)
            pacingAdvisor = EnergyPacingAdvisor(course, learningStore)
            tripPlanner = EnergyTripPlanner(basePlan, plan)
            profileView.setCourse(course)
            val prefs = AppSettings.prefs(this)
            if (resetProgress) {
                prefs.edit().putFloat(AppSettings.KEY_LAST_KM, 0f).putFloat(AppSettings.KEY_TEST_KM, 0f).apply()
            }
            latestRouteKm = prefs.getFloat(AppSettings.KEY_LAST_KM, 0f).toDouble().coerceIn(0.0, course.totalKm)
            latestCourseElevation = course.pointAtKm(latestRouteKm).ele
            tvVersion.text = "Battery Copilot v${appVersionName()} · Plan + Free Ride"
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

        if (logManager.isActive() && !testMode) {
            sendVoiceSettingsToService()
        }
    }

    private fun renderCurrentMode() {
        if (logManager.isFreeRide()) {
            testMode = false
            latestRouteKm = logManager.activeDistanceKm()
            latestFreeAscentM = logManager.activeAscentM()
            renderFreeRide()
            ensurePermissionsAndStart()
            return
        }
        if (testMode) {
            stopRideService()
            latestRouteKm = AppSettings.testKm(this).coerceIn(0.0, course.totalKm)
            latestSpeedKmh = 17.0
            latestOffCourseM = 0.0
            latestAccuracyM = 5f
            latestCourseElevation = course.pointAtKm(latestRouteKm).ele
            tvGpsStatus.text = "테스트 모드"
            renderAtKm(latestRouteKm, true)
        } else {
            val stored = AppSettings.prefs(this).getFloat(AppSettings.KEY_LAST_KM, latestRouteKm.toFloat()).toDouble()
            latestRouteKm = stored.coerceIn(0.0, course.totalKm)
            renderAtKm(latestRouteKm, false)
            if (logManager.isActive()) ensurePermissionsAndStart()
        }
    }

    private fun startPlanRide() {
        if (logManager.isActive()) return
        actualStore.clear()
        chargingSessionStore.clear()
        bleStateStore.clearRuntime(keepAddress = true)
        latestBleSoc = null
        latestBleState = "Avinox 연결 준비"
        latestBleUpdatedMs = 0L
        AppSettings.prefs(this).edit().putFloat(AppSettings.KEY_LAST_KM, 0f).also { if (testMode) it.putFloat(AppSettings.KEY_TEST_KM, 0f) }.apply()
        latestRouteKm = 0.0
        latestSpeedKmh = 0.0
        latestOffCourseM = 0.0
        latestAssistPrimary = null
        latestAssistAlternate = null
        latestAssistConfidence = ""
        latestAssistRawCode = null
        latestAssistUpdatedMs = 0L
        lastPlanAssistMode = null
        assistProfileStore.clearPreferredMode()
        rebuildEnergyModelsForSelectedMode()
        logManager.startPlan(courseMeta)
        recordAvinoxReferenceEvent()
        renderRideState()
        renderAtKm(0.0, testMode)
        if (!testMode) ensurePermissionsAndStart()
        Toast.makeText(this, "계획주행 시작 · Avinox BLE 배터리 + 모드 자동 감지 · 충전 권장량 자동 재계산", Toast.LENGTH_LONG).show()
    }

    private fun showRideStartModeDialog() {
        if (testMode) {
            startPlanRide()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("주행 방식 선택")
            .setMessage("계획주행은 선택된 GPX를 따라 예측합니다.\n\n임의주행은 현재 GPX와 완전히 독립적으로 GPS·고도·속도·실제 배터리만 기록합니다.")
            .setPositiveButton("🗺 계획주행") { _, _ -> startPlanRide() }
            .setNeutralButton("🚵 임의주행") { _, _ -> startFreeRide() }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun startFreeRide() {
        if (logManager.isActive()) return
        actualStore.clear()
        chargingSessionStore.clear()
        bleStateStore.clearRuntime(keepAddress = true)
        latestBleSoc = null
        latestBleState = "Avinox 연결 준비"
        latestBleUpdatedMs = 0L
        latestRouteKm = 0.0
        latestFreeAscentM = 0.0
        latestSpeedKmh = 0.0
        latestOffCourseM = 0.0
        latestAssistPrimary = null
        latestAssistAlternate = null
        latestAssistConfidence = ""
        latestAssistRawCode = null
        latestAssistUpdatedMs = 0L
        lastPlanAssistMode = null
        assistProfileStore.clearPreferredMode()
        rebuildEnergyModelsForSelectedMode()
        logManager.startFree()
        renderRideState()
        renderFreeRide()
        ensurePermissionsAndStart()
        Toast.makeText(this, "임의주행 시작 · Avinox BLE 배터리 + 모드 자동 감지", Toast.LENGTH_LONG).show()
    }

    private fun recordAvinoxReferenceEvent() {
        val ref = avinoxReferenceStore.get(courseMeta.id) ?: return
        val selected = ref.selectedMode?.takeIf { ref.value(it) != null }
        val modeText = selected?.let { "실시간 비교 ${it.label} ${formatPct(ref.value(it)!!)}" } ?: "비교 표시 안 함"
        val detail = "${ref.compactValues()} · $modeText · 자체예측/개인학습 미적용"
        logManager.recordEvent("AVINOX_BENCHMARK", detail, 0.0, actualStore.latest()?.percent)
    }

    private fun confirmEndRide() {
        AlertDialog.Builder(this)
            .setTitle("주행 종료")
            .setMessage("주행을 종료하고 GPS · 배터리 · 이벤트 로그를 저장할까요?")
            .setPositiveButton("종료 및 저장") { _, _ -> endRide() }
            .setNegativeButton("계속 주행", null)
            .show()
    }

    private fun endRide() {
        if (!logManager.isActive()) return
        stopRideService()
        try {
            if (logManager.isFreeRide()) {
                val archive = logManager.finalizeFreeRide(actualStore = actualStore, testMode = false)
                chargingSessionStore.clear()
                renderRideState()
                AlertDialog.Builder(this)
                    .setTitle("임의주행 저장 완료")
                    .setMessage("${RideFormatter.one(archive.maxRouteKm)} km 주행을 저장했습니다.\n\n이제 Avinox에서 FIT 파일을 내려받고, 같은 코스를 내비게이션에 등록해 나온 ECO/AUTO/TRAIL/TURBO 소비량을 사후에 추가하면 됩니다.\n\n피드백 페이지에서 FIT와 Avinox 값을 연결할 수 있습니다.")
                    .setPositiveButton("피드백 페이지로") { _, _ -> showPagerChild(4) }
                    .setNegativeButton("나중에", null)
                    .show()
                return
            }
            val archive = logManager.finalizeRide(
                course = course,
                actualStore = actualStore,
                chargingStations = chargingStore.list(courseMeta.id),
                testMode = testMode
            )
            chargingSessionStore.clear()
            renderRideState()
            if (testMode) {
                logManager.skipArchiveLearning(archive, "TEST_MODE_SKIPPED")
                AlertDialog.Builder(this)
                    .setTitle("주행 로그 저장 완료")
                    .setMessage("${archive.courseName}\n${RideFormatter.one(archive.maxRouteKm)} km\n\nGPX · CSV · JSON · ZIP 저장 완료\n\n테스트 모드 주행은 개인 배터리 학습에서 자동 제외했습니다.\n최근 ZIP 내보내기는 코스 메뉴에서 할 수 있습니다.")
                    .setPositiveButton("확인", null)
                    .show()
            } else {
                askLearningDecision(archive)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "로그 저장 실패: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun askLearningDecision(archive: RideArchive) {
        AlertDialog.Builder(this)
            .setTitle("주행 로그 저장 완료")
            .setMessage(
                "${archive.courseName}\n${RideFormatter.one(archive.maxRouteKm)} km\n\n" +
                    "GPX · CSV · JSON · ZIP 저장 완료\n\n" +
                    "검증된 Avinox 모드 + 실제 BLE 배터리 구간만 앞으로의 예측 학습에 사용할까요?\n" +
                    "모드가 불확실했던 구간은 자동 제외됩니다. 테스트 주행이면 ‘사용 안 함’을 선택하세요."
            )
            .setPositiveButton("학습에 사용") { _, _ ->
                val learned = logManager.learnFromArchive(archive, course, learningStore)
                val msg = if (learned > 0) {
                    "개인 배터리 학습 ${learned}개 구간을 반영했습니다."
                } else {
                    "학습 가능한 실제 배터리 구간이 없었습니다."
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("사용 안 함") { _, _ ->
                logManager.skipArchiveLearning(archive)
                Toast.makeText(this, "이 주행은 배터리 학습에 사용하지 않습니다.", Toast.LENGTH_SHORT).show()
            }
            .setCancelable(false)
            .show()
    }

    private fun renderRideState() {
        val active = logManager.isActive()
        val charging = chargingSessionStore.active()
        btnRideToggle.text = if (active) "■ 주행 종료" else "▶ 주행 시작"
        tvRideMode.text = when {
            logManager.isFreeRide() -> "🚵 임의주행 · GPX 독립 · GPS/배터리 실제값 기록 중"
            active -> "🗺 계획주행 · ${courseMeta.name}"
            else -> "주행 대기 · 시작 버튼에서 계획주행 / 임의주행 선택"
        }
        btnChargeToggle.isEnabled = active
        btnChargeToggle.text = if (charging != null) "⚡ 충전 완료" else "⚡ 충전 시작"
        tvChargeStatus.text = when {
            charging != null && logManager.isFreeRide() -> {
                val min = ((System.currentTimeMillis() - charging.startMs).coerceAtLeast(0L) / 60_000L)
                "충전 중 · 시작 ${charging.arrivalPct.roundToInt()}% · ${RideFormatter.one(charging.routeKm)}km · ${min}분"
            }
            charging != null -> "충전 중 · 실시간 목표 계산 중"
            active -> ""
            else -> "주행 시작 후 충전 기록 가능"
        }
        if (!active) tvGpsStatus.text = if (testMode) "테스트 모드" else "주행 대기"
        renderAssistModeUi()
    }

    private fun renderChargePlannerStatus(routeKm: Double) {
        if (!logManager.isActive() || logManager.isFreeRide()) return
        val activeCharge = chargingSessionStore.active()
        val advice = if (activeCharge != null) {
            val factor = plan.calibration(activeCharge.routeKm)?.factor ?: plan.calibration(routeKm)?.factor ?: 1.0
            tripPlanner.adviceAtStation(activeCharge.routeKm, finishTargetPct, factor, activeCharge.arrivalPct)
        } else {
            tripPlanner.nextChargeAdvice(routeKm, finishTargetPct)
        }
        if (advice == null) {
            if (activeCharge == null) tvChargeStatus.text = ""
            return
        }
        if (activeCharge != null) {
            val currentSoc = (freshBleSoc()?.toDouble() ?: actualStore.latest()?.percent ?: activeCharge.arrivalPct)
            tvChargeStatus.text = tripPlanner.chargingStatusText(advice, currentSoc)
            tvChargeStatus.setTextColor(when {
                !advice.feasibleAt100 -> getColor(R.color.danger)
                advice.userTargetPct + 0.49 < advice.appRecommendedPct && currentSoc + 0.49 >= advice.userTargetPct -> getColor(R.color.warn)
                currentSoc + 0.49 >= advice.appRecommendedPct -> getColor(R.color.good)
                else -> getColor(R.color.accent)
            })
        } else {
            tvChargeStatus.text = buildString {
                append("⚡ ${advice.stationName}: 앱권장 ${advice.appRecommendedPct.roundToInt()}% · 사용자 ${advice.userTargetPct.roundToInt()}%")
                append(" · 권장 ${AvinoxChargeCurve.minutesText(advice.minutesArrivalToRecommended)}")
                if (advice.userTargetPct.roundToInt() != advice.appRecommendedPct.roundToInt()) {
                    append(" / 사용자 ${AvinoxChargeCurve.minutesText(advice.minutesArrivalToUserTarget)}")
                }
                append(" · 소비보정 ${tripPlanner.factorText(routeKm)}")
            }
            tvChargeStatus.setTextColor(when {
                !advice.feasibleAt100 || advice.userTargetPct + 0.49 < advice.appRecommendedPct -> getColor(R.color.warn)
                else -> getColor(R.color.accent)
            })
        }
    }

    private fun selectAssistMode(mode: AvinoxAssistMode) {
        assistProfileStore.setPreferredMode(mode)
        rebuildEnergyModelsForSelectedMode()
        if (logManager.isActive()) {
            val profile = assistProfileStore.get(mode)
            logManager.setAssistMode(profile, currentRideKm(), freshBleSoc()?.toDouble())
            Toast.makeText(this, "${mode.label} 기록 시작 · 12초 BLE probe", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "${mode.label}을 시작 모드로 선택했습니다.", Toast.LENGTH_SHORT).show()
        }
        renderAssistModeUi()
    }

    private fun rebuildEnergyModelsForSelectedMode() {
        if (!::course.isInitialized || !::learningStore.isInitialized || !::chargingStore.isInitialized || !::actualStore.isInitialized) return
        basePlan = BatteryPlan(course, learningStore, chargingStore.list(courseMeta.id))
        plan = AdaptiveBatteryPlan(basePlan, actualStore)
        pacingAdvisor = EnergyPacingAdvisor(course, learningStore)
        tripPlanner = EnergyTripPlanner(basePlan, plan)
    }

    private fun activatePreferredAssistMode() {
        if (!logManager.isActive() || !assistProfileStore.hasPreferredMode()) {
            renderAssistModeUi()
            return
        }
        val mode = assistProfileStore.preferredMode()
        logManager.setAssistMode(assistProfileStore.get(mode), currentRideKm(), freshBleSoc()?.toDouble())
        renderAssistModeUi()
    }

    private fun currentRideKm(): Double = if (logManager.isFreeRide()) latestRouteKm.coerceAtLeast(0.0) else latestRouteKm.coerceIn(0.0, course.totalKm)

    private fun renderAssistModeUi() {
        if (!::assistProfileStore.isInitialized || !::tvAssistModeCurrent.isInitialized) return
        val activeMode = logManager.activeAssistMode()
        val activeConfidence = logManager.activeAssistConfidence()
        val detectionFresh = latestAssistUpdatedMs > 0L && System.currentTimeMillis() - latestAssistUpdatedMs <= 15_000L
        val primary = latestAssistPrimary.takeIf { detectionFresh }
        val alternate = latestAssistAlternate.takeIf { detectionFresh }
        val compatible = activeMode != null && (activeMode == primary || activeMode == alternate)

        when {
            !logManager.isActive() -> {
                tvAssistModeCurrent.text = "Avinox 모드 · 주행 시작 시 자동 감지"
                tvAssistModeCurrent.setTextColor(getColor(R.color.text_primary))
                tvAssistModeHint.text = "4개 수동 버튼 없이 BLE에서 자동으로 읽습니다"
                tvAssistModeHint.setTextColor(getColor(R.color.text_secondary))
                layoutAssistVerify.visibility = View.GONE
            }
            primary == null -> {
                tvAssistModeCurrent.text = "Avinox 모드 · BLE 감지 대기…"
                tvAssistModeCurrent.setTextColor(getColor(R.color.text_primary))
                tvAssistModeHint.text = "배터리와 같은 FFF4 실시간 패킷에서 모드를 찾는 중"
                tvAssistModeHint.setTextColor(getColor(R.color.text_secondary))
                layoutAssistVerify.visibility = View.GONE
            }
            activeConfidence == "CONFIRMED" && compatible -> {
                tvAssistModeCurrent.text = "Avinox 모드 · ${activeMode!!.label}  ✓"
                tvAssistModeCurrent.setTextColor(getColor(R.color.good))
                tvAssistModeHint.text = "사용자 확인됨 · BLE raw ${latestAssistRawCode ?: "-"} · 다르면 위 모드 표시를 탭"
                tvAssistModeHint.setTextColor(getColor(R.color.good))
                layoutAssistVerify.visibility = View.GONE
            }
            latestAssistConfidence == "HIGH" && alternate == null -> {
                tvAssistModeCurrent.text = "Avinox 모드 · ${primary.label}"
                tvAssistModeCurrent.setTextColor(getColor(R.color.good))
                tvAssistModeHint.text = "● BLE 자동감지 · 선택 모드 · 다르면 위 모드 표시를 탭"
                tvAssistModeHint.setTextColor(getColor(R.color.good))
                layoutAssistVerify.visibility = View.GONE
            }
            else -> {
                val altText = alternate?.let { " / ${it.label}" }.orEmpty()
                tvAssistModeCurrent.text = "Avinox 모드 후보 · ${primary.label}$altText"
                tvAssistModeCurrent.setTextColor(getColor(R.color.warn))
                tvAssistModeHint.text = "선택 모드 후보 · raw ${latestAssistRawCode ?: "-"}"
                tvAssistModeHint.setTextColor(getColor(R.color.warn))
                btnAssistModeConfirm.text = "✓ ${primary.label} 맞음"
                layoutAssistVerify.visibility = View.VISIBLE
            }
        }
    }

    private fun confirmDetectedAssistPrimary() {
        val mode = latestAssistPrimary ?: return
        confirmDetectedAssist(mode)
    }

    private fun showDetectedAssistCorrection() {
        if (!logManager.isActive()) return
        val modes = AvinoxAssistMode.values()
        AlertDialog.Builder(this)
            .setTitle("자전거의 실제 Avinox 모드")
            .setMessage("우리 앱의 자동 감지 표시와 다를 때만 실제 자전거 화면의 모드를 선택하세요. 이 확인값은 모드 감지 검증과 클린 학습에 사용됩니다.")
            .setItems(modes.map { it.label }.toTypedArray()) { _, which -> confirmDetectedAssist(modes[which]) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun confirmDetectedAssist(mode: AvinoxAssistMode) {
        logManager.confirmDetectedAssistMode(assistProfileStore.get(mode), currentRideKm(), freshBleSoc()?.toDouble(), latestAssistRawCode)
        assistProfileStore.setPreferredMode(mode)
        lastPlanAssistMode = mode
        rebuildEnergyModelsForSelectedMode()
        Toast.makeText(this, "${mode.label} 확인값 저장 · 이후 자동 감지와 비교", Toast.LENGTH_SHORT).show()
        renderAssistModeUi()
        if (logManager.isFreeRide()) renderFreeRide() else renderAtKm(latestRouteKm, testMode)
    }

    private fun showAssistProfilePicker() {
        val modes = AvinoxAssistMode.values()
        val labels = modes.map { m -> "${m.label}  ·  ${assistProfileStore.get(m).compactText()}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Avinox 모드 설정값")
            .setMessage("사진 기준 초기값을 넣어뒀습니다. 자전거 Avinox 앱에서 값을 바꾸면 여기에도 같은 값으로 저장하세요. 모드별 프로필 ID가 달라져 주행 데이터가 섞이지 않습니다.")
            .setItems(labels) { _, which -> showAssistProfileEditor(modes[which]) }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun showAssistProfileEditor(mode: AvinoxAssistMode) {
        val current = assistProfileStore.get(mode)
        val density = resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).roundToInt()
        fun field(label: String, value: Int?): Pair<TextView, EditText> {
            val title = TextView(this).apply { text = label; textSize = 13f; setTextColor(getColor(R.color.text_primary)); setPadding(0, px(6), 0, 0) }
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(value?.toString().orEmpty())
                hint = "비워두면 미기록"
            }
            return title to input
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(18), px(8), px(18), px(8)) }
        val inputs = linkedMapOf<String, EditText>()
            listOf(
                "assistMin" to field("어시스트 최소 (고정 모드는 같은 값)", current.assistMin),
                "assistMax" to field("어시스트 최대 (고정 모드는 같은 값)", current.assistMax),
                "torque" to field("최대 토크 N·m", current.maxTorqueNm),
                "power" to field("최대 파워 W", current.maxPowerW),
                "overrun" to field("모터 오버런 위치 0~4 (0=최단, 4=최장)", current.motorOverrunStep),
                "start" to field("스타트 어시스트 위치 0~4", current.startAssistStep),
                "continuous" to field("연속 어시스트 위치 0~4", current.continuousAssistStep)
            ).forEach { (key, pair) -> body.addView(pair.first); body.addView(pair.second); inputs[key] = pair.second }
        body.addView(TextView(this).apply {
            text = "※ 오버런/스타트/연속은 Avinox 화면에 숫자가 표시되지 않아 슬라이더 상대 위치(0~4)로 기록합니다. 정확한 물리 단위로 해석하지 않습니다."
            textSize = 11f; setTextColor(getColor(R.color.text_secondary)); setPadding(0, px(8), 0, 0)
        })
        val scroll = ScrollView(this).apply { addView(body) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("${mode.label} 프로필")
            .setView(scroll)
            .setPositiveButton("저장", null)
            .setNeutralButton("사진값 초기화", null)
            .setNegativeButton("취소", null)
            .create()
        dialog.setOnShowListener {
            fun read(key: String): Int? = inputs[key]?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val amin = read("assistMin"); val amax = read("assistMax")
                    require(amin == null || amin in 1..15) { "어시스트 최소는 1~15로 입력하세요." }
                    require(amax == null || amax in 1..15) { "어시스트 최대는 1~15로 입력하세요." }
                    require(amin == null || amax == null || amin <= amax) { "어시스트 최소가 최대보다 클 수 없습니다." }
                    listOf("overrun", "start", "continuous").forEach { key -> read(key)?.let { require(it in 0..4) { "상대 위치는 0~4로 입력하세요." } } }
                    val profile = AvinoxAssistProfile(mode, amin, amax, read("torque"), read("power"), read("overrun"), read("start"), read("continuous"), sourceNote = "사용자 입력")
                    if (profile.maxTorqueNm != null) require(profile.maxTorqueNm in 10..105) { "최대 토크는 10~105 N·m 범위로 입력하세요." }
                    if (profile.maxPowerW != null) require(profile.maxPowerW in 100..1000) { "최대 파워는 100~1000 W 범위로 입력하세요." }
                    assistProfileStore.save(profile)
                    if (logManager.isActive() && logManager.activeAssistMode() == mode) {
                        logManager.setAssistMode(profile, currentRideKm(), freshBleSoc()?.toDouble())
                    }
                    renderAssistModeUi()
                    Toast.makeText(this, "${mode.label} 설정 저장 · ${profile.profileId}", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                } catch (e: Exception) {
                    Toast.makeText(this, e.message ?: "입력값을 확인하세요.", Toast.LENGTH_LONG).show()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                assistProfileStore.reset(mode)
                val reset = assistProfileStore.get(mode)
                if (logManager.isActive() && logManager.activeAssistMode() == mode) {
                    logManager.setAssistMode(reset, currentRideKm(), freshBleSoc()?.toDouble())
                }
                renderAssistModeUi()
                dialog.dismiss()
                Toast.makeText(this, "${mode.label}을 사진 기준 초기값으로 복원했습니다.", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }


    private fun renderCourseQuick() {
        if (!::courseMeta.isInitialized) return
        val elev = if (courseMeta.hasElevation) {
            "▲${courseMeta.totalAscentM.roundToInt()}m · ▼${courseMeta.totalDescentM.roundToInt()}m"
        } else "고도 데이터 없음"
        val learned = learningStore.samples().size
        val source = if (courseMeta.builtIn) "기본 예비 코스" else "가져온 GPX"
        tvCourseQuickSelect.text = "${courseMeta.name}  ▼\n${RideFormatter.one(courseMeta.totalKm)} km · $elev\n$source · 개인 학습 ${learned}개 구간 적용"
        val ref = avinoxReferenceStore.get(courseMeta.id)
        tvAvinoxReferenceSummary.text = if (ref == null) {
            "입력 없음 · 자체 예측만 사용\nAvinox 전체 코스 소비량은 외부 비교용으로만 저장하며 학습/예측에는 섞지 않습니다. 100% 초과 입력 가능."
        } else {
            val selected = ref.selectedMode?.takeIf { ref.value(it) != null }
            val compare = selected?.let { "실시간 비교: ${it.label} ${formatPct(ref.value(it)!!)}" } ?: "실시간 비교 표시 안 함"
            "${ref.compactValues()}\n$compare\n※ Avinox는 외부 benchmark · 자체 예측/개인 학습에 0% 반영"
        }
        val riding = logManager.isActive()
        tvCourseQuickSelect.isEnabled = !riding
        btnCourseImportQuick.isEnabled = !riding
        btnAvinoxReferenceEdit.isEnabled = !riding
    }

    private fun showAvinoxReferenceDialog() {
        if (logManager.isActive()) {
            Toast.makeText(this, "주행 중에는 Avinox 기준을 변경할 수 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val current = avinoxReferenceStore.get(courseMeta.id)
        val density = resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).roundToInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(8), px(18), px(4))
        }
        root.addView(TextView(this).apply {
            text = "Avinox 앱에서 같은 GPX를 분석했을 때 표시된 전체 코스 예상 소비량을 입력하세요.\n100% 초과 입력 가능 · 예: 254% = 배터리 2.54팩 분량\n이 값은 외부 비교용입니다. 자체 예측이나 개인 학습에는 절대 섞이지 않습니다."
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, 0, 0, px(8))
        })

        val inputs = linkedMapOf<AvinoxRideMode, EditText>()
        AvinoxRideMode.values().forEach { mode ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(TextView(this).apply {
                text = mode.label
                textSize = 16f
                setTextColor(getColor(R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, px(52), 0.42f)
                gravity = android.view.Gravity.CENTER_VERTICAL
            })
            val input = EditText(this).apply {
                hint = "% · 100 초과 가능"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(current?.value(mode)?.let { cleanPctText(it) }.orEmpty())
                textSize = 17f
                layoutParams = LinearLayout.LayoutParams(0, px(52), 0.58f)
            }
            inputs[mode] = input
            row.addView(input)
            root.addView(row)
        }

        root.addView(TextView(this).apply {
            text = "실시간 화면에서 비교할 모드"
            textSize = 14f
            setTextColor(getColor(R.color.text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, px(8), 0, px(2))
        })
        val radioGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val compareOnlyId = View.generateViewId()
        radioGroup.addView(RadioButton(this).apply {
            id = compareOnlyId
            text = "비교 표시 안 함"
            textSize = 13f
        }, RadioGroup.LayoutParams(RadioGroup.LayoutParams.MATCH_PARENT, px(42)))
        val modesRow = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val radioIds = linkedMapOf<AvinoxRideMode, Int>()
        AvinoxRideMode.values().forEach { mode ->
            val id = View.generateViewId()
            radioIds[mode] = id
            modesRow.addView(RadioButton(this).apply {
                this.id = id
                text = mode.label
                textSize = 12f
            }, RadioGroup.LayoutParams(0, px(44), 1f))
        }
        // 안드로이드 RadioGroup은 중첩 그룹끼리 단일선택을 공유하지 않으므로 직접 동기화한다.
        modesRow.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId != -1) radioGroup.check(-1)
        }
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == compareOnlyId) modesRow.check(-1)
        }
        if (current?.selectedMode == null) radioGroup.check(compareOnlyId)
        else radioIds[current.selectedMode]?.let { modesRow.check(it) }
        root.addView(radioGroup)
        root.addView(modesRow)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Avinox 전체 코스 예상 소비량")
            .setView(root)
            .setPositiveButton("저장", null)
            .setNegativeButton("취소", null)
            .setNeutralButton("기준 삭제", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                fun read(mode: AvinoxRideMode): Double? {
                    val text = inputs.getValue(mode).text.toString().trim()
                    if (text.isBlank()) return null
                    val value = text.toDoubleOrNull()
                    if (value == null || !value.isFinite() || value < 0.1) throw IllegalArgumentException("${mode.label} 값은 0.1% 이상의 숫자로 입력하세요. 100% 초과도 가능합니다.")
                    return value
                }
                try {
                    val values = AvinoxRideMode.values().associateWith(::read)
                    if (values.values.all { it == null }) {
                        Toast.makeText(this, "최소 한 모드의 전체 코스 예상 소비량을 입력하세요.", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val selected = radioIds.entries.firstOrNull { it.value == modesRow.checkedRadioButtonId }?.key
                    if (selected != null && values[selected] == null) {
                        Toast.makeText(this, "선택한 모드의 소비율을 먼저 입력하세요.", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    avinoxReferenceStore.save(
                        courseId = courseMeta.id,
                        ecoPct = values[AvinoxRideMode.ECO],
                        autoPct = values[AvinoxRideMode.AUTO],
                        trailPct = values[AvinoxRideMode.TRAIL],
                        turboPct = values[AvinoxRideMode.TURBO],
                        selectedMode = selected
                    )
                    rebuildPlanFromCurrentCourse()
                    renderCourseQuick()
                    renderAtKm(latestRouteKm, testMode)
                    dialog.dismiss()
                    val savedMsg = if (selected == null) "Avinox 예상값을 외부 비교 데이터로 저장했습니다." else "Avinox ${selected.label}을 실시간 비교 모드로 설정했습니다. 자체 예측에는 반영하지 않습니다."
                    Toast.makeText(this, savedMsg, Toast.LENGTH_LONG).show()
                } catch (e: IllegalArgumentException) {
                    Toast.makeText(this, e.message ?: "입력값을 확인하세요.", Toast.LENGTH_LONG).show()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                avinoxReferenceStore.clear(courseMeta.id)
                rebuildPlanFromCurrentCourse()
                renderCourseQuick()
                renderAtKm(latestRouteKm, testMode)
                dialog.dismiss()
                Toast.makeText(this, "이 코스의 Avinox 외부 기준을 삭제했습니다.", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun rebuildPlanFromCurrentCourse() {
        basePlan = BatteryPlan(course, learningStore, chargingStore.list(courseMeta.id))
        plan = AdaptiveBatteryPlan(basePlan, actualStore)
        pacingAdvisor = EnergyPacingAdvisor(course, learningStore)
    }

    private fun cleanPctText(value: Double): String {
        val rounded = kotlin.math.round(value)
        return if (abs(value - rounded) < 0.05) rounded.toInt().toString() else String.format(Locale.US, "%.1f", value)
    }

    private fun formatPct(value: Double): String = cleanPctText(value) + "%"

    private fun showCoursePickerQuick() {
        if (logManager.isActive()) {
            Toast.makeText(this, "주행 중에는 코스를 변경할 수 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val courses = courseRepo.listCourses()
        if (courses.isEmpty()) return
        val activeId = courseRepo.activeMeta().id
        val labels = courses.map { meta ->
            val selected = if (meta.id == activeId) "✓ " else ""
            val source = if (meta.builtIn) " · 기본" else " · GPX"
            val elev = if (meta.hasElevation) " · ▲${meta.totalAscentM.roundToInt()}m" else ""
            "$selected${meta.name}$source\n${RideFormatter.one(meta.totalKm)} km$elev"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("주행 코스 선택")
            .setMessage("선택한 GPX에 현재 개인 학습 데이터를 적용해 배터리 예측과 어시스트를 다시 계산합니다.")
            .setItems(labels) { _, which ->
                val chosen = courses[which]
                if (chosen.id != activeId) selectCourseQuick(chosen)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun selectCourseQuick(meta: CourseMeta) {
        if (logManager.isActive()) return
        courseRepo.setActive(meta.id)
        actualStore.clear()
        chargingSessionStore.clear()
        if (!loadSelectedCourse(resetProgress = true)) return
        applySettings()
        renderCourseQuick()
        refreshInlineSettings()
        renderCurrentMode()
        val learned = learningStore.samples().size
        Toast.makeText(this, "${meta.name} 선택 · 개인 학습 ${learned}개 구간 적용", Toast.LENGTH_LONG).show()
    }

    private fun importGpxQuick() {
        if (logManager.isActive()) {
            Toast.makeText(this, "주행 종료 후 GPX를 변경해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream"))
        }
        try {
            startActivityForResult(intent, REQ_GPX_IMPORT)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "파일 선택 앱을 찾지 못했습니다.", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleImportedGpx(uri: android.net.Uri) {
        if (logManager.isActive()) return
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            val meta = courseRepo.importGpx(uri, displayName(uri))
            actualStore.clear()
            chargingSessionStore.clear()
            if (!loadSelectedCourse(resetProgress = true)) return
            applySettings()
            renderCourseQuick()
            refreshInlineSettings()
            renderCurrentMode()
            showPagerChild(0)
            Toast.makeText(this, "GPX 선택 완료 · ${meta.name}\n개인 학습 데이터를 이 코스에 적용합니다.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "GPX 가져오기 실패: ${e.message ?: "파일을 확인해 주세요."}", Toast.LENGTH_LONG).show()
        }
    }

    private fun displayName(uri: android.net.Uri): String? {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    private fun setupInlineSettings() {
        seekPageDistanceInterval.max = 50
        seekPageTimeInterval.max = 120
        seekPageFinishTarget.max = 98

        switchPageVoice.setOnCheckedChangeListener { _, checked ->
            if (refreshingSettingsUi) return@setOnCheckedChangeListener
            AppSettings.prefs(this).edit().putBoolean(AppSettings.KEY_VOICE, checked).apply()
            sendVoiceSettingsToService()
        }
        switchPageKeepScreen.setOnCheckedChangeListener { _, checked ->
            if (refreshingSettingsUi) return@setOnCheckedChangeListener
            AppSettings.prefs(this).edit().putBoolean(AppSettings.KEY_KEEP_SCREEN_ON, checked).apply()
            applyKeepScreenOn(checked)
        }
        seekPageDistanceInterval.setOnSeekBarChangeListener(simpleSeekListener { value ->
            if (refreshingSettingsUi) return@simpleSeekListener
            AppSettings.prefs(this).edit().putInt(AppSettings.KEY_ANNOUNCE_DISTANCE_KM, value).apply()
            updateInlineSettingsLabels()
            sendVoiceSettingsToService()
        })
        seekPageTimeInterval.setOnSeekBarChangeListener(simpleSeekListener { value ->
            if (refreshingSettingsUi) return@simpleSeekListener
            AppSettings.prefs(this).edit().putInt(AppSettings.KEY_ANNOUNCE_TIME_MIN, value).apply()
            updateInlineSettingsLabels()
            sendVoiceSettingsToService()
        })
        seekPageFinishTarget.setOnSeekBarChangeListener(simpleSeekListener { value ->
            if (refreshingSettingsUi) return@simpleSeekListener
            val pct = (value + 1).coerceIn(1, 99)
            AppSettings.prefs(this).edit().putInt(AppSettings.KEY_FINISH_TARGET, pct).apply()
            finishTargetPct = pct.toDouble()
            updateInlineSettingsLabels()
            if (::plan.isInitialized) renderAtKm(latestRouteKm, testMode)
        })
        switchPageTestMode.setOnCheckedChangeListener { _, checked ->
            if (refreshingSettingsUi) return@setOnCheckedChangeListener
            if (logManager.isActive()) {
                refreshInlineSettings()
                return@setOnCheckedChangeListener
            }
            AppSettings.prefs(this).edit().putBoolean(AppSettings.KEY_TEST_MODE, checked).apply()
            testMode = checked
            refreshInlineSettings()
            renderCurrentMode()
        }
        seekPageTestKm.setOnSeekBarChangeListener(simpleSeekListener { value ->
            if (refreshingSettingsUi || !switchPageTestMode.isChecked || !::course.isInitialized) return@simpleSeekListener
            val km = (value / 10.0).coerceIn(0.0, course.totalKm)
            AppSettings.prefs(this).edit().putFloat(AppSettings.KEY_TEST_KM, km.toFloat()).apply()
            updateInlineSettingsLabels()
            if (testMode) renderCurrentMode()
        })
        btnPageBleDiagnostic.setOnClickListener {
            startActivity(Intent(this, BleDiagnosticActivity::class.java))
        }
        switchPageBetaUpdates.isChecked = AppSettings.betaUpdates(this)
        switchPageBetaUpdates.setOnCheckedChangeListener { _, checked ->
            AppSettings.prefs(this).edit().putBoolean(AppSettings.KEY_BETA_UPDATES, checked).apply()
            refreshInlineUpdateStatus()
        }
        btnPageCheckUpdate.setOnClickListener { checkForUpdateInline() }
        refreshInlineUpdateStatus()
        btnPageResetProgress.setOnClickListener { resetProgressQuick() }
    }

    private fun refreshInlineUpdateStatus(extra: String? = null) {
        val channel = if (AppSettings.betaUpdates(this)) "테스트판 포함" else "안정판"
        val repo = UpdateManager.repository()
        tvPageUpdateStatus.text = buildString {
            append("현재 v${UpdateManager.currentVersion(this@MainActivity)} · $channel")
            if (repo.isNotBlank()) append(" · $repo")
            if (!extra.isNullOrBlank()) append("\n$extra")
        }
    }

    private fun checkForUpdateInline() {
        btnPageCheckUpdate.isEnabled = false
        refreshInlineUpdateStatus("GitHub에서 최신 릴리스를 확인 중…")
        UpdateManager.checkAsync(this) { result ->
            btnPageCheckUpdate.isEnabled = true
            result.onSuccess { info ->
                if (info == null) {
                    refreshInlineUpdateStatus("최신 버전입니다.")
                } else {
                    refreshInlineUpdateStatus("새 버전 v${info.versionName} 사용 가능")
                    UpdateManager.showUpdateDialog(this, info)
                }
            }.onFailure { e ->
                refreshInlineUpdateStatus("업데이트 확인 실패 · ${e.message ?: "네트워크/설정을 확인하세요"}")
            }
        }
    }

    private fun simpleSeekListener(onChanged: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = onChanged(progress)
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    private fun refreshInlineSettings() {
        if (!::course.isInitialized) return
        refreshingSettingsUi = true
        try {
            switchPageVoice.isChecked = AppSettings.voiceEnabled(this)
            switchPageKeepScreen.isChecked = AppSettings.keepScreenOn(this)
            seekPageDistanceInterval.progress = AppSettings.distanceIntervalKm(this)
            seekPageTimeInterval.progress = AppSettings.timeIntervalMin(this)
            seekPageFinishTarget.progress = AppSettings.finishTarget(this).roundToInt().coerceIn(1, 99) - 1
            switchPageTestMode.isChecked = AppSettings.testMode(this)
            seekPageTestKm.max = (course.totalKm * 10.0).roundToInt().coerceAtLeast(1)
            seekPageTestKm.progress = (AppSettings.testKm(this).coerceIn(0.0, course.totalKm) * 10.0).roundToInt()
            switchPageTestMode.isEnabled = !logManager.isActive()
            seekPageTestKm.isEnabled = switchPageTestMode.isChecked && !logManager.isActive()
            btnPageResetProgress.isEnabled = !logManager.isActive()
            tvPageSettingsHint.text = if (logManager.isActive()) {
                "주행 중에는 테스트 모드를 변경할 수 없습니다. 음성 안내와 종점 목표는 즉시 반영됩니다."
            } else {
                "선택 코스 · ${courseMeta.name} · 테스트 위치와 모든 예측은 이 GPX 기준으로 계산됩니다."
            }
            updateInlineSettingsLabels()
        } finally {
            refreshingSettingsUi = false
        }
    }

    private fun updateInlineSettingsLabels() {
        val d = seekPageDistanceInterval.progress
        tvPageDistanceInterval.text = if (d == 0) "거리 기준 안내 · 사용 안 함" else "거리 기준 안내 · ${d} km마다"
        val t = seekPageTimeInterval.progress
        tvPageTimeInterval.text = if (t == 0) "시간 기준 안내 · 사용 안 함" else "시간 기준 안내 · ${t}분마다"
        tvPageFinishTarget.text = "종점 목표 잔량 ${seekPageFinishTarget.progress + 1}%"
        val km = (seekPageTestKm.progress / 10.0).coerceIn(0.0, if (::course.isInitialized) course.totalKm else 0.0)
        tvPageTestKm.text = if (::course.isInitialized) "테스트 위치 ${RideFormatter.one(km)} / ${RideFormatter.one(course.totalKm)} km" else "테스트 위치"
    }


    private fun resetProgressQuick() {
        if (logManager.isActive()) {
            Toast.makeText(this, "주행 기록 중에는 진행 위치를 초기화할 수 없습니다.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("현재 코스 진행 위치 초기화")
            .setMessage("${courseMeta.name}의 진행 위치와 실제 배터리 보정값을 0km 상태로 초기화할까요? 저장된 학습 데이터와 과거 주행 로그는 삭제하지 않습니다.")
            .setPositiveButton("초기화") { _, _ ->
                actualStore.clear()
                chargingSessionStore.clear()
                AppSettings.prefs(this).edit()
                    .putFloat(AppSettings.KEY_LAST_KM, 0f)
                    .putFloat(AppSettings.KEY_TEST_KM, 0f)
                    .apply()
                latestRouteKm = 0.0
                refreshInlineSettings()
                renderCurrentMode()
                Toast.makeText(this, "진행 위치를 초기화했습니다.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun setupLearningPage() {
        btnLearningFit.setOnClickListener { openHistoricalLearning(HistoricalSourceType.FIT) }
        btnLearningGpx.setOnClickListener { openHistoricalLearning(HistoricalSourceType.GPX) }
        btnLearningManage.setOnClickListener { openHistoricalLearning(null) }
        btnLearningClear.setOnClickListener { confirmClearLearningQuick() }
    }

    private fun openHistoricalLearning(type: HistoricalSourceType?) {
        if (logManager.isActive()) {
            Toast.makeText(this, "주행 종료 후 과거 라이딩 학습을 관리해 주세요.", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(this, HistoricalRideActivity::class.java)
        type?.let { intent.putExtra(HistoricalRideActivity.EXTRA_AUTO_PICK_TYPE, it.name) }
        startActivity(intent)
    }

    private fun refreshLearningPage() {
        if (!::learningStore.isInitialized) return
        val samples = learningStore.samples()
        val rides = HistoricalRideStore(this).records()
        tvLearningPageSummary.text = if (samples.isEmpty()) {
            "학습 데이터 0개 · 중립 초기 모델 사용 중\n\nFIT/GPX를 학습하면 이후 선택하는 모든 GPX 코스의 거리·고도·지형을 개인 소비 특성으로 보정합니다."
        } else {
            "학습 라이딩 ${rides.size}개 · 학습 구간 ${samples.size}개\n${learningStore.summaryText()}\n\n현재 선택 코스: ${if (::courseMeta.isInitialized) courseMeta.name else "-"}"
        }
        val enabled = !logManager.isActive()
        btnLearningFit.isEnabled = enabled
        btnLearningGpx.isEnabled = enabled
        btnLearningManage.isEnabled = enabled
        btnLearningClear.isEnabled = enabled && samples.isNotEmpty()
    }

    private fun confirmClearLearningQuick() {
        if (logManager.isActive()) return
        AlertDialog.Builder(this)
            .setTitle("개인 학습 데이터 초기화")
            .setMessage("FIT/GPX와 실제 주행에서 만든 개인 배터리 학습 데이터를 모두 삭제할까요? 코스 GPX와 주행 로그는 삭제하지 않습니다.")
            .setPositiveButton("학습 데이터 삭제") { _, _ ->
                learningStore.clear()
                HistoricalRideStore(this).clear()
                HistoricalRideDataStore(this).clearAll()
                if (::course.isInitialized) {
                    basePlan = BatteryPlan(course, learningStore, chargingStore.list(courseMeta.id))
                    plan = AdaptiveBatteryPlan(basePlan, actualStore)
                    pacingAdvisor = EnergyPacingAdvisor(course, learningStore)
                    renderAtKm(latestRouteKm, testMode)
                }
                refreshLearningPage()
                renderCourseQuick()
                Toast.makeText(this, "개인 학습 데이터를 초기화했습니다.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun loadBleSnapshot() {
        if (!::bleStateStore.isInitialized) return
        val snap = bleStateStore.snapshot()
        latestBleSoc = snap.soc
        latestBleState = snap.state
        latestBleUpdatedMs = snap.updatedMs
    }

    private fun freshBleSoc(maxAgeMs: Long = 30_000L): Int? {
        val soc = latestBleSoc ?: return null
        val age = System.currentTimeMillis() - latestBleUpdatedMs
        return soc.takeIf { latestBleUpdatedMs > 0L && age in 0..maxAgeMs }
    }

    private fun renderBleStatusLine() {
        val fresh = freshBleSoc()
        // v0.18.2부터 별도 'Avinox 실제 배터리' 카드는 숨기고 상단 큰 배터리 카드로 통합한다.
        tvActualDetail.visibility = View.GONE
        tvActualBattery.visibility = View.GONE
        btnManualBattery.visibility = if (logManager.isActive() && fresh == null && !testMode) View.VISIBLE else View.GONE
    }

    private fun showManualBatteryDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "배터리 %"
            setText((freshBleSoc() ?: actualStore.latest()?.percent?.roundToInt())?.toString().orEmpty())
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("비상 수동 배터리 입력")
            .setMessage("Avinox BLE가 연결되지 않을 때만 사용하세요. BLE가 복구되면 자동값이 다시 우선됩니다.")
            .setView(input)
            .setPositiveButton("저장") { _, _ ->
                val pct = input.text.toString().trim().toIntOrNull()
                if (pct == null || pct !in 0..100) {
                    Toast.makeText(this, "0~100 사이 배터리 값을 입력해 주세요.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val km = if (logManager.isFreeRide()) latestRouteKm.coerceAtLeast(0.0) else latestRouteKm.coerceIn(0.0, course.totalKm)
                actualStore.save(pct.toDouble(), km, ActualEntryKind.RIDING, System.currentTimeMillis(), ActualEntrySource.MANUAL)
                if (logManager.isActive()) logManager.recordEvent("BATTERY_MANUAL", "$pct% · BLE fallback", km, pct.toDouble())
                if (logManager.isFreeRide()) renderFreeRide() else renderAtKm(latestRouteKm, testMode)
                Toast.makeText(this, "수동 배터리 $pct% 저장", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
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
        // 배터리 관측 위치는 음성 인식 결과가 돌아온 시점이 아니라 사용자가 입력을 시작한 시점에 고정한다.
        voiceInputStartedMs = System.currentTimeMillis()
        voiceInputRouteKm = if (logManager.isFreeRide()) latestRouteKm.coerceAtLeast(0.0) else latestRouteKm.coerceIn(0.0, course.totalKm)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PROMPT, if (logManager.isFreeRide()) "임의주행 · 배터리 48프로야 · 현재 상태 · 주행 종료" else "자연스럽게 말하세요 · 배터리 48프로야 · 종점 목표 20 · 5킬로마다 알려줘 · 앞에 업힐 있어?")
        }
        try { startActivityForResult(intent, REQ_SPEECH) }
        catch (_: ActivityNotFoundException) {
            voiceInputStartedMs = 0L
            Toast.makeText(this, "음성 인식 앱을 찾지 못했습니다.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Android, retained for minSdk 26 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_POST_RIDE_FIT) {
            if (resultCode == RESULT_OK) data?.data?.let { uri ->
                try {
                    val msg = logManager.attachFitToLastRide(uri, learningStore)
                    AlertDialog.Builder(this).setTitle("FIT 연결 완료").setMessage(msg + "\n\n" + logManager.lastComparisonText()).setPositiveButton("확인", null).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "FIT 연결 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        if (requestCode == REQ_GPX_IMPORT) {
            if (resultCode == RESULT_OK) data?.data?.let { handleImportedGpx(it) }
            return
        }
        if (requestCode != REQ_SPEECH) return
        if (resultCode != RESULT_OK) {
            voiceInputStartedMs = 0L
            return
        }
        val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty()
        val parsed = results.map { VoiceCommandParser.parse(it) to it }.firstOrNull { it.first !is VoiceCommand.Unknown }
        if (parsed == null) {
            voiceInputStartedMs = 0L
            Toast.makeText(this, "명령을 못 알아들었습니다. 다시 말해주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        handleVoiceCommand(parsed.first, parsed.second)
        voiceInputStartedMs = 0L
    }

    private fun handleVoiceCommand(command: VoiceCommand, heardText: String) {
        if (logManager.isFreeRide()) {
            when (command) {
                is VoiceCommand.Battery -> saveActualBattery(command.percent, heardText)
                VoiceCommand.Repeat, VoiceCommand.CurrentStatus -> speakCurrentSummary()
                VoiceCommand.UndoActual -> undoActual()
                VoiceCommand.RideStop -> confirmEndRide()
                is VoiceCommand.SetVoiceEnabled -> {
                    AppSettings.prefs(this).edit().putBoolean(AppSettings.KEY_VOICE, command.enabled).apply()
                    sendVoiceSettingsToService()
                    Toast.makeText(this, if (command.enabled) "음성 안내를 켰습니다." else "음성 안내를 껐습니다.", Toast.LENGTH_SHORT).show()
                }
                VoiceCommand.Help -> speakText("임의주행에서는 현재 상태, 주행 종료처럼 말할 수 있습니다. 배터리 잔량은 Avinox BLE에서 자동으로 읽습니다.")
                else -> speakText("임의주행에서는 GPX 종점이나 업힐 명령을 사용하지 않습니다. 배터리 입력과 현재 상태 확인은 사용할 수 있습니다.")
            }
            return
        }
        when (command) {
            is VoiceCommand.Battery -> saveActualBattery(command.percent, heardText)
            is VoiceCommand.FinishTarget -> {
                finishTargetPct = command.percent.toDouble().coerceIn(1.0, 99.0)
                AppSettings.prefs(this).edit().putInt(AppSettings.KEY_FINISH_TARGET, finishTargetPct.roundToInt()).apply()
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
            VoiceCommand.RideStart -> showRideStartModeDialog()
            VoiceCommand.RideStop -> confirmEndRide()
            VoiceCommand.AddSupplyPoint -> addCurrentSupplyPoint()
            VoiceCommand.Help -> speakVoiceHelp()
            VoiceCommand.Unknown -> Unit
        }
    }

    private fun saveActualBattery(percent: Int, heardText: String) {
        val pct = percent.coerceIn(0, 100)
        val km = if (voiceInputStartedMs > 0L) voiceInputRouteKm else if (logManager.isFreeRide()) latestRouteKm.coerceAtLeast(0.0) else latestRouteKm.coerceIn(0.0, course.totalKm)
        val now = voiceInputStartedMs.takeIf { it > 0L } ?: System.currentTimeMillis()
        val result = actualStore.saveRidingReplacingRecent(pct.toDouble(), km, now, 10_000L)
        if (logManager.isActive()) {
            result.replaced?.let { old ->
                logManager.recordEvent(
                    "BATTERY_REPLACED",
                    "${old.percent.roundToInt()}% 입력 무효 → $pct%로 수정",
                    km,
                    pct.toDouble()
                )
            }
            logManager.recordEvent("BATTERY", "$pct% · RIDING", km, pct.toDouble())
        }
        // 값은 발화 시작 위치에 기록하되 화면/예측은 인식 결과가 돌아온 현재 위치를 유지한다.
        if (logManager.isFreeRide()) renderFreeRide() else renderAtKm(latestRouteKm, testMode)
        if (logManager.isFreeRide()) {
            val consumed = cumulativeActualConsumption(actualStore.entries())
            if (result.replaced != null) Toast.makeText(this, "${result.replaced.percent.roundToInt()}% 입력을 취소하고 $pct%로 수정했습니다.", Toast.LENGTH_SHORT).show()
            speakText("배터리 ${pct}퍼센트 저장했습니다.${consumed?.let { " 누적 소비 약 ${it.roundToInt()}퍼센트." }.orEmpty()}")
            return
        }
        val reserve = plan.reserveStatus(latestRouteKm, finishTargetPct)
        if (result.replaced != null) {
            Toast.makeText(this, "${result.replaced.percent.roundToInt()}% 입력을 취소하고 $pct%로 수정했습니다.", Toast.LENGTH_SHORT).show()
        }
        speakText("배터리 ${pct}퍼센트로 반영했습니다. ${reserve.targetName} 예상 ${reserve.predictedPct.roundToInt()}퍼센트, 상태 ${reserve.label}.")
    }

    private fun toggleCharging() {
        if (!logManager.isActive()) {
            Toast.makeText(this, "주행을 시작한 뒤 충전 이벤트를 기록할 수 있습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val active = chargingSessionStore.active()
        if (active == null) showChargeBatteryDialog(isStart = true) else showChargeBatteryDialog(isStart = false)
    }

    private fun showChargeBatteryDialog(isStart: Boolean) {
        val active = chargingSessionStore.active()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = if (isStart) "충전 전 배터리 %" else "충전 후 배터리 %"
            setText((freshBleSoc() ?: actualStore.latest()?.percent?.roundToInt())?.toString().orEmpty())
            selectAll()
        }
        val title = if (isStart) "충전 시작" else "충전 완료"
        val message = if (isStart) {
            if (freshBleSoc() != null) "Avinox BLE 현재값을 자동으로 넣었습니다. 확인하면 충전 시작값으로 저장합니다." else "BLE 값이 없어 수동으로 현재 배터리 잔량을 입력하세요."
        } else {
            if (freshBleSoc() != null) "Avinox BLE 현재값을 자동으로 넣었습니다. 확인하면 충전 완료값으로 저장합니다." else "BLE 값이 없어 수동으로 충전 후 배터리 잔량을 입력하세요."
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(input)
            .setPositiveButton("확인", null)
            .setNegativeButton("취소", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pct = input.text.toString().trim().toIntOrNull()
                if (pct == null || pct !in 0..100) {
                    Toast.makeText(this, "배터리 값을 0~100 사이로 입력해 주세요.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!isStart && active != null && pct < active.arrivalPct.roundToInt()) {
                    AlertDialog.Builder(this)
                        .setTitle("배터리 값 확인")
                        .setMessage("충전 후 ${pct}%가 충전 전 ${active.arrivalPct.roundToInt()}%보다 낮습니다. 이 값이 맞나요?")
                        .setPositiveButton("맞음") { _, _ ->
                            finishCharge(active, pct)
                            dialog.dismiss()
                        }
                        .setNegativeButton("다시 입력", null)
                        .show()
                    return@setOnClickListener
                }
                if (isStart) startCharge(pct) else if (active != null) finishCharge(active, pct)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun startCharge(pct: Int) {
        val km = if (logManager.isFreeRide()) latestRouteKm.coerceAtLeast(0.0) else latestRouteKm.coerceIn(0.0, course.totalKm)
        val now = System.currentTimeMillis()
        actualStore.save(pct.toDouble(), km, ActualEntryKind.ARRIVAL, now, ActualEntrySource.CHARGE)
        chargingSessionStore.start(km, pct.toDouble(), now)
        if (logManager.isActive()) {
            val advice = if (!logManager.isFreeRide()) tripPlanner.adviceAtStation(km, finishTargetPct, plan.calibration(km)?.factor ?: 1.0, pct.toDouble()) else null
            val detail = advice?.let { " · 앱권장 ${it.appRecommendedPct.roundToInt()}% · 사용자 ${it.userTargetPct.roundToInt()}%" }.orEmpty()
            logManager.recordEvent("CHARGE_START", "충전 시작 · $pct%$detail", km, pct.toDouble())
        }
        renderRideState()
        if (logManager.isFreeRide()) renderFreeRide() else renderAtKm(km, testMode)
        Toast.makeText(this, "충전 시작 · $pct%", Toast.LENGTH_SHORT).show()
    }

    private fun finishCharge(active: ActiveChargeSession, pct: Int) {
        val now = System.currentTimeMillis()
        // 충전 전/후는 동일한 코스 km에 고정해 소비 구간이 충전시간/GPS 드리프트에 오염되지 않게 한다.
        actualStore.save(pct.toDouble(), active.routeKm, ActualEntryKind.POST_CHARGE, now, ActualEntrySource.CHARGE)
        if (logManager.isActive()) {
            logManager.recordEvent(
                "CHARGE_COMPLETE",
                "충전 완료 · ${active.arrivalPct.roundToInt()}% → $pct% · 실제 ${((now - active.startMs).coerceAtLeast(0L) / 60_000L)}분",
                active.routeKm,
                pct.toDouble()
            )
        }
        chargingSessionStore.clear()
        renderRideState()
        if (logManager.isFreeRide()) renderFreeRide() else renderAtKm(latestRouteKm, testMode)
        Toast.makeText(this, "충전 완료 · $pct%", Toast.LENGTH_SHORT).show()
    }

    private fun undoActual() {
        val removed = actualStore.undoLast()
        if (removed == null) {
            Toast.makeText(this, "취소할 실제 배터리 입력이 없습니다.", Toast.LENGTH_SHORT).show()
        } else {
            if (logManager.isActive()) logManager.recordEvent("BATTERY_UNDO", "마지막 배터리 입력 취소", latestRouteKm, null)
            if (logManager.isFreeRide()) renderFreeRide() else renderAtKm(latestRouteKm, testMode)
        }
    }

    private fun setupSwipePager() {
        pagerFlipper.displayedChild = 0
        updatePagerIndicator()
        pagerGesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                if (abs(dx) < 90f || abs(dx) < abs(dy) * 1.25f || abs(velocityX) < 250f) return false
                if (dx < 0) showPagerChild((pagerFlipper.displayedChild + 1).coerceAtMost(4))
                else showPagerChild((pagerFlipper.displayedChild - 1).coerceAtLeast(0))
                return true
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val settingsSliderTouch = ::pagerFlipper.isInitialized && pagerFlipper.displayedChild == 2 && listOf(
            seekPageDistanceInterval, seekPageTimeInterval, seekPageFinishTarget, seekPageTestKm
        ).any { isTouchInside(ev, it) }
        if (::pagerGesture.isInitialized && !settingsSliderTouch) pagerGesture.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun isTouchInside(ev: MotionEvent, view: View): Boolean {
        if (!view.isShown) return false
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return ev.rawX >= loc[0] && ev.rawX <= loc[0] + view.width &&
            ev.rawY >= loc[1] && ev.rawY <= loc[1] + view.height
    }

    private fun showPagerChild(index: Int) {
        val target = index.coerceIn(0, 4)
        if (target == pagerFlipper.displayedChild) return
        pagerFlipper.displayedChild = target
        updatePagerIndicator()
    }

    private fun updatePagerIndicator() {
        val labels = arrayOf("주행", "코스", "설정", "학습", "피드백")
        val dots = (0..4).joinToString("  ") { if (it == pagerFlipper.displayedChild) "●" else "○" }
        tvPagerIndicator.text = "$dots   ${labels[pagerFlipper.displayedChild]}"
    }

    private fun addCurrentSupplyPoint() {
        if (logManager.isFreeRide()) return speakText("임의주행에서는 GPX 보급소를 등록하지 않습니다.")
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
        if (logManager.isFreeRide()) {
            val actual = actualStore.latest()?.percent
            val consumed = cumulativeActualConsumption(actualStore.entries())
            val bat = actual?.let { "현재 배터리 ${it.roundToInt()}퍼센트." } ?: "Avinox BLE 배터리 수신 전입니다."
            val use = consumed?.let { " 누적 소비 약 ${it.roundToInt()}퍼센트." }.orEmpty()
            return speakText("임의주행 ${RideFormatter.one(latestRouteKm)}킬로미터. 상승 약 ${latestFreeAscentM.roundToInt()}미터. $bat$use")
        }
        val km = latestRouteKm
        val battery = plan.estimate(km)
        val reserve = plan.reserveStatus(km, finishTargetPct)
        val stats = course.elevationAhead(km, 10.0)
        val cp = plan.currentOrNextCheckpoint(km)
        val cpText = cp?.takeIf { it.km < course.totalKm - 0.05 }?.let {
            " 다음 ${it.name}까지 ${RideFormatter.one((it.km - km).coerceAtLeast(0.0))}킬로미터."
        }.orEmpty()
        val elevText = if (course.hasElevation) "앞으로 10킬로미터 상승 ${stats.ascentM.roundToInt()}미터." else "GPX에 고도 데이터가 없습니다."
        val pacing = pacingAdvisor.advice(km, latestSpeedKmh, reserve)
        val chargeAdvice = tripPlanner.nextChargeAdvice(km, finishTargetPct)
        val chargeText = chargeAdvice?.let {
            " 다음 충전소 앱 권장 ${it.appRecommendedPct.roundToInt()}퍼센트, 사용자 목표 ${it.userTargetPct.roundToInt()}퍼센트."
        }.orEmpty()
        speakText("현재 ${RideFormatter.one(km)}킬로미터. 예상 배터리 ${battery.percent.roundToInt()}퍼센트. 상태 ${reserve.label}. 종점 예상 ${plan.forecast(km, course.totalKm).percent.roundToInt()}퍼센트. 목표 ${finishTargetPct.roundToInt()}퍼센트. $elevText$cpText$chargeText ${pacing.voiceText}")
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
        val reserve = plan.reserveStatus(latestRouteKm, finishTargetPct)
        val pacing = pacingAdvisor.adviceForKm(climb.startKm, reserve)
        speakText("${if (remain <= 0.2) "현재 주요 업힐입니다." else "약 ${RideFormatter.one(remain)}킬로미터 후 주요 업힐입니다."} 길이 ${RideFormatter.one(climb.distanceKm)}킬로미터, 상승 ${climb.ascentM.roundToInt()}미터, 평균 경사 ${String.format(Locale.US, "%.1f", climb.averageGradePct)}퍼센트입니다. ${pacing.voiceText}")
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scan = checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val connect = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (!scan || !connect) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), REQ_BLUETOOTH)
                return
            }
        }
        startRideService()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_LOCATION -> if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) ensurePermissionsAndStart() else tvGpsStatus.text = "위치 권한이 필요합니다 · 설정에서 테스트 모드는 사용 가능"
            REQ_NOTIFICATIONS -> ensurePermissionsAndStart()
            REQ_BLUETOOTH -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) ensurePermissionsAndStart()
                else {
                    latestBleState = "BLE 권한 없음 · 수동 입력 사용"
                    startRideService()
                }
            }
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
        tvGpsStatus.text = "GPS + Avinox BLE 자동 기록 중 · 화면을 꺼도 유지"
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

    private fun renderEnergyComparison(km: Double) {
        val ref = avinoxReferenceStore.get(courseMeta.id)
        val snapshot = EnergyComparisonCalculator.snapshot(
            routeKm = km,
            course = course,
            base = basePlan,
            adaptive = plan,
            actualEntries = actualStore.entries(),
            avinoxReference = ref
        )
        tvCompareActual.text = snapshot.actualConsumedPct?.let(::formatPct) ?: "—"
        tvCompareModel.text = formatPct(snapshot.modelConsumedPct)
        tvCompareAvinox.text = snapshot.avinoxConsumedPct?.let(::formatPct) ?: "—"

        val actualDiffModel = snapshot.actualConsumedPct?.let { actual ->
            val diff = actual - snapshot.modelConsumedPct
            "실제-자체 ${signedPct(diff)}"
        } ?: "실제값 입력 전"
        val avinoxName = snapshot.avinoxMode?.label ?: "미선택"
        val avinoxTotal = snapshot.avinoxProjectedTotalPct?.let(::formatPct) ?: "—"
        tvCompareDetail.text =
            "누적 충전 +${formatPct(snapshot.chargedAddedPct)} · $actualDiffModel · 보정 ${String.format(Locale.US, "%.2f", snapshot.modelFactor)}x\n" +
            "종점 누적예상  자체 ${formatPct(snapshot.modelProjectedTotalPct)} · Avinox $avinoxName $avinoxTotal"
    }

    private fun signedPct(value: Double): String {
        val sign = if (value >= 0.0) "+" else ""
        return sign + formatPct(value)
    }

    private fun renderAtKm(kmValue: Double, simulated: Boolean) {
        profileView.visibility = View.VISIBLE
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
        val planPct = battery.percent.roundToInt().coerceIn(0, 100)
        val freshSoc = freshBleSoc()
        val lastObservedSoc = actualStatus?.entry?.percent?.roundToInt()?.coerceIn(0, 100)
        val displaySoc = when {
            simulated -> planPct
            freshSoc != null -> freshSoc
            lastObservedSoc != null -> lastObservedSoc
            else -> null
        }
        tvBattery.text = displaySoc?.let { "$it%" } ?: "—"
        tvBattery.setTextColor(displaySoc?.let { batteryColor(it.toDouble()) } ?: getColor(R.color.text_secondary))
        progressBattery.progress = displaySoc ?: 0
        progressBattery.progressTintList = android.content.res.ColorStateList.valueOf(displaySoc?.let { batteryColor(it.toDouble()) } ?: getColor(R.color.text_secondary))
        tvBatteryRange.text = when {
            simulated -> "테스트 · 계획 $planPct% · 예상 ${range.start.roundToInt()}~${range.endInclusive.roundToInt()}%"
            freshSoc != null -> {
                val diff = freshSoc - planPct
                "● BLE 실제 · 계획 $planPct% · 오차 ${if (diff >= 0) "+" else ""}$diff% · 예상 ${range.start.roundToInt()}~${range.endInclusive.roundToInt()}%"
            }
            lastObservedSoc != null -> "최근 실측 $lastObservedSoc% · BLE 재연결 중 · 계획 $planPct%"
            else -> "BLE 연결 대기 · 계획 $planPct% · 예상 ${range.start.roundToInt()}~${range.endInclusive.roundToInt()}%"
        }
        btnManualBattery.visibility = if (!simulated && logManager.isActive() && freshSoc == null) View.VISIBLE else View.GONE
        renderEnergyComparison(km)

        tvRiskStatus.text = reserve.label
        tvRiskStatus.setTextColor(when (reserve.label) {
            "여유" -> getColor(R.color.good)
            "주의" -> getColor(R.color.warn)
            else -> getColor(R.color.danger)
        })
        val diffAbs = abs(reserve.differencePct).roundToInt()
        val differenceText = if (reserve.differencePct >= 0) "여유 ${diffAbs}%" else "부족 ${diffAbs}%"
        tvRiskDetail.text = "${reserve.targetName} ${reserve.predictedPct.roundToInt()}%\n목표 ${reserve.targetPct.roundToInt()}%\n$differenceText"

        // 하위 호환용 숨김 뷰. 실제 SOC는 이제 상단의 큰 배터리 카드가 담당한다.
        tvActualBattery.text = displaySoc?.let { "$it%" } ?: "—"
        renderBleStatusLine()

        val remainFinish = (course.totalKm - km).coerceAtLeast(0.0)
        tvSpeed.text = if (latestSpeedKmh >= 2.0) "속도 ${RideFormatter.one(latestSpeedKmh)}km/h" else "속도 -"
        tvFinishEta.text = "종점 ${RideFormatter.one(remainFinish)}km · ${RideFormatter.etaClock(remainFinish, latestSpeedKmh)}"

        if (cp != null) {
            val remain = (cp.km - km).coerceAtLeast(0.0)
            val atCurrent = abs(cp.km - km) <= 0.15
            val predicted = plan.forecast(km, cp.km).percent.roundToInt()
            tvNextCheckpoint.text = if (atCurrent) "현재 · ${cp.name}" else cp.name
            val chargeAdvice = if (cp.chargeToPct != null) tripPlanner.nextChargeAdvice(km, finishTargetPct) else null
            tvNextCheckpointDetail.text = when {
                cp.chargeToPct != null && chargeAdvice != null -> buildString {
                    append(if (atCurrent) "현재 지점" else "${RideFormatter.one(remain)} km 남음")
                    append("\n도착예상 $predicted%")
                    append("\n앱권장 ${chargeAdvice.appRecommendedPct.roundToInt()}% · 사용자 ${chargeAdvice.userTargetPct.roundToInt()}%")
                    if (!chargeAdvice.feasibleAt100) append("\n⚠ 100%로도 ${chargeAdvice.shortagePctAt100.roundToInt()}% 부족")
                }
                cp.chargeToPct != null && atCurrent -> "현재 지점\n도착예상 $predicted%\n사용자 목표 ${cp.chargeToPct.roundToInt()}%"
                cp.chargeToPct != null -> "${RideFormatter.one(remain)} km 남음\n도착예상 $predicted%\n사용자 목표 ${cp.chargeToPct.roundToInt()}%"
                cp.km >= course.totalKm - 0.05 -> "${RideFormatter.one(remain)} km 남음\n종점예상 $predicted%\nETA ${RideFormatter.etaClock(remain, latestSpeedKmh)}"
                else -> "${RideFormatter.one(remain)} km 남음\n예상 $predicted%\nETA ${RideFormatter.etaClock(remain, latestSpeedKmh)}"
            }
            tvEta.text = ""
        } else {
            tvNextCheckpoint.text = "코스 완료"
            tvNextCheckpointDetail.text = "종점 도착"
            tvEta.text = "완료"
        }

        if (course.hasElevation) {
            tvElevationAhead.text = "10km ▲${stats10.ascentM.roundToInt()}m ▼${stats10.descentM.roundToInt()}m"
            if (climb == null) {
                tvNextClimb.text = "주요 업힐 없음"
                tvNextClimbDetail.text = "22 km 내\n큰 업힐 없음"
            } else {
                val rem = (climb.startKm - km).coerceAtLeast(0.0)
                tvNextClimb.text = if (rem <= 0.2) "현재 업힐" else "다음 업힐"
                tvNextClimbDetail.text = "${if (rem <= 0.2) "진행 중" else "${RideFormatter.one(rem)} km 후"}\n길이 ${RideFormatter.one(climb.distanceKm)} km\n평균 ${String.format(Locale.US, "%.1f", climb.averageGradePct)}% · +${climb.ascentM.roundToInt()}m"
            }
        } else {
            tvElevationAhead.text = "고도 없음 · 거리 기반"
            tvNextClimb.text = "업힐 분석 불가"
            tvNextClimbDetail.text = "GPX에\n고도 데이터 없음"
        }

        tvTenKmBattery.text = "10km 후 ${battery10.percent.roundToInt()}%${if (battery10.calibrated) " · 보정" else ""}"
        val pacing = pacingAdvisor.advice(km, latestSpeedKmh, reserve)
        val reservePrefix = when (reserve.label) {
            "위험" -> "⚠ 목표보다 ${(-reserve.differencePct).coerceAtLeast(0.0).roundToInt()}% 부족 · 절약 페이스\n"
            "주의" -> "목표선 근처 · 절약 우선\n"
            else -> ""
        }
        tvAssist.text = "${pacing.title}\n$reservePrefix${pacing.displayText}"
        tvAssist.setTextColor(when {
            reserve.label == "위험" -> getColor(R.color.danger)
            reserve.label == "주의" -> getColor(R.color.warn)
            else -> getColor(R.color.good)
        })
        tvNextPoi.text = poi?.let { "포인트 ${it.name} · ${RideFormatter.one((it.routeKm - km).coerceAtLeast(0.0))}km" } ?: "포인트 · 종점"

        val accText = if (latestAccuracyM >= 0) "±${latestAccuracyM.roundToInt()}m" else "-"
        val offText = if (simulated) "테스트" else "이탈 ${latestOffCourseM.roundToInt()}m"
        val ele = if (latestCourseElevation > 0) latestCourseElevation else point.ele
        tvCourseStatus.text = if (course.hasElevation) "고도 ${ele.roundToInt()}m · GPS $accText · $offText" else "GPS $accText · $offText · 고도 없음"
        renderChargePlannerStatus(km)

        if (!simulated) {
            tvGpsStatus.text = when {
                !logManager.isActive() -> "주행 대기"
                latestOffCourseM >= 150 -> "⚠ 코스 이탈 ${latestOffCourseM.roundToInt()}m"
                latestAccuracyM > 50 -> "GPS ±${latestAccuracyM.roundToInt()}m"
                else -> "GPS · 로그 정상"
            }
            tvGpsStatus.setTextColor(if (latestOffCourseM >= 150 && logManager.isActive()) getColor(R.color.danger) else getColor(R.color.text_secondary))
        }
        profileView.setCurrentKm(km)
        renderRideState()
    }

    private fun renderFreeRide() {
        latestRouteKm = logManager.activeDistanceKm().takeIf { it > latestRouteKm } ?: latestRouteKm.coerceAtLeast(0.0)
        latestFreeAscentM = logManager.activeAscentM().takeIf { it > latestFreeAscentM } ?: latestFreeAscentM.coerceAtLeast(0.0)
        val actual = actualStore.latest()
        val consumed = cumulativeActualConsumption(actualStore.entries())
        val charged = cumulativeChargeAdded(actualStore.entries())
        tvCurrentKm.text = "${RideFormatter.one(latestRouteKm)} km"
        val freshSoc = freshBleSoc()
        val storedSoc = actual?.percent?.roundToInt()?.coerceIn(0, 100)
        val displaySoc = freshSoc ?: storedSoc
        tvBattery.text = displaySoc?.let { "$it%" } ?: "—"
        tvBattery.setTextColor(displaySoc?.let { batteryColor(it.toDouble()) } ?: getColor(R.color.text_secondary))
        progressBattery.progress = displaySoc ?: 0
        progressBattery.progressTintList = android.content.res.ColorStateList.valueOf(displaySoc?.let { batteryColor(it.toDouble()) } ?: getColor(R.color.text_secondary))
        tvBatteryRange.text = when {
            freshSoc != null -> "● BLE 실제 · 임의주행 · GPS + 배터리 자동 기록"
            storedSoc != null -> "최근 실측 $storedSoc% · BLE 재연결 중 · 임의주행"
            else -> "BLE 연결 대기 · 임의주행"
        }
        btnManualBattery.visibility = if (logManager.isActive() && freshSoc == null) View.VISIBLE else View.GONE
        tvCompareActual.text = consumed?.let(::formatPct) ?: "—"
        tvCompareModel.text = "사후"
        tvCompareAvinox.text = "사후"
        tvCompareDetail.text = "누적 충전 +${formatPct(charged)} · 주행 종료 후 FIT을 연결하면 우리 모델 사후예측 생성\nAvinox 예상 소비량도 종료 후 독립 benchmark로 입력"
        tvRiskStatus.text = "기록 중"
        tvRiskStatus.setTextColor(getColor(R.color.accent))
        tvRiskDetail.text = "임의주행은 목표 코스/종점이 없습니다. Avinox BLE SOC를 자동 기록해 실제 누적 소비량을 계산합니다."
        tvActualBattery.text = displaySoc?.let { "$it%" } ?: "—"
        renderBleStatusLine()
        tvNextCheckpoint.text = "자유 주행"
        tvNextCheckpointDetail.text = "GPX 독립\n${RideFormatter.one(latestRouteKm)} km 기록\n배터리 ${actual?.percent?.roundToInt()?.let { "$it%" } ?: "입력 전"}"
        tvNextClimb.text = "실제 고도"
        tvNextClimbDetail.text = "누적 상승 약\n${latestFreeAscentM.roundToInt()} m\nFIT 연결 후 정밀 보강"
        tvSpeed.text = if (latestSpeedKmh >= 2.0) "속도 ${RideFormatter.one(latestSpeedKmh)} km/h" else "속도 계산 중"
        tvFinishEta.text = "종점 없음"
        tvElevationAhead.text = "▲ ${latestFreeAscentM.roundToInt()} m"
        tvTenKmBattery.text = consumed?.let { "누적소비 ${formatPct(it)}" } ?: "BLE 배터리 연결 대기"
        tvAssist.text = "임의주행 데이터 수집 중 · GPS + Avinox BLE SOC 자동 저장\n종료 후 FIT · Avinox 예상값을 붙여 3자 비교"
        tvCourseStatus.text = "임의주행에서는 선택 GPX를 사용하지 않습니다."
        tvNextPoi.text = ""
        profileView.visibility = View.GONE
        tvGpsStatus.text = if (logManager.isActive()) "임의주행 GPS 기록 중 · 화면 꺼도 유지" else "임의주행 저장 완료"
    }

    private fun cumulativeChargeAdded(entries: List<ActualBatteryEntry>): Double {
        var total = 0.0
        var arrival: ActualBatteryEntry? = null
        entries.sortedBy { it.timestampMs }.forEach { e ->
            when (e.kind) {
                ActualEntryKind.ARRIVAL -> arrival = e
                ActualEntryKind.POST_CHARGE -> {
                    arrival?.let { total += (e.percent - it.percent).coerceAtLeast(0.0) }
                    arrival = null
                }
                ActualEntryKind.RIDING -> Unit
            }
        }
        return total
    }

    private fun cumulativeActualConsumption(entries: List<ActualBatteryEntry>): Double? {
        val last = entries.lastOrNull() ?: return null
        return (entries.first().percent + cumulativeChargeAdded(entries) - last.percent).coerceAtLeast(0.0)
    }

    private fun pickPostRideFit() {
        if (logManager.isActive()) return Toast.makeText(this, "주행을 종료한 뒤 FIT을 연결해 주세요.", Toast.LENGTH_SHORT).show()
        if (logManager.lastJsonFile() == null) return Toast.makeText(this, "먼저 저장된 주행이 필요합니다.", Toast.LENGTH_SHORT).show()
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "application/fit", "application/vnd.ant.fit"))
        }
        try { startActivityForResult(intent, REQ_POST_RIDE_FIT) }
        catch (_: ActivityNotFoundException) { Toast.makeText(this, "파일 선택 앱을 찾지 못했습니다.", Toast.LENGTH_LONG).show() }
    }

    private fun showPostRideAvinoxDialog() {
        if (logManager.isActive()) return Toast.makeText(this, "주행 종료 후 입력해 주세요.", Toast.LENGTH_SHORT).show()
        if (logManager.lastJsonFile() == null) return Toast.makeText(this, "저장된 주행이 없습니다.", Toast.LENGTH_SHORT).show()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 12, 48, 0) }
        fun field(label: String): EditText = EditText(this).apply {
            hint = "$label 전체 소비량 % · 100 초과 가능"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            box.addView(this)
        }
        val eco = field("ECO")
        val auto = field("AUTO")
        val trail = field("TRAIL")
        val turbo = field("TURBO")
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val modes = listOf(AvinoxRideMode.ECO, AvinoxRideMode.AUTO, AvinoxRideMode.TRAIL, AvinoxRideMode.TURBO)
        modes.forEachIndexed { i, mode -> group.addView(RadioButton(this).apply { id = 9100 + i; text = mode.label; if (mode == AvinoxRideMode.AUTO) isChecked = true }) }
        box.addView(TextView(this).apply { text = "비교할 대표 모드"; setPadding(0, 12, 0, 0) })
        box.addView(group)
        val dialog = AlertDialog.Builder(this).setTitle("주행 후 Avinox 예상값").setMessage("같은 주행 경로를 Avinox 내비게이션에 등록해 나온 전체 예상 소비량을 입력합니다. 학습에는 절대 사용하지 않습니다.").setView(box).setPositiveButton("저장", null).setNegativeButton("취소", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                fun num(e: EditText) = e.text.toString().trim().takeIf { it.isNotBlank() }?.toDoubleOrNull()?.takeIf { it >= 0.0 }
                val values = listOf(num(eco), num(auto), num(trail), num(turbo))
                if (values.all { it == null }) { Toast.makeText(this, "Avinox 값을 하나 이상 입력해 주세요.", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                val idx = modes.indices.firstOrNull { group.checkedRadioButtonId == 9100 + it } ?: 1
                try {
                    val text = logManager.setLastRideAvinox(values[0], values[1], values[2], values[3], modes[idx])
                    dialog.dismiss()
                    AlertDialog.Builder(this).setTitle("Avinox 저장 완료").setMessage(text).setPositiveButton("확인", null).show()
                } catch (e: Exception) { Toast.makeText(this, "저장 실패: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
        dialog.show()
    }

    private fun learnLastFreeRide() {
        if (logManager.isActive()) return Toast.makeText(this, "주행 종료 후 학습해 주세요.", Toast.LENGTH_SHORT).show()
        AlertDialog.Builder(this)
            .setTitle("임의주행 학습 반영")
            .setMessage("FIT을 기준 코스로 사용하고, 주행 중 Avinox BLE로 자동 기록된 실제 배터리 값만 개인 학습에 반영합니다. Avinox 예상값은 학습에 사용하지 않습니다.\n\n사후 비교용 '우리 모델 예상'은 이미 학습 전 값으로 저장되어 있어 비교 공정성은 유지됩니다.")
            .setPositiveButton("학습 반영") { _, _ ->
                try {
                    val n = logManager.learnLastFreeRideFromFit(learningStore)
                    if (n > 0) {
                        refreshLearningPage()
                        Toast.makeText(this, "임의주행 ${n}개 구간을 개인 학습에 반영했습니다.", Toast.LENGTH_LONG).show()
                    } else Toast.makeText(this, "FIT 연결 또는 배터리 입력이 부족해 학습하지 못했습니다.", Toast.LENGTH_LONG).show()
                } catch (e: Exception) { Toast.makeText(this, "학습 실패: ${e.message}", Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showPostRideComparison() {
        AlertDialog.Builder(this).setTitle("사후 3자 비교").setMessage(logManager.lastComparisonText()).setPositiveButton("확인", null).show()
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
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0.18.2"
    } catch (_: Exception) { "0.18.2" }

    override fun onResume() {
        super.onResume()
        UpdateManager.resumePendingInstall(this)
        if (!::courseRepo.isInitialized) return

        // 코스 메뉴에서 선택한 코스가 바뀌었으면 즉시 재로딩.
        val activeId = runCatching { courseRepo.activeMeta().id }.getOrNull()
        if (activeId != null && activeId != loadedCourseId && !logManager.isActive()) {
            actualStore.clear()
            loadSelectedCourse(resetProgress = false)
        } else if (activeId != null && activeId == loadedCourseId && !logManager.isActive() && ::course.isInitialized) {
            // 코스 메뉴에서 충전소 계획만 바꾼 경우에도 즉시 배터리 판단 기준을 재구성한다.
            basePlan = BatteryPlan(course, learningStore, chargingStore.list(activeId))
            plan = AdaptiveBatteryPlan(basePlan, actualStore)
            pacingAdvisor = EnergyPacingAdvisor(course, learningStore)
            tripPlanner = EnergyTripPlanner(basePlan, plan)
        }
        loadBleSnapshot()
        applySettings()
        renderCourseQuick()
        refreshInlineSettings()
        refreshLearningPage()
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
        if (::course.isInitialized) { if (::logManager.isInitialized && logManager.isFreeRide()) renderFreeRide() else renderAtKm(latestRouteKm, testMode) }
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
