package com.seungjae.jangsu280battery

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Color
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognizerIntent
import android.provider.OpenableColumns
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.text.InputType
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
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
    private lateinit var replanStore: RideReplanStore
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
    private lateinit var layoutAssistModeBanner: LinearLayout
    private lateinit var layoutAutoEstimate: LinearLayout
    private lateinit var tvAutoEstimateLabel: TextView
    private lateinit var tvAutoEstimateGrade: TextView
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
    private lateinit var tvRideRouteScale: TextView
    private lateinit var switchRideTestMode: Switch
    private lateinit var rideMiniProfileView: ElevationProfileView
    private lateinit var seekRideRoute: ChargeDistanceSeekBar
    private lateinit var pageRideRoot: LinearLayout
    private lateinit var layoutRideWarningBanner: LinearLayout
    private lateinit var tvRideWarningTitle: TextView
    private lateinit var tvRideWarningReason: TextView
    private lateinit var layoutRideReachMargins: LinearLayout
    private lateinit var tvSafeReachMargin: TextView
    private lateinit var tvHardReachMargin: TextView
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
    private lateinit var tvPointEtaList: TextView
    private lateinit var tvPointEtaBasis: TextView
    private lateinit var tvPointEtaRideTime: TextView
    private lateinit var tvPointEtaStopTime: TextView
    private lateinit var tvVersion: TextView
    private lateinit var profileView: ElevationProfileView
    private lateinit var btnRideReport: Button
    private lateinit var btnPostRideFit: Button
    private lateinit var btnFeedbackStrava: Button
    private lateinit var btnPostRideAvinox: Button
    private lateinit var btnPostRideCompare: Button
    private lateinit var btnPostRideLearn: Button
    private lateinit var tvChargeStatus: TextView
    private lateinit var btnReplanAction: Button
    private lateinit var switchPageVoice: Switch
    private lateinit var switchPageKeepScreen: Switch
    private lateinit var tvPageDistanceInterval: TextView
    private lateinit var seekPageDistanceInterval: SeekBar
    private lateinit var tvPageTimeInterval: TextView
    private lateinit var seekPageTimeInterval: SeekBar
    private lateinit var tvPageFinishTarget: TextView
    private lateinit var seekPageFinishTarget: SeekBar
    private lateinit var tvPageHardReserve: TextView
    private lateinit var seekPageHardReserve: SeekBar
    private lateinit var tvPageSettingsHint: TextView
    private lateinit var btnPageChargeSimulator: Button
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
    private lateinit var btnPageMobileRelease: Button
    private lateinit var tvPageMobileReleaseStatus: TextView
    private lateinit var pagerFlipper: ViewFlipper
    private lateinit var tvPagerIndicator: TextView
    private lateinit var pagerGesture: GestureDetector

    private var latestRouteKm = 0.0
    private var latestOffCourseM = 0.0
    private var latestAccuracyM = -1f
    private var latestSpeedKmh = 0.0
    private var latestCourseElevation = 0.0
    private var latestLat = Double.NaN
    private var latestLon = Double.NaN
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

    // v0.20.0 AUTO 내부 어시스트는 BLE 실측값이 아직 확인되지 않았다.
    // 아래 값은 주행동역학 + 최근 SOC/km를 섞은 명시적 "추정" HUD 전용이다.
    private var lastRenderedAssistMode: AvinoxAssistMode? = null
    private var lastRenderedAutoEstimate: AvinoxAssistMode? = null
    private var autoEstimateMode: AvinoxAssistMode? = null
    private var autoEstimateCandidate: AvinoxAssistMode? = null
    private var autoEstimateCandidateSinceMs: Long = 0L
    private var autoEstimateWasAuto = false
    private var autoEstimateLastSampleMs: Long = 0L
    private var autoEstimateLastKm = 0.0
    private var autoEstimateLastElevation = Double.NaN
    private var autoEstimateLastSpeedKmh = 0.0
    private var autoEstimateSmoothedGradePct = 0.0
    private var autoEstimateSmoothedAccel = 0.0
    private var autoEstimateSocAnchor: Int? = null
    private var autoEstimateSocAnchorKm = 0.0
    private var autoEstimateWhPerKm: Double? = null
    private var lastLoggedAutoEstimate: AvinoxAssistMode? = null
    private var testMode = false
    private var receiverRegistered = false
    private var speechPendingAfterPermission = false
    private var finishTargetPct = AppSettings.DEFAULT_FINISH_TARGET.toDouble()
    private var loadedCourseId: String? = null
    private var voiceInputStartedMs: Long = 0L
    private var voiceInputRouteKm: Double = 0.0
    private var refreshingSettingsUi = false
    private var refreshingRideControls = false
    private var emergencySearchRunning = false

    private enum class RideWarningStage { NONE, CHARGE_IMMINENT, ECO_CONNECT, EMERGENCY, HARD_RESERVE }
    private var lastRideWarningKey: String = ""

    private val rideReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != RideService.ACTION_UPDATE || testMode) return
            latestRouteKm = intent.getDoubleExtra(RideService.EXTRA_ROUTE_KM, latestRouteKm)
            latestOffCourseM = intent.getDoubleExtra(RideService.EXTRA_OFF_COURSE_M, latestOffCourseM)
            latestAccuracyM = intent.getFloatExtra(RideService.EXTRA_ACCURACY_M, latestAccuracyM)
            latestSpeedKmh = intent.getDoubleExtra(RideService.EXTRA_SPEED_KMH, latestSpeedKmh)
            latestCourseElevation = intent.getDoubleExtra(RideService.EXTRA_COURSE_ELEVATION, latestCourseElevation)
            if (intent.hasExtra(RideService.EXTRA_LAT)) latestLat = intent.getDoubleExtra(RideService.EXTRA_LAT, latestLat)
            if (intent.hasExtra(RideService.EXTRA_LON)) latestLon = intent.getDoubleExtra(RideService.EXTRA_LON, latestLon)
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
            updateAutoAssistEstimate()
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
        replanStore = RideReplanStore(this)
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
        if (!logManager.isActive()) replanStore.clearCourse(courseMeta.id)
        applySettings()

        btnCourseMenu.setOnClickListener { startActivity(Intent(this, CourseActivity::class.java)) }
        btnCourseImportQuick.setOnClickListener { importGpxQuick() }
        tvCourseQuickSelect.setOnClickListener { showCoursePickerQuick() }
        btnAvinoxReferenceEdit.setOnClickListener { showAvinoxReferenceDialog() }
        btnRideToggle.setOnClickListener { if (logManager.isActive()) confirmEndRide() else showRideStartModeDialog() }
        btnChargeToggle.setOnClickListener { toggleCharging() }
        btnReplanAction.setOnClickListener { handleReplanAction() }
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
        setupRidePositionControls()
        setupInlineSettings()
        setupLearningPage()
        setupMobileReleasePage()
        setupSwipePager()

        renderCourseQuick()
        refreshRidePositionControls()
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
        layoutAssistModeBanner = findViewById(R.id.layoutAssistModeBanner)
        layoutAutoEstimate = findViewById(R.id.layoutAutoEstimate)
        tvAutoEstimateLabel = findViewById(R.id.tvAutoEstimateLabel)
        tvAutoEstimateGrade = findViewById(R.id.tvAutoEstimateGrade)
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
        tvRideRouteScale = findViewById(R.id.tvRideRouteScale)
        switchRideTestMode = findViewById(R.id.switchRideTestMode)
        rideMiniProfileView = findViewById(R.id.rideMiniProfileView)
        seekRideRoute = findViewById(R.id.seekRideRoute)
        pageRideRoot = findViewById(R.id.pageRideRoot)
        layoutRideWarningBanner = findViewById(R.id.layoutRideWarningBanner)
        tvRideWarningTitle = findViewById(R.id.tvRideWarningTitle)
        tvRideWarningReason = findViewById(R.id.tvRideWarningReason)
        layoutRideReachMargins = findViewById(R.id.layoutRideReachMargins)
        tvSafeReachMargin = findViewById(R.id.tvSafeReachMargin)
        tvHardReachMargin = findViewById(R.id.tvHardReachMargin)
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
        tvPointEtaList = findViewById(R.id.tvPointEtaList)
        tvPointEtaBasis = findViewById(R.id.tvPointEtaBasis)
        tvPointEtaRideTime = findViewById(R.id.tvPointEtaRideTime)
        tvPointEtaStopTime = findViewById(R.id.tvPointEtaStopTime)
        tvVersion = findViewById(R.id.tvVersion)
        profileView = findViewById(R.id.profileView)
        btnRideReport = findViewById(R.id.btnRideReport)
        btnPostRideFit = findViewById(R.id.btnPostRideFit)
        btnFeedbackStrava = findViewById(R.id.btnFeedbackStrava)
        btnPostRideAvinox = findViewById(R.id.btnPostRideAvinox)
        btnPostRideCompare = findViewById(R.id.btnPostRideCompare)
        btnPostRideLearn = findViewById(R.id.btnPostRideLearn)
        tvChargeStatus = findViewById(R.id.tvChargeStatus)
        btnReplanAction = findViewById(R.id.btnReplanAction)
        switchPageVoice = findViewById(R.id.switchPageVoice)
        switchPageKeepScreen = findViewById(R.id.switchPageKeepScreen)
        tvPageDistanceInterval = findViewById(R.id.tvPageDistanceInterval)
        seekPageDistanceInterval = findViewById(R.id.seekPageDistanceInterval)
        tvPageTimeInterval = findViewById(R.id.tvPageTimeInterval)
        seekPageTimeInterval = findViewById(R.id.seekPageTimeInterval)
        tvPageFinishTarget = findViewById(R.id.tvPageFinishTarget)
        seekPageFinishTarget = findViewById(R.id.seekPageFinishTarget)
        tvPageHardReserve = findViewById(R.id.tvPageHardReserve)
        seekPageHardReserve = findViewById(R.id.seekPageHardReserve)
        tvPageSettingsHint = findViewById(R.id.tvPageSettingsHint)
        btnPageChargeSimulator = findViewById(R.id.btnPageChargeSimulator)
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
        btnPageMobileRelease = findViewById(R.id.btnPageMobileRelease)
        tvPageMobileReleaseStatus = findViewById(R.id.tvPageMobileReleaseStatus)
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
            configureRideRouteVisuals()
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
        replanStore.clearCourse(courseMeta.id)
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
            charging != null -> "충전 중 · 계획/권장 확인 중"
            active -> ""
            else -> "주행 시작 후 충전 기록 가능"
        }
        if (!active) tvGpsStatus.text = if (testMode) "테스트 모드" else "주행 대기"
        renderAssistModeUi()
    }

    private fun renderChargePlannerStatus(routeKm: Double) {
        if (!logManager.isActive() || logManager.isFreeRide()) return
        val emergency = replanStore.active(courseMeta.id)
        val activeCharge = chargingSessionStore.active()
        if (emergency != null && activeCharge != null && emergency.phase == EmergencyPhase.CHARGING) {
            val currentSoc = freshBleSoc()?.toDouble() ?: actualStore.latest()?.percent ?: activeCharge.arrivalPct
            val target = emergencyRecommendedChargeTargetPct(emergency)
            val remain = AvinoxChargeCurve.minutesBetween(currentSoc, target.toDouble())
            tvChargeStatus.text = if (currentSoc + 0.49 >= target) {
                "✓ 비상 충전 권장 ${target}% 도달 · 충전 완료 후 원래 이탈점 복귀"
            } else {
                "⚡ 비상 충전 ${currentSoc.roundToInt()}% → 권장 ${target}% · ${AvinoxChargeCurve.minutesText(remain)} · 완료 후 원래 이탈점 복귀"
            }
            tvChargeStatus.setTextColor(if (currentSoc + 0.49 >= target) getColor(R.color.good) else getColor(R.color.warn))
            return
        }
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
                append("⚡ ${advice.stationName}: 내 계획 ${advice.userTargetPct.roundToInt()}% · 앱권장 ${advice.appRecommendedPct.roundToInt()}%")
                append(" · 권장 ${AvinoxChargeCurve.minutesText(advice.minutesArrivalToRecommended)}")
                if (advice.userTargetPct.roundToInt() != advice.appRecommendedPct.roundToInt()) {
                    append(" / 계획 ${AvinoxChargeCurve.minutesText(advice.minutesArrivalToUserTarget)}")
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
                renderAssistIdle("대기")
                tvAssistModeHint.text = "주행을 시작하면 Avinox BLE 모드를 자동 감지합니다"
                tvAssistModeHint.setTextColor(getColor(R.color.text_secondary))
                layoutAssistVerify.visibility = View.GONE
            }
            primary == null -> {
                renderAssistIdle("BLE…")
                tvAssistModeHint.text = "FFF4 실시간 패킷에서 선택 모드를 찾는 중"
                tvAssistModeHint.setTextColor(getColor(R.color.text_secondary))
                layoutAssistVerify.visibility = View.GONE
            }
            activeConfidence == "CONFIRMED" && compatible -> {
                renderAssistModeBanner(activeMode!!, tentative = false)
                tvAssistModeHint.text = "사용자 확인됨 · BLE raw ${latestAssistRawCode ?: "-"} · 표시를 탭하면 수정"
                tvAssistModeHint.setTextColor(getColor(R.color.good))
                layoutAssistVerify.visibility = View.GONE
            }
            latestAssistConfidence == "HIGH" && alternate == null -> {
                renderAssistModeBanner(primary, tentative = false)
                tvAssistModeHint.text = if (primary == AvinoxAssistMode.AUTO) {
                    "AUTO 우측은 실측이 아닌 실험적 어시스트 등급 추정"
                } else {
                    "● BLE 자동감지 · 다르면 위 모드 표시를 탭"
                }
                tvAssistModeHint.setTextColor(getColor(R.color.good))
                layoutAssistVerify.visibility = View.GONE
            }
            else -> {
                renderAssistModeBanner(primary, tentative = true)
                val altText = alternate?.let { " · 다른 후보 ${it.label}" }.orEmpty()
                tvAssistModeHint.text = "선택 모드 후보$altText · raw ${latestAssistRawCode ?: "-"}"
                tvAssistModeHint.setTextColor(getColor(R.color.warn))
                btnAssistModeConfirm.text = "✓ ${primary.label} 맞음"
                layoutAssistVerify.visibility = View.VISIBLE
            }
        }
    }

    private fun renderAssistIdle(text: String) {
        layoutAutoEstimate.visibility = View.GONE
        tvAssistModeCurrent.text = text
        tvAssistModeCurrent.setTextColor(getColor(R.color.text_primary))
        tvAssistModeCurrent.setBackgroundResource(R.drawable.assist_mode_idle_bg)
        lastRenderedAssistMode = null
        lastRenderedAutoEstimate = null
    }

    private fun renderAssistModeBanner(mode: AvinoxAssistMode, tentative: Boolean) {
        val changed = lastRenderedAssistMode != mode
        renderAssistModeAndRange(mode, tentative)
        when (mode) {
            AvinoxAssistMode.ECO -> {
                layoutAutoEstimate.visibility = View.GONE
                tvAssistModeCurrent.setBackgroundResource(R.drawable.assist_mode_eco_bg)
                tvAssistModeCurrent.setTextColor(getColor(R.color.text_primary))
            }
            AvinoxAssistMode.AUTO -> {
                tvAssistModeCurrent.setBackgroundResource(R.drawable.assist_mode_auto_left_bg)
                tvAssistModeCurrent.setTextColor(getColor(R.color.text_primary))
                layoutAutoEstimate.visibility = View.VISIBLE
                renderAutoEstimateSegment()
            }
            AvinoxAssistMode.TRAIL -> {
                layoutAutoEstimate.visibility = View.GONE
                tvAssistModeCurrent.setBackgroundResource(R.drawable.assist_mode_trail_bg)
                tvAssistModeCurrent.setTextColor(getColor(R.color.assist_dark_text))
            }
            AvinoxAssistMode.TURBO -> {
                layoutAutoEstimate.visibility = View.GONE
                tvAssistModeCurrent.setBackgroundResource(R.drawable.assist_mode_turbo_bg)
                tvAssistModeCurrent.setTextColor(getColor(R.color.text_primary))
            }
        }
        if (changed) popModeBanner()
        lastRenderedAssistMode = mode
    }

    /**
     * 현재 BLE SOC와 A급 개인학습을 이용한 선택 모드별 예상 주행거리.
     * 계획주행은 현재 GPX의 남은 고도 프로파일을 따라 적분하고,
     * 임의주행은 같은 모드의 검증된 평균 %/km를 사용한다.
     * 표시 거리는 설정의 '충전권장 기준 잔량'을 남겨두는 안전거리다.
     */
    private fun assistModeRangeText(mode: AvinoxAssistMode): String {
        if (!::learningStore.isInitialized || learningStore.batterySampleCountForMode(mode) <= 0) return "학습중"
        val soc = freshBleSoc()?.toDouble() ?: actualStore.latest()?.percent ?: return "SOC 대기"
        val reserve = finishTargetPct.coerceIn(1.0, 99.0)
        val usable = (soc - reserve).coerceAtLeast(0.0)
        if (usable <= 0.05) return "0 km"

        if (logManager.isFreeRide()) {
            val pctPerKm = learningStore.learnedPctPerKmForMode(mode) ?: return "학습중"
            val km = (usable / pctPerKm).coerceIn(0.0, 999.0)
            return "${km.roundToInt()} km"
        }

        if (!::course.isInitialized) return "학습중"
        val startKm = currentRideKm().coerceIn(0.0, course.totalKm)
        val remaining = (course.totalKm - startKm).coerceAtLeast(0.0)
        if (remaining <= 0.05) return "0 km"
        val liveFactor = if (::plan.isInitialized) (plan.calibration(startKm)?.factor ?: 1.0) else 1.0
        val fullUse = learningStore.estimateConsumption(course, startKm, course.totalKm, mode) * liveFactor
        if (fullUse <= usable + 1e-6) return "${remaining.roundToInt()}+ km"

        var lo = startKm
        var hi = course.totalKm
        repeat(22) {
            val mid = (lo + hi) / 2.0
            val use = learningStore.estimateConsumption(course, startKm, mid, mode) * liveFactor
            if (use <= usable) lo = mid else hi = mid
        }
        val km = (lo - startKm).coerceAtLeast(0.0)
        return "${km.roundToInt()} km"
    }

    private fun renderAssistModeAndRange(mode: AvinoxAssistMode, tentative: Boolean) {
        val modeText = mode.name + if (tentative) " ?" else ""
        val rangeText = assistModeRangeText(mode)
        val full = "$modeText   $rangeText"
        val span = SpannableString(full)
        span.setSpan(RelativeSizeSpan(0.50f), modeText.length, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        tvAssistModeCurrent.text = span
    }

    private fun renderAutoEstimateSegment() {
        val estimate = autoEstimateMode
        tvAutoEstimateLabel.text = "추정"
        when (estimate) {
            AvinoxAssistMode.ECO -> {
                tvAutoEstimateGrade.text = "ECO급"
                layoutAutoEstimate.setBackgroundResource(R.drawable.assist_estimate_eco_right_bg)
                tvAutoEstimateLabel.setTextColor(getColor(R.color.text_primary))
                tvAutoEstimateGrade.setTextColor(getColor(R.color.text_primary))
            }
            AvinoxAssistMode.TRAIL -> {
                tvAutoEstimateGrade.text = "TRAIL급"
                layoutAutoEstimate.setBackgroundResource(R.drawable.assist_estimate_trail_right_bg)
                tvAutoEstimateLabel.setTextColor(getColor(R.color.assist_dark_text))
                tvAutoEstimateGrade.setTextColor(getColor(R.color.assist_dark_text))
            }
            AvinoxAssistMode.TURBO -> {
                tvAutoEstimateGrade.text = "TURBO급"
                layoutAutoEstimate.setBackgroundResource(R.drawable.assist_estimate_turbo_right_bg)
                tvAutoEstimateLabel.setTextColor(getColor(R.color.text_primary))
                tvAutoEstimateGrade.setTextColor(getColor(R.color.text_primary))
            }
            else -> {
                tvAutoEstimateGrade.text = "계산중"
                layoutAutoEstimate.setBackgroundResource(R.drawable.assist_estimate_idle_right_bg)
                tvAutoEstimateLabel.setTextColor(getColor(R.color.text_secondary))
                tvAutoEstimateGrade.setTextColor(getColor(R.color.text_primary))
            }
        }
        if (estimate != null && estimate != lastRenderedAutoEstimate) {
            layoutAutoEstimate.animate().cancel()
            layoutAutoEstimate.scaleX = 1f
            layoutAutoEstimate.scaleY = 1f
            layoutAutoEstimate.animate().scaleX(1.07f).scaleY(1.07f).setDuration(90L).withEndAction {
                layoutAutoEstimate.animate().scaleX(1f).scaleY(1f).setDuration(130L).start()
            }.start()
        }
        lastRenderedAutoEstimate = estimate
    }

    private fun popModeBanner() {
        layoutAssistModeBanner.animate().cancel()
        layoutAssistModeBanner.scaleX = 1f
        layoutAssistModeBanner.scaleY = 1f
        layoutAssistModeBanner.animate().scaleX(1.055f).scaleY(1.055f).setDuration(90L).withEndAction {
            layoutAssistModeBanner.animate().scaleX(1f).scaleY(1f).setDuration(150L).start()
        }.start()
    }

    /**
     * AUTO 내부의 실제 모터 지원 단계는 현재 BLE에서 확인되지 않았다.
     * 그래서 이 값은 절대로 "실측"으로 취급하지 않고 HUD에 '추정'으로만 표시한다.
     *
     * 실험 알고리즘 v1:
     * - 800Wh 기준 최근 SOC 감소량 / 거리(Wh/km)를 가장 강한 신호로 사용
     * - 자유주행에서는 GPS 고도 노이즈 때문에 경사도는 쓰지 않고 가속만 보조 신호로 사용
     * - GPX 계획주행에서는 코스 고도 기반 경사 + 가속을 보조 신호로 사용
     * - 100→98% 구간은 Avinox SOC 비선형성이 커서 Wh/km 신호를 무시
     */
    private fun updateAutoAssistEstimate() {
        if (!logManager.isActive()) {
            resetAutoAssistEstimator()
            return
        }
        val now = System.currentTimeMillis()
        val detectionFresh = latestAssistUpdatedMs > 0L && now - latestAssistUpdatedMs <= 15_000L
        val selected = latestAssistPrimary.takeIf { detectionFresh }
        if (selected != AvinoxAssistMode.AUTO) {
            if (autoEstimateWasAuto) resetAutoAssistEstimator()
            return
        }

        if (!autoEstimateWasAuto) {
            resetAutoAssistEstimator()
            autoEstimateWasAuto = true
            autoEstimateLastSampleMs = now
            autoEstimateLastKm = latestRouteKm
            autoEstimateLastElevation = latestCourseElevation
            autoEstimateLastSpeedKmh = latestSpeedKmh
            autoEstimateSocAnchor = latestBleSoc
            autoEstimateSocAnchorKm = latestRouteKm
            return
        }

        val dtSec = (now - autoEstimateLastSampleMs) / 1000.0
        val dKm = latestRouteKm - autoEstimateLastKm
        if (dtSec in 0.45..6.0) {
            val accel = (latestSpeedKmh - autoEstimateLastSpeedKmh) / dtSec
            if (accel.isFinite() && abs(accel) <= 12.0) {
                autoEstimateSmoothedAccel = autoEstimateSmoothedAccel * 0.72 + accel * 0.28
            }
            if (!logManager.isFreeRide() && dKm in 0.004..0.20 && latestCourseElevation.isFinite() && autoEstimateLastElevation.isFinite()) {
                val grade = (latestCourseElevation - autoEstimateLastElevation) / (dKm * 1000.0) * 100.0
                if (grade.isFinite() && abs(grade) <= 25.0) {
                    autoEstimateSmoothedGradePct = autoEstimateSmoothedGradePct * 0.74 + grade * 0.26
                }
            }
        }
        autoEstimateLastSampleMs = now
        autoEstimateLastKm = latestRouteKm
        autoEstimateLastElevation = latestCourseElevation
        autoEstimateLastSpeedKmh = latestSpeedKmh

        val soc = latestBleSoc
        val anchorSoc = autoEstimateSocAnchor
        if (soc != null) {
            if (anchorSoc == null || soc > anchorSoc) {
                autoEstimateSocAnchor = soc
                autoEstimateSocAnchorKm = latestRouteKm
            } else if (soc < anchorSoc) {
                val drop = anchorSoc - soc
                val distance = latestRouteKm - autoEstimateSocAnchorKm
                // 상단 SOC 왜곡을 피하고, 너무 짧은 거리/비정상 샘플은 버린다.
                if (anchorSoc <= 98 && soc <= 98 && drop in 1..5 && distance >= 0.15) {
                    val rawWhPerKm = drop * 8.0 / distance
                    if (rawWhPerKm in 1.0..60.0) {
                        autoEstimateWhPerKm = autoEstimateWhPerKm?.let { it * 0.55 + rawWhPerKm * 0.45 } ?: rawWhPerKm
                    }
                }
                autoEstimateSocAnchor = soc
                autoEstimateSocAnchorKm = latestRouteKm
            }
        }

        val candidate = classifyAutoAssistEstimate()
        if (candidate != autoEstimateCandidate) {
            autoEstimateCandidate = candidate
            autoEstimateCandidateSinceMs = now
        } else if (candidate != null && candidate != autoEstimateMode && now - autoEstimateCandidateSinceMs >= 650L) {
            autoEstimateMode = candidate
            if (candidate != lastLoggedAutoEstimate) {
                val wh = autoEstimateWhPerKm?.let { String.format(Locale.US, "%.1f", it) } ?: "-"
                val grade = String.format(Locale.US, "%.1f", autoEstimateSmoothedGradePct)
                val accel = String.format(Locale.US, "%.2f", autoEstimateSmoothedAccel)
                logManager.recordEvent(
                    "AUTO_ASSIST_ESTIMATE",
                    "${candidate.name}급 · 추정 v1 · ${wh}Wh/km · grade ${grade}% · accel ${accel}kmh/s",
                    currentRideKm(),
                    freshBleSoc()?.toDouble()
                )
                lastLoggedAutoEstimate = candidate
            }
        }
    }

    private fun classifyAutoAssistEstimate(): AvinoxAssistMode {
        val whLevel = autoEstimateWhPerKm?.let {
            when {
                it < 6.5 -> AvinoxAssistMode.ECO
                it < 18.0 -> AvinoxAssistMode.TRAIL
                else -> AvinoxAssistMode.TURBO
            }
        }
        val dynamicLevel = when {
            !logManager.isFreeRide() && autoEstimateSmoothedGradePct >= 5.5 -> AvinoxAssistMode.TURBO
            autoEstimateSmoothedAccel >= 2.8 -> AvinoxAssistMode.TURBO
            !logManager.isFreeRide() && autoEstimateSmoothedGradePct >= 1.7 -> AvinoxAssistMode.TRAIL
            autoEstimateSmoothedAccel >= 0.9 -> AvinoxAssistMode.TRAIL
            else -> AvinoxAssistMode.ECO
        }
        if (whLevel == null) return dynamicLevel
        return when {
            whLevel == AvinoxAssistMode.TURBO || dynamicLevel == AvinoxAssistMode.TURBO -> AvinoxAssistMode.TURBO
            whLevel == AvinoxAssistMode.TRAIL || dynamicLevel == AvinoxAssistMode.TRAIL -> AvinoxAssistMode.TRAIL
            else -> AvinoxAssistMode.ECO
        }
    }

    private fun resetAutoAssistEstimator() {
        autoEstimateMode = null
        autoEstimateCandidate = null
        autoEstimateCandidateSinceMs = 0L
        autoEstimateWasAuto = false
        autoEstimateLastSampleMs = 0L
        autoEstimateLastKm = latestRouteKm
        autoEstimateLastElevation = Double.NaN
        autoEstimateLastSpeedKmh = latestSpeedKmh
        autoEstimateSmoothedGradePct = 0.0
        autoEstimateSmoothedAccel = 0.0
        autoEstimateSocAnchor = null
        autoEstimateSocAnchorKm = latestRouteKm
        autoEstimateWhPerKm = null
        lastLoggedAutoEstimate = null
        lastRenderedAutoEstimate = null
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

    private fun plannedChargeKms(): List<Double> = if (::basePlan.isInitialized) {
        basePlan.checkpoints.filter { it.chargeToPct != null }.map { it.km }
    } else emptyList()

    /** v0.27.3: first-page route axis is distance/charging based, never battery based. */
    private fun configureRideRouteVisuals() {
        if (!::course.isInitialized) return
        val chargeKms = plannedChargeKms()
        profileView.setCourse(course)
        rideMiniProfileView.setCourse(course)
        rideMiniProfileView.setCompactMode(true)
        rideMiniProfileView.setCheckpointKms(chargeKms)
        seekRideRoute.setCourse(course.totalKm, chargeKms)
    }

    /**
     * The test-mode switch and virtual GPX location moved from Settings to the ride HUD.
     * In normal riding the same control becomes a read-only live course-distance axis.
     */
    private fun setupRidePositionControls() {
        switchRideTestMode.setOnCheckedChangeListener { _, checked ->
            if (refreshingRideControls) return@setOnCheckedChangeListener
            if (logManager.isActive()) {
                refreshRidePositionControls()
                Toast.makeText(this, "주행 중에는 테스트 모드를 바꿀 수 없습니다. 테스트 주행에서는 위치 슬라이더만 계속 움직일 수 있습니다.", Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            AppSettings.prefs(this).edit().putBoolean(AppSettings.KEY_TEST_MODE, checked).apply()
            testMode = checked
            refreshRidePositionControls()
            renderCurrentMode()
        }
        seekRideRoute.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || refreshingRideControls || !testMode || !::course.isInitialized) return
                val km = seekRideRoute.routeKmForProgress(progress).coerceIn(0.0, course.totalKm)
                AppSettings.prefs(this@MainActivity).edit().putFloat(AppSettings.KEY_TEST_KM, km.toFloat()).apply()
                latestRouteKm = km
                renderCurrentMode()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun refreshRidePositionControls() {
        if (!::course.isInitialized) return
        refreshingRideControls = true
        try {
            switchRideTestMode.visibility = View.VISIBLE
            switchRideTestMode.isChecked = AppSettings.testMode(this)
            switchRideTestMode.isEnabled = !logManager.isActive()
            seekRideRoute.visibility = View.VISIBLE
            seekRideRoute.setUserSeekingEnabled(testMode)
            val km = if (testMode) AppSettings.testKm(this).coerceIn(0.0, course.totalKm) else latestRouteKm.coerceIn(0.0, course.totalKm)
            seekRideRoute.setRouteKm(km)
            rideMiniProfileView.visibility = View.VISIBLE
            rideMiniProfileView.setCurrentKm(km)
        } finally {
            refreshingRideControls = false
        }
    }

    private fun renderRideRouteVisual(km: Double, simulated: Boolean) {
        if (!::course.isInitialized) return
        rideMiniProfileView.visibility = View.VISIBLE
        seekRideRoute.visibility = View.VISIBLE
        switchRideTestMode.visibility = View.VISIBLE
        switchRideTestMode.isEnabled = !logManager.isActive()
        rideMiniProfileView.setCurrentKm(km)
        seekRideRoute.setRouteKm(km)
        seekRideRoute.setUserSeekingEnabled(testMode)

        val nextCharge = activePlannedChargeCheckpoints().firstOrNull { it.km > km + 0.08 }
        val targetKm = nextCharge?.km ?: course.totalKm
        val remain = (targetKm - km).coerceAtLeast(0.0)
        val prefix = if (simulated) "테스트" else "거리"
        tvRideRouteScale.text = if (nextCharge != null) {
            "$prefix ${RideFormatter.one(km)}/${RideFormatter.one(course.totalKm)}km · 다음충전 ${RideFormatter.one(nextCharge.km)} · ${RideFormatter.one(remain)}km 남음"
        } else {
            "$prefix ${RideFormatter.one(km)}/${RideFormatter.one(course.totalKm)}km · 종점 ${RideFormatter.one(course.totalKm)} · ${RideFormatter.one(remain)}km 남음"
        }
    }

    /** v0.27.4: skipped stations are not segment boundaries on the live mini profile. */
    private fun activePlannedChargeCheckpoints(): List<Checkpoint> = basePlan.checkpoints
        .filter { it.chargeToPct != null && !replanStore.isSkipped(courseMeta.id, it.km) }
        .sortedBy { it.km }

    /**
     * v0.27.5
     * - Test mode keeps the whole GPX.
     * - Real riding zooms to the current charge-to-charge leg + a small look-ahead after the next station.
     * - The blue line is ALWAYS the reach limit at the normal reserve target (default 15%).
     * - The red line is the last-resort hard-reserve reach (default 7%) and appears only when
     *   a planned charge is imminent or the replan logic is already in ECO/emergency territory.
     */
    private fun renderRideMiniProfileContext(km: Double, simulated: Boolean, context: EtaChargeContext) {
        if (!::course.isInitialized) return
        rideMiniProfileView.setCurrentKm(km)

        val charges = activePlannedChargeCheckpoints()
        val next = charges.firstOrNull { it.km > km + 0.08 }
        val targetKm = next?.km ?: course.totalKm

        if (simulated || !logManager.isActive()) {
            rideMiniProfileView.setWindow(null, null)
        } else {
            val previousKm = charges.lastOrNull { it.km <= km + 0.12 }?.km ?: 0.0
            val followingKm = charges.firstOrNull { it.km > targetKm + 0.08 }?.km ?: course.totalKm
            val availableAfterTarget = (followingKm - targetKm).coerceAtLeast(0.0)
            val extraAfterTarget = if (next != null && availableAfterTarget > 0.05) {
                (availableAfterTarget * 0.20).coerceIn(2.0, 5.0).coerceAtMost(availableAfterTarget)
            } else 0.0
            val windowEnd = (targetKm + extraAfterTarget).coerceAtMost(course.totalKm)
            rideMiniProfileView.setWindow(previousKm, windowEnd)
        }

        layoutRideReachMargins.visibility = View.VISIBLE
        val visualSoc = if (simulated) plan.estimate(km).percent else currentSocForReplan(km)
        val hard = AppSettings.hardReserve(this)
        val decision = computeReplanDecision(km, context, visualSoc)
        val nextChargeRemainKm = next?.let { (it.km - km).coerceAtLeast(0.0) }
        val chargeImminent = nextChargeRemainKm != null && nextChargeRemainKm <= 5.0
        val emergencyActive = replanStore.active(courseMeta.id) != null

        val recommendedReachKm = operationalReachLimitKm(km, visualSoc, finishTargetPct)
        val showHard = chargeImminent ||
            decision.kind == ReplanDecisionKind.ECO_CONNECT ||
            decision.kind == ReplanDecisionKind.EMERGENCY ||
            emergencyActive ||
            visualSoc <= hard + 0.5
        val hardReachKm = if (showHard) operationalReachLimitKm(km, visualSoc, hard.toDouble()) else null
        rideMiniProfileView.setReachLimits(recommendedReachKm, hardReachKm)
        renderReachMarginText(next, recommendedReachKm, hardReachKm, hard)
    }

    private fun renderReachMarginText(next: Checkpoint?, recommendedReachKm: Double, hardReachKm: Double?, hardReserve: Int) {
        fun signed(delta: Double): String = "${if (delta >= 0.0) "+" else ""}${RideFormatter.one(delta)}km"
        if (next != null) {
            tvSafeReachMargin.text = "안전 ${finishTargetPct.roundToInt()}% · 다음충전 ${signed(recommendedReachKm - next.km)}"
            if (hardReachKm != null) {
                tvHardReachMargin.visibility = View.VISIBLE
                tvHardReachMargin.text = "하드 ${hardReserve}% · ${signed(hardReachKm - next.km)}"
            } else {
                tvHardReachMargin.visibility = View.GONE
            }
        } else {
            val remainToFinish = course.totalKm - recommendedReachKm
            tvSafeReachMargin.text = if (remainToFinish <= 0.05) {
                "안전 ${finishTargetPct.roundToInt()}% · 종점 도달"
            } else {
                "안전 ${finishTargetPct.roundToInt()}% · 종점 -${RideFormatter.one(remainToFinish)}km"
            }
            if (hardReachKm != null) {
                val hardRemain = course.totalKm - hardReachKm
                tvHardReachMargin.visibility = View.VISIBLE
                tvHardReachMargin.text = if (hardRemain <= 0.05) "하드 ${hardReserve}% · 종점 도달" else "하드 ${hardReserve}% · -${RideFormatter.one(hardRemain)}km"
            } else {
                tvHardReachMargin.visibility = View.GONE
            }
        }
    }

    /**
     * Farthest GPX km reachable from current SOC while preserving the requested reserve.
     * Intermediate planned charging is deliberately ignored: this answers "with the battery I have now, how far?".
     */
    private fun operationalReachLimitKm(currentKm: Double, currentSoc: Double, reservePct: Double): Double {
        val start = currentKm.coerceIn(0.0, course.totalKm)
        val usablePct = (currentSoc - reservePct).coerceAtLeast(0.0)
        if (usablePct <= 0.01) return start
        val factor = (plan.calibration(start)?.factor ?: 1.0).coerceIn(0.65, 1.65)
        fun useTo(km: Double): Double = basePlan.internalConsumption(start, km.coerceIn(start, course.totalKm)) * factor
        if (useTo(course.totalKm) <= usablePct) return course.totalKm

        var lo = start
        var hi = course.totalKm
        repeat(28) {
            val mid = (lo + hi) / 2.0
            if (useTo(mid) <= usablePct) lo = mid else hi = mid
        }
        return lo.coerceIn(start, course.totalKm)
    }

    private fun renderRideVisualWarning(km: Double, simulated: Boolean, context: EtaChargeContext) {
        if ((!simulated && !logManager.isActive()) || context.activeCharge != null) {
            hideRideVisualWarning()
            return
        }

        val visualSoc = if (simulated) plan.estimate(km).percent else currentSocForReplan(km)
        val hard = AppSettings.hardReserve(this)
        val decision = computeReplanDecision(km, context, visualSoc)
        val charges = activePlannedChargeCheckpoints()
        val nextCharge = charges.firstOrNull { it.km > km + 0.08 }
        val remain = nextCharge?.let { (it.km - km).coerceAtLeast(0.0) }
        val emergencySession = replanStore.active(courseMeta.id)

        val stage = when {
            visualSoc <= hard + 0.5 -> RideWarningStage.HARD_RESERVE
            emergencySession != null || decision.kind == ReplanDecisionKind.EMERGENCY -> RideWarningStage.EMERGENCY
            decision.kind == ReplanDecisionKind.ECO_CONNECT -> RideWarningStage.ECO_CONNECT
            remain != null && remain <= 5.0 -> RideWarningStage.CHARGE_IMMINENT
            else -> RideWarningStage.NONE
        }
        if (stage == RideWarningStage.NONE) {
            hideRideVisualWarning()
            return
        }

        val target = decision.target ?: nextCharge
        val prefix = if (simulated) "테스트 · " else ""
        when (stage) {
            RideWarningStage.CHARGE_IMMINENT -> {
                layoutRideWarningBanner.setBackgroundResource(R.drawable.ride_warning_orange_bg)
                tvRideWarningTitle.text = "${prefix}⚠ 충전 임박"
                tvRideWarningReason.text = if (decision.kind == ReplanDecisionKind.SKIP_AVAILABLE && decision.skipNextTarget != null) {
                    "${nextCharge?.name ?: "충전소"} ${RideFormatter.one(remain ?: 0.0)}km · 현재 계산상 충전 생략 가능"
                } else {
                    "${nextCharge?.name ?: "다음 충전소"} ${RideFormatter.one(remain ?: 0.0)}km · 파란 안전선과 충전소 위치를 확인하세요."
                }
            }
            RideWarningStage.ECO_CONNECT -> {
                layoutRideWarningBanner.setBackgroundResource(R.drawable.ride_warning_orange_bg)
                tvRideWarningTitle.text = "${prefix}⚠ 충전 권장 · ECO 연결"
                tvRideWarningReason.text = "${target?.name ?: "다음 지점"} 도착예상 ${decision.predictedPct.roundToInt()}% · 안전 기준 ${finishTargetPct.roundToInt()}% 아래"
            }
            RideWarningStage.EMERGENCY -> {
                layoutRideWarningBanner.setBackgroundResource(R.drawable.ride_warning_red_bg)
                tvRideWarningTitle.text = "${prefix}🚨 긴급 충전"
                tvRideWarningReason.text = emergencySession?.let {
                    "${it.candidateName} 비상 절차 진행 중 · 하드 리저브 ${hard}% 빨간선을 넘기지 마세요."
                } ?: "${target?.name ?: "다음 지점"} 도착예상 ${decision.predictedPct.roundToInt()}% · 하드 리저브 ${hard}% 미만"
            }
            RideWarningStage.HARD_RESERVE -> {
                layoutRideWarningBanner.setBackgroundResource(R.drawable.ride_warning_hard_bg)
                tvRideWarningTitle.text = "${prefix}🚨 하드 리저브 진입"
                tvRideWarningReason.text = "현재 ${visualSoc.roundToInt()}% · 하드 리저브 ${hard}% · 주행 연장보다 즉시 충전을 우선하세요."
            }
            else -> Unit
        }
        layoutRideWarningBanner.visibility = View.VISIBLE

        val warningKey = "${stage.name}:${target?.km?.let { (it * 10).roundToInt() } ?: -1}"
        if (warningKey != lastRideWarningKey) {
            lastRideWarningKey = warningKey
            when (stage) {
                RideWarningStage.CHARGE_IMMINENT, RideWarningStage.ECO_CONNECT -> pulseWarningBanner(strong = false)
                RideWarningStage.EMERGENCY -> {
                    pulseWarningBanner(strong = true)
                    flashRidePage(getColor(R.color.danger), strong = false)
                    if (!simulated) vibrateRideWarning(260L, 170)
                }
                RideWarningStage.HARD_RESERVE -> {
                    pulseWarningBanner(strong = true)
                    flashRidePage(getColor(R.color.danger), strong = true)
                    if (!simulated) vibrateRideWarning(500L, 230)
                }
                else -> Unit
            }
        }
    }

    private fun hideRideVisualWarning() {
        layoutRideWarningBanner.visibility = View.GONE
        layoutRideWarningBanner.clearAnimation()
        pageRideRoot.setBackgroundColor(Color.TRANSPARENT)
        lastRideWarningKey = ""
    }

    private fun pulseWarningBanner(strong: Boolean) {
        layoutRideWarningBanner.clearAnimation()
        val pulse = AlphaAnimation(if (strong) 0.28f else 0.55f, 1f).apply {
            duration = if (strong) 280L else 360L
            repeatMode = Animation.REVERSE
            repeatCount = if (strong) 3 else 2
        }
        layoutRideWarningBanner.startAnimation(pulse)
    }

    private fun flashRidePage(color: Int, strong: Boolean) {
        val alpha = if (strong) 105 else 55
        val flash = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
        ValueAnimator.ofObject(ArgbEvaluator(), Color.TRANSPARENT, flash, Color.TRANSPARENT).apply {
            duration = if (strong) 900L else 700L
            repeatCount = if (strong) 1 else 0
            addUpdateListener { pageRideRoot.setBackgroundColor(it.animatedValue as Int) }
            start()
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrateRideWarning(durationMs: Long, amplitude: Int) {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude.coerceIn(1, 255)))
        } else {
            vibrator.vibrate(durationMs)
        }
    }

    private fun setupInlineSettings() {
        seekPageDistanceInterval.max = 50
        seekPageTimeInterval.max = 120
        seekPageFinishTarget.max = 98
        seekPageHardReserve.max = 10 // 5~15%

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
        seekPageHardReserve.setOnSeekBarChangeListener(simpleSeekListener { value ->
            if (refreshingSettingsUi) return@simpleSeekListener
            val pct = (value + 5).coerceIn(5, 15)
            AppSettings.prefs(this).edit().putInt(AppSettings.KEY_HARD_RESERVE, pct).apply()
            updateInlineSettingsLabels()
            if (::plan.isInitialized) renderAtKm(latestRouteKm, testMode)
        })
        btnPageChargeSimulator.setOnClickListener {
            if (logManager.isActive()) {
                Toast.makeText(this, "실제 주행 기록 중에는 시뮬레이터를 열 수 없습니다.", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this, ChargeScenarioSimulatorActivity::class.java))
            }
        }
        btnPageBleDiagnostic.setOnClickListener {
            startActivity(Intent(this, BleDiagnosticActivity::class.java))
        }
        findViewById<Button>(R.id.btnPageSramDiagnostic).setOnClickListener {
            startActivity(Intent(this, SramBleActivity::class.java))
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
            seekPageHardReserve.progress = AppSettings.hardReserve(this).coerceIn(5, 15) - 5
            btnPageChargeSimulator.isEnabled = !logManager.isActive()
            btnPageResetProgress.isEnabled = !logManager.isActive()
            tvPageSettingsHint.text = if (logManager.isActive()) {
                "주행 중입니다. 테스트 위치 조작은 첫 페이지 거리축에서만 가능하며, 음성 안내와 충전 권장 기준은 즉시 반영됩니다."
            } else {
                "테스트 모드와 GPX 위치 슬라이더는 첫 페이지로 이동했습니다. 선택 코스 · ${courseMeta.name}"
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
        tvPageFinishTarget.text = "충전권장 기준 잔량 ${seekPageFinishTarget.progress + 1}%"
        tvPageHardReserve.text = "비상 하드 리저브 ${seekPageHardReserve.progress + 5}% · 이 아래면 긴급 충전 탐색"
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
                replanStore.clearCourse(courseMeta.id)
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
            putExtra(RecognizerIntent.EXTRA_PROMPT, if (logManager.isFreeRide()) "임의주행 · 배터리 48프로야 · 현재 상태 · 주행 종료" else "자연스럽게 말하세요 · 배터리 48프로야 · 충전 권장 20 · 5킬로마다 알려줘 · 앞에 업힐 있어?")
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
                speakText("충전권장 기준 잔량을 ${finishTargetPct.roundToInt()}퍼센트로 설정했습니다.")
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
        val emergency = if (!logManager.isFreeRide()) replanStore.active(courseMeta.id) else null
        basePlan.checkpointAt(km, 0.35)?.takeIf { it.chargeToPct != null }?.let { replanStore.unskip(courseMeta.id, it.km) }
        if (emergency != null && emergency.phase == EmergencyPhase.OUTBOUND) {
            replanStore.setPhase(courseMeta.id, EmergencyPhase.CHARGING, now)
        }
        actualStore.save(pct.toDouble(), km, ActualEntryKind.ARRIVAL, now, ActualEntrySource.CHARGE)
        val advice = if (!logManager.isFreeRide() && emergency == null) {
            tripPlanner.adviceAtStation(km, finishTargetPct, plan.calibration(km)?.factor ?: 1.0, pct.toDouble())
        } else null
        val alertTarget = when {
            emergency != null -> emergencyRecommendedChargeTargetPct(emergency)
            advice != null -> advice.userTargetPct.roundToInt().coerceIn(1, 100)
            else -> AppSettings.chargeAlertTarget(this)
        }
        chargingSessionStore.start(km, pct.toDouble(), now, alertTarget)
        if (logManager.isActive()) {
            val detail = when {
                emergency != null -> " · 비상충전 · 권장 ${alertTarget}% · 복귀앵커 ${RideFormatter.one(emergency.anchorRouteKm)}km"
                advice != null -> " · 내계획 ${advice.userTargetPct.roundToInt()}% · 앱권장 ${advice.appRecommendedPct.roundToInt()}% · 기준잔량 ${finishTargetPct.roundToInt()}%"
                else -> ""
            }
            val alertDetail = if (AppSettings.chargeAlertEnabled(this)) " · 알림 ${alertTarget}%" else " · 충전알림 꺼짐"
            logManager.recordEvent(if (emergency != null) "EMERGENCY_CHARGE_START" else "CHARGE_START", "충전 시작 · $pct%$detail$alertDetail", km, pct.toDouble())
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
        val emergency = if (!logManager.isFreeRide()) replanStore.active(courseMeta.id) else null
        chargingSessionStore.clear()
        if (emergency != null && emergency.phase == EmergencyPhase.CHARGING) {
            replanStore.recordSuccessfulPlace(emergency)
            replanStore.setPhase(courseMeta.id, EmergencyPhase.RETURN, now)
            logManager.recordEvent("EMERGENCY_CHARGE_COMPLETE", "${emergency.candidateName} 비상충전 완료 · 이제 ${RideFormatter.one(emergency.anchorRouteKm)}km 이탈점 복귀", emergency.anchorRouteKm, pct.toDouble())
        }
        renderRideState()
        if (logManager.isFreeRide()) renderFreeRide() else renderAtKm(latestRouteKm, testMode)
        if (emergency != null && emergency.phase == EmergencyPhase.CHARGING) {
            val updated = replanStore.active(courseMeta.id)
            AlertDialog.Builder(this)
                .setTitle("충전 완료 · 경기코스로 복귀")
                .setMessage("원래 코스 이탈점 ${RideFormatter.one(emergency.anchorRouteKm)}km로 반드시 돌아간 뒤 경기를 이어가세요. 50m 이내로 복귀하면 앱이 자동으로 원래 GPX 진행을 재개합니다.")
                .setPositiveButton("이탈점 길안내") { _, _ -> openExternalRoute(updated?.returnUrl ?: emergency.returnUrl) }
                .setNegativeButton("잠시 후", null)
                .show()
        } else {
            Toast.makeText(this, "충전 완료 · $pct%", Toast.LENGTH_SHORT).show()
        }
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

    private fun setupMobileReleasePage() {
        val repo = UpdateManager.repository().ifBlank { BuildConfig.UPDATE_REPOSITORY.orEmpty() }
        tvPageMobileReleaseStatus.text = buildString {
            append("현재 v${appVersionName()}")
            if (repo.isNotBlank()) append(" · $repo")
            append("\n새 소스 ZIP을 휴대폰에서 바로 GitHub main으로 배포할 수 있습니다.")
        }
        btnPageMobileRelease.setOnClickListener {
            startActivity(Intent(this, ReleaseUploaderActivity::class.java))
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
                if (dx < 0) showPagerChild((pagerFlipper.displayedChild + 1).coerceAtMost(5))
                else showPagerChild((pagerFlipper.displayedChild - 1).coerceAtLeast(0))
                return true
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val settingsSliderTouch = ::pagerFlipper.isInitialized && pagerFlipper.displayedChild == 2 && listOf(
            seekPageDistanceInterval, seekPageTimeInterval, seekPageFinishTarget, seekPageHardReserve
        ).any { isTouchInside(ev, it) }
        val rideTestSliderTouch = ::pagerFlipper.isInitialized && pagerFlipper.displayedChild == 0 && testMode &&
            ::seekRideRoute.isInitialized && isTouchInside(ev, seekRideRoute)
        if (::pagerGesture.isInitialized && !settingsSliderTouch && !rideTestSliderTouch) pagerGesture.onTouchEvent(ev)
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
        val target = index.coerceIn(0, 5)
        if (target == pagerFlipper.displayedChild) return
        pagerFlipper.displayedChild = target
        updatePagerIndicator()
    }

    private fun updatePagerIndicator() {
        val labels = arrayOf("주행", "코스", "설정", "학습", "피드백", "배포")
        val dots = (0..5).joinToString("  ") { if (it == pagerFlipper.displayedChild) "●" else "○" }
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
            " ${it.stationName}에서 내 충전 계획 ${it.userTargetPct.roundToInt()}퍼센트, 앱 권장 ${it.appRecommendedPct.roundToInt()}퍼센트입니다. 권장은 다음 ${it.nextTargetName}에 ${it.requiredArrivalPctAtNext.roundToInt()}퍼센트를 남기는 기준입니다."
        }.orEmpty()
        speakText("현재 ${RideFormatter.one(km)}킬로미터. 예상 배터리 ${battery.percent.roundToInt()}퍼센트. 상태 ${reserve.label}. 종점 예상 ${plan.forecast(km, course.totalKm).percent.roundToInt()}퍼센트. 기준 잔량 ${finishTargetPct.roundToInt()}퍼센트. $elevText$cpText$chargeText ${pacing.voiceText}")
    }

    private fun speakNextCheckpoint() {
        val cp = plan.currentOrNextCheckpoint(latestRouteKm) ?: return speakText("종점에 도착했습니다.")
        val remain = (cp.km - latestRouteKm).coerceAtLeast(0.0)
        val predicted = plan.forecast(latestRouteKm, cp.km).percent
        speakText("${cp.name}까지 ${RideFormatter.one(remain)}킬로미터. 예상 배터리 ${predicted.roundToInt()}퍼센트입니다.")
    }

    private fun speakFinishInfo() {
        speakText("종점까지 ${RideFormatter.one((course.totalKm - latestRouteKm).coerceAtLeast(0.0))}킬로미터. 종점 예상 배터리 ${plan.forecast(latestRouteKm, course.totalKm).percent.roundToInt()}퍼센트, 기준 잔량 ${finishTargetPct.roundToInt()}퍼센트입니다.")
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
        speakText("자연스럽게 말하세요. 지금 배터리 48프로야, 충전 권장 20프로, 5킬로마다 알려줘, 10분마다 알려줘, 앞에 업힐 있어, 종점까지 얼마나 남았어, 여기를 보급소로 등록해처럼 말할 수 있습니다.")
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
        setComparisonWithProjected(
            tvCompareModel,
            formatPct(snapshot.modelConsumedPct),
            "자체 ${formatPct(snapshot.modelProjectedTotalPct)}"
        )
        val avinoxConsumed = snapshot.avinoxConsumedPct?.let(::formatPct) ?: "—"
        val avinoxProjected = snapshot.avinoxProjectedTotalPct?.let { total ->
            val mode = snapshot.avinoxMode?.name?.takeIf { it.isNotBlank() }
            if (mode != null) "$mode ${formatPct(total)}" else formatPct(total)
        }
        setComparisonWithProjected(tvCompareAvinox, avinoxConsumed, avinoxProjected)
        tvCompareDetail.text = ""
    }

    private fun setComparisonWithProjected(view: TextView, main: String, projected: String?) {
        // v0.26.1: 긴 예상 총량 문구가 3분할 박스에서 잘리지 않도록
        // 괄호 보조값은 항상 둘째 줄에 표시한다.
        val suffix = projected?.takeIf { it.isNotBlank() }?.let { "\n($it)" }.orEmpty()
        val full = main + suffix
        val span = SpannableString(full)
        if (suffix.isNotEmpty()) {
            span.setSpan(RelativeSizeSpan(0.58f), main.length, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        view.text = span
    }

    private fun renderAtKm(kmValue: Double, simulated: Boolean) {
        profileView.visibility = View.VISIBLE
        val km = kmValue.coerceIn(0.0, course.totalKm)
        latestRouteKm = km
        val point = course.pointAtKm(km)
        val battery = plan.estimate(km)
        val range = plan.confidenceRange(km)
        // v0.26.5: 충전소에 도착한 뒤에는 현재 충전정보는 별도 충전 상태 카드가 담당하고,
        // '다음 지점' 카드는 그 다음 충전소/종점을 보여준다.
        val currentCheckpoint = basePlan.checkpointAt(km, 0.15)
        val cp = if (currentCheckpoint?.chargeToPct != null && !replanStore.isSkipped(courseMeta.id, currentCheckpoint.km)) {
            nextReplanTarget(currentCheckpoint.km) ?: currentCheckpoint
        } else {
            nextReplanTarget(km - 0.12)
        }
        val poi = course.nextPoi(km)
        val stats10 = course.elevationAhead(km, 10.0)
        val battery10TargetKm = (km + 10.0).coerceAtMost(course.totalKm)
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
        renderRideRouteVisual(km, simulated)
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
        tvRiskDetail.text = "${reserve.targetName} 예상 ${reserve.predictedPct.roundToInt()}%\n기준 ${reserve.targetPct.roundToInt()}%   $differenceText"

        // 하위 호환용 숨김 뷰. 실제 SOC는 이제 상단의 큰 배터리 카드가 담당한다.
        tvActualBattery.text = displaySoc?.let { "$it%" } ?: "—"
        renderBleStatusLine()

        val etaSpeed = pointEtaSpeedKmh()
        // 한 화면 갱신 동안 실제배터리/충전상태를 한 번만 읽어 ETA 다중 포인트 계산이 UI를 무겁게 하지 않게 한다.
        val etaContext = etaChargeContext()
        renderReplanDecision(km, etaContext)
        renderRideMiniProfileContext(km, simulated, etaContext)
        renderRideVisualWarning(km, simulated, etaContext)
        val remainFinish = (course.totalKm - km).coerceAtLeast(0.0)
        tvSpeed.text = if (latestSpeedKmh >= 2.0) "속도 ${RideFormatter.one(latestSpeedKmh)}km/h" else "속도 -"
        tvFinishEta.text = "종점 ${RideFormatter.one(remainFinish)}km · ${chargeAwareEtaClock(km, course.totalKm, etaSpeed, etaContext)}"

        if (cp != null) {
            val remain = (cp.km - km).coerceAtLeast(0.0)
            val atCurrent = abs(cp.km - km) <= 0.15
            val predicted = replannedProjectedSoc(
                km,
                cp.km,
                currentSocForReplan(km),
                etaContext
            ).roundToInt()
            val etaText = chargeAwareEtaClock(km, cp.km, etaSpeed, etaContext)
            tvNextCheckpoint.text = if (atCurrent) "현재 · ${cp.name}" else cp.name
            val chargeAdvice = if (cp.chargeToPct != null) {
                tripPlanner.adviceAtStation(
                    cp.km,
                    finishTargetPct,
                    plan.calibration(km)?.factor ?: 1.0,
                    predicted.toDouble()
                )
            } else null
            tvNextCheckpointDetail.text = when {
                cp.chargeToPct != null && chargeAdvice != null -> buildString {
                    append(if (atCurrent) "현재 지점" else "${RideFormatter.one(remain)} km 남음")
                    append("\nETA $etaText · 도착예상 $predicted%")
                    append("\n내 계획 ${chargeAdvice.userTargetPct.roundToInt()}% · 앱권장 ${chargeAdvice.appRecommendedPct.roundToInt()}%")
                    val depart = chargeAwareDepartureClock(km, cp, etaSpeed, etaContext)
                    append("\n예상 출발 $depart")
                    if (!chargeAdvice.feasibleAt100) append("\n⚠ 100%로도 ${chargeAdvice.shortagePctAt100.roundToInt()}% 부족")
                }
                cp.chargeToPct != null -> "${if (atCurrent) "현재 지점" else "${RideFormatter.one(remain)} km 남음"}\nETA $etaText · 도착예상 $predicted%\n내 충전 계획 ${cp.chargeToPct.roundToInt()}%\n예상 출발 ${chargeAwareDepartureClock(km, cp, etaSpeed, etaContext)}"
                cp.km >= course.totalKm - 0.05 -> "${RideFormatter.one(remain)} km 남음\n종점예상 $predicted%\nETA $etaText"
                else -> "${RideFormatter.one(remain)} km 남음\n예상 $predicted%\nETA $etaText"
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

        val battery10Pct = replannedProjectedSoc(km, battery10TargetKm, currentSocForReplan(km), etaContext)
        tvTenKmBattery.text = "10km 후 ${battery10Pct.roundToInt()}% · 실시간 재계획"
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
        tvNextPoi.text = poi?.let {
            val remainPoi = (it.routeKm - km).coerceAtLeast(0.0)
            "다음 포인트 · ${it.name} · ${RideFormatter.one(remainPoi)}km · ${chargeAwareEtaClock(km, it.routeKm, etaSpeed, etaContext)}"
        } ?: "다음 포인트 · 종점 · ${chargeAwareEtaClock(km, course.totalKm, etaSpeed, etaContext)}"
        renderPointEtas(km, etaSpeed, etaContext)

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

    private data class EtaChargeContext(
        val entries: List<ActualBatteryEntry>,
        val activeCharge: ActiveChargeSession?,
        val liveSocPct: Double?,
        val nowMs: Long
    )

    private fun etaChargeContext(): EtaChargeContext = EtaChargeContext(
        entries = actualStore.entries(),
        activeCharge = chargingSessionStore.active(),
        liveSocPct = freshBleSoc()?.toDouble(),
        nowMs = System.currentTimeMillis()
    )

    /** v0.26.5: 현재 주행에서 해당 충전소가 이미 완료됐는지 확인한다. */
    private fun chargeCompletedAt(stationKm: Double, context: EtaChargeContext): Boolean =
        replanStore.isSkipped(courseMeta.id, stationKm) || context.entries.any {
            it.kind == ActualEntryKind.POST_CHARGE && abs(it.routeKm - stationKm) <= 0.35
        }

    /** 현재 위치가 아직 처리해야 할 충전소라면 반환한다. */
    private fun pendingChargeCheckpointAt(currentKm: Double, context: EtaChargeContext): Checkpoint? {
        val cp = basePlan.checkpointAt(currentKm, 0.18)?.takeIf { it.chargeToPct != null } ?: return null
        return cp.takeUnless { chargeCompletedAt(it.km, context) }
    }

    /**
     * 특정 충전소에서 '지금부터' 계획 목표까지 남은 충전시간.
     * - 충전 중: BLE SOC를 최우선으로 사용해 남은 시간만 계산
     * - BLE가 없으면 실제 경과시간만큼 차감
     * - 미래 충전소: 해당 구간의 예상 도착 SOC → 사용자 계획 SOC로 계산
     */
    private fun remainingChargeMinutesAt(cp: Checkpoint, currentKm: Double, context: EtaChargeContext): Double {
        val target = cp.chargeToPct ?: return 0.0
        if (replanStore.isSkipped(courseMeta.id, cp.km) || chargeCompletedAt(cp.km, context)) return 0.0

        val active = context.activeCharge?.takeIf { abs(it.routeKm - cp.km) <= 0.35 }
        if (active != null) {
            val liveSoc = context.liveSocPct
            if (liveSoc != null) return AvinoxChargeCurve.minutesBetween(liveSoc, target)
            val total = AvinoxChargeCurve.minutesBetween(active.arrivalPct, target)
            val elapsed = ((context.nowMs - active.startMs).coerceAtLeast(0L) / 60_000.0)
            return (total - elapsed).coerceAtLeast(0.0)
        }

        if (abs(cp.km - currentKm) <= 0.18) {
            val currentSoc = context.liveSocPct
                ?: context.entries.lastOrNull { abs(it.routeKm - cp.km) <= 0.35 }?.percent
                ?: plan.estimate(cp.km).percent
            return AvinoxChargeCurve.minutesBetween(currentSoc, target)
        }

        val arrival = replannedProjectedSoc(
            currentKm,
            cp.km,
            currentSocForReplan(currentKm),
            context
        )
        return AvinoxChargeCurve.minutesBetween(arrival, target)
    }

    /** target 지점 '도착 전'에 거쳐야 하는 모든 미완료 계획 충전시간을 누적한다. */
    private fun pendingChargeMinutesBefore(currentKm: Double, targetKm: Double, context: EtaChargeContext): Double {
        if (targetKm <= currentKm + 0.05) return 0.0
        return basePlan.checkpoints
            .asSequence()
            .filter { it.chargeToPct != null }
            .filter { it.km >= currentKm - 0.18 && it.km < targetKm - 0.05 }
            .sumOf { remainingChargeMinutesAt(it, currentKm, context) }
    }

    /** 주행시간 + target 이전의 남은 계획 충전시간을 모두 포함한 도착 ETA. */
    private fun chargeAwareEtaClock(currentKm: Double, targetKm: Double, speedKmh: Double, context: EtaChargeContext): String {
        val remain = (targetKm - currentKm).coerceAtLeast(0.0)
        val chargeMin = pendingChargeMinutesBefore(currentKm, targetKm, context)
        val emergencyMin = emergencyDetourRemainingMinutes(targetKm, context)
        return RideFormatter.etaClock(remain, speedKmh, chargeMin + emergencyMin)
    }

    /** 충전소 도착 ETA + 그 충전소 자체의 계획 충전시간까지 포함한 예상 출발시각. */
    private fun chargeAwareDepartureClock(currentKm: Double, cp: Checkpoint, speedKmh: Double, context: EtaChargeContext): String {
        val remain = (cp.km - currentKm).coerceAtLeast(0.0)
        val before = pendingChargeMinutesBefore(currentKm, cp.km, context)
        val atStation = remainingChargeMinutesAt(cp, currentKm, context)
        val emergencyMin = emergencyDetourRemainingMinutes(cp.km, context)
        return RideFormatter.etaClock(remain, speedKmh, before + atStation + emergencyMin)
    }

    /** v0.26.4: 순간속도보다 주행 전체 이동평균을 우선해 포인트 ETA가 출렁이지 않게 한다. */
    private fun pointEtaSpeedKmh(): Double {
        val avg = logManager.activeAverageSpeedKmh()
        return when {
            avg >= 3.0 -> avg
            latestSpeedKmh >= 3.0 -> latestSpeedKmh
            else -> 0.0
        }
    }

    /** v0.27.6: ETA 카드 상단의 주행/정차 요약 시간을 같은 형식으로 표시한다. */
    private fun compactHoursMinutes(totalMinutes: Double): String {
        val minutes = totalMinutes.coerceAtLeast(0.0).roundToInt()
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "${h}시간 ${m}분" else "${m}분"
    }

    /**
     * v0.27.6: 현재 위치부터 종점까지 남은 순수 주행시간과 계획상 정차시간을 분리해 보여준다.
     * 정차시간은 완료/생략된 충전은 제외하고 현재 충전 중이면 남은 충전시간만 반영한다.
     * 식사와 충전이 동시에 이뤄지는 지점은 현재 계획 데이터상 충전 정차시간으로 포함된다.
     */
    private fun renderPointEtaDurationSummary(km: Double, speedKmh: Double, context: EtaChargeContext) {
        val remainKm = (course.totalKm - km).coerceAtLeast(0.0)
        val rideMinutes = if (speedKmh >= 3.0) remainKm / speedKmh * 60.0 else null
        val stopMinutes = basePlan.checkpoints
            .asSequence()
            .filter { it.chargeToPct != null && it.km >= km - 0.18 }
            .sumOf { remainingChargeMinutesAt(it, km, context) }

        tvPointEtaRideTime.text = if (rideMinutes != null) {
            "주행 ${compactHoursMinutes(rideMinutes)}"
        } else {
            "주행 계산 중"
        }
        tvPointEtaStopTime.text = "정차 ${compactHoursMinutes(stopMinutes)}"
    }

    /**
     * 선택 GPX의 남은 모든 waypoint/POI를 한 번에 보여준다.
     * 등록 충전소는 ◆, GPX 보급계열 POI는 ◇, 일반 POI는 • 로 구분한다.
     */
    private data class PointEtaTimelineRow(
        val routeKm: Double,
        val poi: RoutePoi? = null,
        val chargingCheckpoint: Checkpoint? = null
    )

    /**
     * v0.26.7: 등록된 충전 계획을 GPX POI에 억지로 ±200m 매칭하지 않는다.
     * 충전 Checkpoint 자체를 ETA 시간축의 독립 이벤트로 넣고, 가까운 POI 이름은 보조 라벨로만 사용한다.
     * 따라서 등록 충전소가 GPX 보급 waypoint와 조금 어긋나 있어도 모든 충전시간이 표시/누적된다.
     */
    private fun renderPointEtas(km: Double, speedKmh: Double, context: EtaChargeContext) {
        renderPointEtaDurationSummary(km, speedKmh, context)

        val upcomingPois = course.pois
            .asSequence()
            .filter { it.routeKm >= km - 0.05 }
            .distinctBy { "${it.name}|${String.format(Locale.US, "%.3f", it.routeKm)}" }
            .sortedBy { it.routeKm }
            .toList()

        val plannedCharges = basePlan.checkpoints
            .asSequence()
            .filter { it.chargeToPct != null && it.km >= km - 0.18 }
            .sortedBy { it.km }
            .toList()

        // 충전소와 거의 같은 위치의 POI만 중복 제거한다. 충전소 자체는 항상 별도 행으로 남는다.
        val poiRows = upcomingPois
            .filter { poi -> plannedCharges.none { cp -> abs(cp.km - poi.routeKm) <= 0.12 } }
            .map { PointEtaTimelineRow(routeKm = it.routeKm, poi = it) }
        val chargeRows = plannedCharges.map { PointEtaTimelineRow(routeKm = it.km, chargingCheckpoint = it) }
        val timeline = (poiRows + chargeRows).sortedWith(
            compareBy<PointEtaTimelineRow> { it.routeKm }
                .thenBy { if (it.chargingCheckpoint != null) 0 else 1 }
        )

        val emergencyEta = replanStore.active(courseMeta.id) != null
        tvPointEtaBasis.text = if (speedKmh >= 3.0) {
            "이동평균 ${RideFormatter.one(speedKmh)}km/h + ${if (emergencyEta) "비상 우회/충전/복귀 + " else ""}남은 충전시간 포함 · 등록 충전소 ${plannedCharges.size}개 · 자동 재계산"
        } else {
            "이동속도가 잡히면 주행시간 + 계획/비상 충전시간을 합쳐 ETA를 계산합니다. · 등록 충전소 ${plannedCharges.size}개"
        }

        tvPointEtaList.text = if (timeline.isEmpty()) {
            "남은 포인트 없음 · 종점 ${chargeAwareEtaClock(km, course.totalKm, speedKmh, context)}"
        } else {
            timeline.joinToString("\n") { row ->
                val cp = row.chargingCheckpoint
                if (cp != null) {
                    val eta = chargeAwareEtaClock(km, cp.km, speedKmh, context)
                    val nearPoi = upcomingPois
                        .minByOrNull { abs(it.routeKm - cp.km) }
                        ?.takeIf { abs(it.routeKm - cp.km) <= 0.50 }
                    val nearbyName = nearPoi?.name
                        ?.takeIf { it.isNotBlank() && !cp.name.contains(it, ignoreCase = true) && !it.contains(cp.name, ignoreCase = true) }
                    val label = if (nearbyName != null) "${cp.name} · $nearbyName" else cp.name
                    if (replanStore.isSkipped(courseMeta.id, cp.km)) {
                        "◆ ${RideFormatter.one(cp.km)}km · 도착 $eta · $label\n   ↳ 충전 생략 확정 · 충전시간 0분 반영"
                    } else {
                        "◆ ${RideFormatter.one(cp.km)}km · 도착 $eta · $label\n   ↳ ${cp.chargeToPct!!.roundToInt()}% 충전 · 예상 출발 ${chargeAwareDepartureClock(km, cp, speedKmh, context)}"
                    }
                } else {
                    val p = row.poi!!
                    val mark = if (p.isSupplyLike()) "◇" else "•"
                    val eta = chargeAwareEtaClock(km, p.routeKm, speedKmh, context)
                    "$mark ${RideFormatter.one(p.routeKm)}km · $eta · ${p.name}"
                }
            }
        }
    }


    private enum class ReplanDecisionKind { NORMAL, SKIP_AVAILABLE, ECO_CONNECT, EMERGENCY }

    private data class ReplanDecision(
        val kind: ReplanDecisionKind,
        val target: Checkpoint?,
        val predictedPct: Double,
        val hardReservePct: Int,
        val skipCheckpoint: Checkpoint? = null,
        val skipNextTarget: Checkpoint? = null,
        val skipArrivalPct: Double? = null
    )

    private fun currentSocForReplan(km: Double): Double = (
        freshBleSoc()?.toDouble()
            ?: actualStore.latest()?.percent
            ?: plan.estimate(km).percent
        ).coerceIn(0.0, 100.0)

    private fun nextReplanTarget(afterKm: Double, extraSkipKm: Double? = null): Checkpoint? = basePlan.checkpoints.firstOrNull { cp ->
        cp.km > afterKm + 0.08 && (cp.chargeToPct == null || (
            !replanStore.isSkipped(courseMeta.id, cp.km) && (extraSkipKm == null || abs(cp.km - extraSkipKm) > 0.10)
        ))
    }

    /**
     * 실제 현재 SOC를 출발점으로 미래 SOC를 다시 계산한다.
     * 사용자가 생략 확정한 계획 충전소에서는 SOC를 계획값으로 리셋하지 않는다.
     */
    private fun replannedProjectedSoc(
        currentKm: Double,
        targetKm: Double,
        currentSoc: Double,
        context: EtaChargeContext,
        extraSkipKm: Double? = null
    ): Double {
        if (targetKm <= currentKm + 0.001) return currentSoc.coerceIn(0.0, 100.0)
        var soc = currentSoc.coerceIn(0.0, 100.0)
        var fromKm = currentKm
        val factor = (plan.calibration(currentKm)?.factor ?: 1.0).coerceIn(0.65, 1.65)
        val charges = basePlan.checkpoints
            .asSequence()
            .filter { it.chargeToPct != null }
            .filter { it.km > currentKm + 0.08 && it.km < targetKm - 0.05 }
            .sortedBy { it.km }
            .toList()
        for (cp in charges) {
            soc -= basePlan.internalConsumption(fromKm, cp.km) * factor
            val skipped = replanStore.isSkipped(courseMeta.id, cp.km) || (extraSkipKm != null && abs(cp.km - extraSkipKm) <= 0.10)
            if (!skipped) {
                val actualPost = context.entries.lastOrNull {
                    it.kind == ActualEntryKind.POST_CHARGE && abs(it.routeKm - cp.km) <= 0.35
                }?.percent
                soc = (actualPost ?: cp.chargeToPct!!).coerceIn(0.0, 100.0)
            }
            fromKm = cp.km
        }
        soc -= basePlan.internalConsumption(fromKm, targetKm) * factor
        return soc.coerceIn(0.0, 100.0)
    }

    private fun computeReplanDecision(km: Double, context: EtaChargeContext, currentSocOverride: Double? = null): ReplanDecision {
        val hard = AppSettings.hardReserve(this)
        val soc = (currentSocOverride ?: currentSocForReplan(km)).coerceIn(0.0, 100.0)
        val target = nextReplanTarget(km)
            ?: return ReplanDecision(ReplanDecisionKind.NORMAL, null, soc, hard)
        val predicted = replannedProjectedSoc(km, target.km, soc, context)

        val firstPlannedCharge = basePlan.checkpoints.firstOrNull { cp ->
            cp.chargeToPct != null && cp.km > km - 0.15 && !replanStore.isSkipped(courseMeta.id, cp.km)
        }
        if (firstPlannedCharge != null && firstPlannedCharge.km - km <= 2.5) {
            val afterSkip = nextReplanTarget(firstPlannedCharge.km, extraSkipKm = firstPlannedCharge.km)
            if (afterSkip != null) {
                val skipArrival = replannedProjectedSoc(km, afterSkip.km, soc, context, extraSkipKm = firstPlannedCharge.km)
                val required = if (afterSkip.chargeToPct == null) finishTargetPct else hard.toDouble()
                if (skipArrival >= required) {
                    return ReplanDecision(
                        ReplanDecisionKind.SKIP_AVAILABLE,
                        target = firstPlannedCharge,
                        predictedPct = predicted,
                        hardReservePct = hard,
                        skipCheckpoint = firstPlannedCharge,
                        skipNextTarget = afterSkip,
                        skipArrivalPct = skipArrival
                    )
                }
            }
        }

        return when {
            predicted < hard -> ReplanDecision(ReplanDecisionKind.EMERGENCY, target, predicted, hard)
            predicted < finishTargetPct -> ReplanDecision(ReplanDecisionKind.ECO_CONNECT, target, predicted, hard)
            else -> ReplanDecision(ReplanDecisionKind.NORMAL, target, predicted, hard)
        }
    }

    private fun renderReplanDecision(km: Double, context: EtaChargeContext) {
        if (!logManager.isActive()) {
            btnReplanAction.visibility = View.GONE
            return
        }
        val session = replanStore.active(courseMeta.id)
        if (session != null) {
            btnReplanAction.visibility = View.VISIBLE
            btnReplanAction.text = when (session.phase) {
                EmergencyPhase.OUTBOUND -> "🧭 비상 충전소 길안내 / 관리"
                EmergencyPhase.CHARGING -> "⚡ 비상 충전 중 · 복귀 준비"
                EmergencyPhase.RETURN -> "↩ 원래 이탈점으로 복귀"
            }
            val anchor = "${RideFormatter.one(session.anchorRouteKm)}km"
            when (session.phase) {
                EmergencyPhase.OUTBOUND -> {
                    tvRiskStatus.text = "비상충전 이동"
                    tvRiskStatus.setTextColor(getColor(R.color.danger))
                    tvRiskDetail.text = "${session.candidateName} · ${RideFormatter.one(session.outboundKm)}km\n이탈점 $anchor 고정 · 충전 후 같은 지점 복귀"
                }
                EmergencyPhase.CHARGING -> {
                    val target = emergencyRecommendedChargeTargetPct(session)
                    tvRiskStatus.text = "비상충전 중"
                    tvRiskStatus.setTextColor(getColor(R.color.warn))
                    tvRiskDetail.text = "권장 충전 ${target}%\n완료 후 이탈점 $anchor 로 반드시 복귀"
                }
                EmergencyPhase.RETURN -> {
                    val dist = if (latestLat.isFinite() && latestLon.isFinite()) Geo.distanceMeters(latestLat, latestLon, session.anchorLat, session.anchorLon) else Double.NaN
                    tvRiskStatus.text = "코스 복귀"
                    tvRiskStatus.setTextColor(getColor(R.color.warn))
                    tvRiskDetail.text = if (dist.isFinite()) "원래 이탈점까지 약 ${dist.roundToInt()}m\n$anchor 지점 50m 이내 복귀 시 경기코스 재개" else "원래 이탈점 $anchor 로 복귀 중\n50m 이내 복귀 시 경기코스 재개"
                }
            }
            return
        }

        val d = computeReplanDecision(km, context)
        when (d.kind) {
            ReplanDecisionKind.NORMAL -> btnReplanAction.visibility = View.GONE
            ReplanDecisionKind.SKIP_AVAILABLE -> {
                val cp = d.skipCheckpoint ?: return
                val next = d.skipNextTarget ?: return
                val nextPct = d.skipArrivalPct ?: 0.0
                tvRiskStatus.text = "충전 생략 가능"
                tvRiskStatus.setTextColor(getColor(R.color.good))
                tvRiskDetail.text = "${cp.name} 충전 생략 시\n${next.name} 예상 ${nextPct.roundToInt()}% · 기준 ${if (next.chargeToPct == null) finishTargetPct.roundToInt() else d.hardReservePct}%"
                btnReplanAction.visibility = View.VISIBLE
                btnReplanAction.text = "✓ ${cp.name} 충전 생략 확정"
            }
            ReplanDecisionKind.ECO_CONNECT -> {
                val target = d.target ?: return
                tvRiskStatus.text = "ECO 연결"
                tvRiskStatus.setTextColor(getColor(R.color.warn))
                tvRiskDetail.text = "${target.name} 도착 예상 ${d.predictedPct.roundToInt()}%\n하드 리저브 ${d.hardReservePct}% · 여유 ${(d.predictedPct - d.hardReservePct).coerceAtLeast(0.0).roundToInt()}%"
                btnReplanAction.visibility = View.GONE
            }
            ReplanDecisionKind.EMERGENCY -> {
                val target = d.target ?: return
                tvRiskStatus.text = "긴급 충전"
                tvRiskStatus.setTextColor(getColor(R.color.danger))
                tvRiskDetail.text = "${target.name} 도착 예상 ${d.predictedPct.roundToInt()}%\n하드 리저브 ${d.hardReservePct}% 미만 · 가까운 충전 후보 필요"
                btnReplanAction.visibility = View.VISIBLE
                btnReplanAction.text = "⚠ 주변 비상 충전 후보 찾기"
            }
        }
    }

    private fun handleReplanAction() {
        if (!logManager.isActive() || logManager.isFreeRide()) return
        val session = replanStore.active(courseMeta.id)
        if (session != null) {
            showEmergencySessionDialog(session)
            return
        }
        val context = etaChargeContext()
        val d = computeReplanDecision(latestRouteKm, context)
        when (d.kind) {
            ReplanDecisionKind.SKIP_AVAILABLE -> d.skipCheckpoint?.let { confirmSkipCharge(it, d) }
            ReplanDecisionKind.EMERGENCY -> searchEmergencyChargeCandidates()
            else -> Unit
        }
    }

    private fun confirmSkipCharge(cp: Checkpoint, decision: ReplanDecision) {
        val next = decision.skipNextTarget ?: return
        AlertDialog.Builder(this)
            .setTitle("${cp.name} 충전을 생략할까요?")
            .setMessage("현재 실제 소비량 기준으로 ${cp.name}에서 충전하지 않아도 ${next.name} 도착 예상이 약 ${decision.skipArrivalPct?.roundToInt()}%입니다.\n\n생략하면 이후 ETA에서 이 충전시간도 즉시 빠집니다. 필요하면 현장에서 충전 시작을 눌러 생략을 취소할 수 있습니다.")
            .setPositiveButton("충전 생략 확정") { _, _ ->
                replanStore.markSkipped(courseMeta.id, cp.km)
                logManager.recordEvent("PLANNED_CHARGE_SKIPPED", "${cp.name} 충전 생략 · 다음 ${next.name} 예상 ${decision.skipArrivalPct?.roundToInt()}%", cp.km, currentSocForReplan(latestRouteKm))
                renderAtKm(latestRouteKm, testMode)
            }
            .setNegativeButton("계획 유지", null)
            .show()
    }

    private fun detourConsumptionPct(distanceKm: Double): Double {
        if (distanceKm <= 0.0 || course.totalKm <= 0.1) return 0.0
        val average = basePlan.internalTotalUsePct() / course.totalKm
        val factor = (plan.calibration(latestRouteKm)?.factor ?: 1.0).coerceIn(0.7, 1.6)
        return distanceKm * average * factor * 1.15 // 비상 우회는 15% 보수 마진
    }

    private fun emergencyRecommendedChargeTargetPct(session: EmergencyDetourSession): Int {
        val next = nextReplanTarget(session.anchorRouteKm)
        val requiredArrival = finishTargetPct
        val factor = (plan.calibration(session.anchorRouteKm)?.factor ?: 1.0).coerceIn(0.7, 1.6)
        val courseUse = if (next != null) basePlan.internalConsumption(session.anchorRouteKm, next.km) * factor else 0.0
        val returnUse = detourConsumptionPct(session.returnKm)
        return kotlin.math.ceil(requiredArrival + courseUse + returnUse).toInt().coerceIn(20, 100)
    }

    private fun searchEmergencyChargeCandidates() {
        if (emergencySearchRunning) return
        if (!latestLat.isFinite() || !latestLon.isFinite()) {
            Toast.makeText(this, "현재 GPS 위치가 잡힌 뒤 다시 시도하세요.", Toast.LENGTH_LONG).show()
            return
        }
        if (BuildConfig.KAKAO_REST_API_KEY.isBlank()) {
            Toast.makeText(this, "Kakao REST API 키가 APK에 주입되지 않았습니다. GitHub Secret/Action을 확인하세요.", Toast.LENGTH_LONG).show()
            return
        }
        val anchor = course.pointAtKm(latestRouteKm)
        val currentSoc = currentSocForReplan(latestRouteKm)
        val hard = AppSettings.hardReserve(this)
        emergencySearchRunning = true
        btnReplanAction.isEnabled = false
        btnReplanAction.text = "주변 충전 후보 검색 중…"

        Thread {
            val result = runCatching {
                val client = KakaoEmergencyChargeClient(BuildConfig.KAKAO_REST_API_KEY)
                val all = linkedMapOf<String, KakaoPlaceCandidate>()

                replanStore.history().forEach { h ->
                    if (Geo.distanceMeters(anchor.lat, anchor.lon, h.lat, h.lon) <= 20_000.0) {
                        all[h.id] = KakaoPlaceCandidate(h.id, h.name, h.lat, h.lon, h.address, "", "과거 실제 충전 성공", "A", "과거 실제 충전 성공 ${h.successCount}회", Geo.distanceMeters(anchor.lat, anchor.lon, h.lat, h.lon))
                    }
                }
                chargingStore.list(courseMeta.id).forEach { st ->
                    if (st.lat != 0.0 && st.lon != 0.0 && Geo.distanceMeters(anchor.lat, anchor.lon, st.lat, st.lon) <= 20_000.0) {
                        all.putIfAbsent(st.id, KakaoPlaceCandidate(st.id, st.name, st.lat, st.lon, st.address, "", "등록 충전소", "A", "내가 등록한 충전소", Geo.distanceMeters(anchor.lat, anchor.lon, st.lat, st.lon)))
                    }
                }
                client.searchAround(anchor.lat, anchor.lon).forEach { all.putIfAbsent(it.id, it) }

                val ranked = all.values.sortedWith(compareBy<KakaoPlaceCandidate>({ it.confidence }, { it.straightDistanceM })).take(6)
                ranked.mapNotNull { place ->
                    runCatching {
                        val out = client.bicycleRoute(anchor.lat, anchor.lon, place.lat, place.lon)
                        val back = client.bicycleRoute(place.lat, place.lon, anchor.lat, anchor.lon)
                        val arrival = (currentSoc - detourConsumptionPct(out.distanceKm)).coerceIn(0.0, 100.0)
                        EvaluatedEmergencyCandidate(place, out, back, arrival)
                    }.getOrNull()
                }.sortedWith(
                    compareByDescending<EvaluatedEmergencyCandidate> { if (it.predictedArrivalSoc >= hard) 1 else 0 }
                        .thenBy { it.place.confidence }
                        .thenBy { it.outbound.distanceKm }
                )
            }
            runOnUiThread {
                emergencySearchRunning = false
                btnReplanAction.isEnabled = true
                renderAtKm(latestRouteKm, testMode)
                result.onSuccess { showEmergencyCandidateDialog(it, anchor, currentSoc) }
                    .onFailure { e ->
                        AlertDialog.Builder(this)
                            .setTitle("비상 충전 검색 실패")
                            .setMessage("${e.message ?: "네트워크 또는 Kakao API 설정을 확인하세요."}\n\n카카오맵 API가 활성화되지 않았거나 쿼터 설정이 필요한 경우 Kakao Developers에서 먼저 활성화해야 합니다.")
                            .setPositiveButton("확인", null)
                            .show()
                    }
            }
        }.start()
    }

    private fun showEmergencyCandidateDialog(items: List<EvaluatedEmergencyCandidate>, anchor: TrackPoint, currentSoc: Double) {
        if (items.isEmpty()) {
            AlertDialog.Builder(this).setTitle("비상 충전 후보 없음").setMessage("반경 내에서 자전거 경로를 계산할 수 있는 후보를 찾지 못했습니다. 검색 반경을 넓히거나 직접 장소를 확인하세요.").setPositiveButton("확인", null).show()
            return
        }
        val hard = AppSettings.hardReserve(this)
        EmergencyCandidateDialog.show(
            activity = this,
            title = "비상 충전 후보 · 현재 ${currentSoc.roundToInt()}%",
            intro = "A=등록/과거 성공 · B=충전 관련 검색 · C=편의점/카페 등 현장 확인 필요\n후보를 누르면 현재 GPX 지점을 '복귀 앵커'로 고정하기 전 최종 확인 화면으로 넘어갑니다.",
            items = items,
            hardReserve = hard,
            onSelect = { confirmEmergencyCandidate(it, anchor) }
        )
    }

    private fun confirmEmergencyCandidate(c: EvaluatedEmergencyCandidate, anchor: TrackPoint) {
        val hard = AppSettings.hardReserve(this)
        val warning = if (c.place.confidence == "A") "확인된/등록된 후보입니다." else "⚠ 실제 콘센트/충전 가능 여부는 카카오 검색만으로 보장할 수 없습니다. 출발 전 전화 또는 현장 확인이 필요합니다."
        AlertDialog.Builder(this)
            .setTitle("${c.place.name}으로 비상 우회")
            .setMessage("$warning\n\n편도 ${RideFormatter.one(c.outbound.distanceKm)}km · 약 ${c.outbound.minutes.roundToInt()}분\n도착 예상 ${c.predictedArrivalSoc.roundToInt()}% · 하드 리저브 $hard%\n\n경기 규정 준수를 위해 현재 ${RideFormatter.one(anchor.routeKm)}km 지점을 저장하고, 충전 후 반드시 같은 지점으로 복귀합니다.")
            .setPositiveButton("이탈점 고정 · 길안내") { _, _ ->
                val now = System.currentTimeMillis()
                val session = EmergencyDetourSession(
                    courseId = courseMeta.id,
                    anchorRouteKm = anchor.routeKm,
                    anchorLat = anchor.lat,
                    anchorLon = anchor.lon,
                    candidateId = c.place.id,
                    candidateName = c.place.name,
                    candidateLat = c.place.lat,
                    candidateLon = c.place.lon,
                    candidateAddress = c.place.address,
                    candidateConfidence = c.place.confidence,
                    outboundKm = c.outbound.distanceKm,
                    outboundMinutes = c.outbound.minutes,
                    returnKm = c.back.distanceKm,
                    returnMinutes = c.back.minutes,
                    outboundUrl = c.outbound.landingUrl,
                    returnUrl = c.back.landingUrl,
                    phase = EmergencyPhase.OUTBOUND,
                    startedMs = now,
                    phaseStartMs = now
                )
                replanStore.start(session)
                logManager.recordEvent("EMERGENCY_DETOUR_START", "${c.place.name} 비상충전 · 복귀앵커 ${RideFormatter.one(anchor.routeKm)}km · 왕복 ${RideFormatter.one(c.roundTripKm)}km", anchor.routeKm, currentSocForReplan(anchor.routeKm))
                renderAtKm(anchor.routeKm, testMode)
                openExternalRoute(c.outbound.landingUrl)
            }
            .setNegativeButton("다른 후보", null)
            .show()
    }

    private fun showEmergencySessionDialog(session: EmergencyDetourSession) {
        val msg = when (session.phase) {
            EmergencyPhase.OUTBOUND -> "${session.candidateName}으로 이동 중입니다.\n원래 이탈점 ${RideFormatter.one(session.anchorRouteKm)}km는 고정되어 코스 진행도가 앞으로 점프하지 않습니다.\n충전소에 도착하면 메인의 '충전 시작'을 누르세요."
            EmergencyPhase.CHARGING -> "비상 충전 중입니다. 권장 목표 ${emergencyRecommendedChargeTargetPct(session)}%.\n충전 완료 후에는 반드시 ${RideFormatter.one(session.anchorRouteKm)}km 이탈점으로 돌아갑니다."
            EmergencyPhase.RETURN -> "원래 이탈점 ${RideFormatter.one(session.anchorRouteKm)}km로 복귀 중입니다.\nGPS가 이탈점 50m 이내에 들어오면 자동으로 원래 GPX 진행을 재개합니다."
        }
        val b = AlertDialog.Builder(this).setTitle("비상 충전 / 경기코스 복귀").setMessage(msg)
        when (session.phase) {
            EmergencyPhase.OUTBOUND -> b.setPositiveButton("충전소 길안내") { _, _ -> openExternalRoute(session.outboundUrl) }
                .setNeutralButton("우회 취소") { _, _ -> confirmCancelEmergency(session) }
            EmergencyPhase.CHARGING -> b.setPositiveButton("확인", null)
            EmergencyPhase.RETURN -> b.setPositiveButton("이탈점 길안내") { _, _ -> openExternalRoute(session.returnUrl) }
        }
        b.setNegativeButton("닫기", null).show()
    }

    private fun confirmCancelEmergency(session: EmergencyDetourSession) {
        AlertDialog.Builder(this)
            .setTitle("비상 우회를 취소할까요?")
            .setMessage("코스를 이미 벗어났다면 취소 전에 원래 이탈점 ${RideFormatter.one(session.anchorRouteKm)}km로 돌아가는 것이 안전합니다.")
            .setPositiveButton("그래도 취소") { _, _ ->
                replanStore.cancelEmergency(courseMeta.id)
                renderAtKm(latestRouteKm, testMode)
            }
            .setNegativeButton("유지", null)
            .show()
    }

    private fun openExternalRoute(url: String) {
        if (url.isBlank()) {
            Toast.makeText(this, "카카오 자전거 길안내 URL을 받지 못했습니다.", Toast.LENGTH_LONG).show()
            return
        }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
            .onFailure { Toast.makeText(this, "길안내를 열 수 없습니다: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    /** 비상 우회가 진행 중이면 해당 왕복/충전의 아직 남은 시간을 이후 모든 ETA에 더한다. */
    private fun emergencyDetourRemainingMinutes(targetKm: Double, context: EtaChargeContext): Double {
        val s = replanStore.active(courseMeta.id) ?: return 0.0
        if (targetKm <= s.anchorRouteKm + 0.05) return 0.0
        val now = context.nowMs
        return when (s.phase) {
            EmergencyPhase.OUTBOUND -> {
                val elapsed = ((now - s.phaseStartMs).coerceAtLeast(0L) / 60_000.0)
                val outboundRemain = (s.outboundMinutes - elapsed).coerceAtLeast(0.0)
                val predictedAtStation = (currentSocForReplan(s.anchorRouteKm) - detourConsumptionPct(s.outboundKm)).coerceIn(0.0, 100.0)
                val chargeMin = AvinoxChargeCurve.minutesBetween(predictedAtStation, emergencyRecommendedChargeTargetPct(s).toDouble())
                outboundRemain + chargeMin + s.returnMinutes
            }
            EmergencyPhase.CHARGING -> {
                val active = context.activeCharge
                val current = context.liveSocPct ?: context.entries.lastOrNull()?.percent ?: active?.arrivalPct ?: currentSocForReplan(s.anchorRouteKm)
                AvinoxChargeCurve.minutesBetween(current, emergencyRecommendedChargeTargetPct(s).toDouble()) + s.returnMinutes
            }
            EmergencyPhase.RETURN -> {
                val elapsed = ((now - s.phaseStartMs).coerceAtLeast(0L) / 60_000.0)
                (s.returnMinutes - elapsed).coerceAtLeast(0.0)
            }
        }
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
        rideMiniProfileView.visibility = View.GONE
        layoutRideReachMargins.visibility = View.GONE
        hideRideVisualWarning()
        seekRideRoute.visibility = View.GONE
        tvRideRouteScale.text = "임의주행 · GPX/충전소 거리축 없음"
        switchRideTestMode.visibility = View.GONE
        tvBatteryRange.text = when {
            freshSoc != null -> "● BLE 실제 · 임의주행 · GPS + 배터리 자동 기록"
            storedSoc != null -> "최근 실측 $storedSoc% · BLE 재연결 중 · 임의주행"
            else -> "BLE 연결 대기 · 임의주행"
        }
        btnManualBattery.visibility = if (logManager.isActive() && freshSoc == null) View.VISIBLE else View.GONE
        tvCompareActual.text = consumed?.let(::formatPct) ?: "—"
        tvCompareModel.text = "사후"
        tvCompareAvinox.text = "사후"
        tvCompareDetail.text = ""
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
        tvPointEtaBasis.text = "계획주행에서 GPX 포인트 ETA를 표시합니다."
        tvPointEtaList.text = ""
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
            configureRideRouteVisuals()
        }
        loadBleSnapshot()
        applySettings()
        renderCourseQuick()
        refreshRidePositionControls()
        refreshInlineSettings()
        refreshLearningPage()
        renderCurrentMode()
        if (!logManager.isActive()) runAutomaticLearningImportInBackground()
    }

    private fun runAutomaticLearningImportInBackground() {
        // v0.26.0: Avinox original .proto is the primary A+ learning source.
        // FIT folder import remains a fallback only when Shizuku original sync is unavailable.
        val proto = AvinoxProtoSyncManager(this)
        if (proto.canAutoSyncNow()) {
            proto.markAutoScanAttempt()
            proto.syncAsync(maxFiles = 8) { result ->
                runOnUiThread {
                    if (result.imported > 0) {
                        refreshLearningPage()
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    } else if (result.failed > 0) {
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
            return
        }
        val manager = AutoFitImportManager(this)
        if (!manager.folderConfigured()) return
        manager.scanAsync { result ->
            runOnUiThread {
                if (result.imported > 0) {
                    refreshLearningPage()
                    Toast.makeText(this, "FIT 백업 · ${result.message}", Toast.LENGTH_LONG).show()
                } else if (result.failed > 0) {
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
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
