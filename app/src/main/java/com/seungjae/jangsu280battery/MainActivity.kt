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
import android.widget.ImageButton
import android.widget.EditText
import android.text.InputType
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
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
    }

    private lateinit var courseRepo: CourseRepository
    private lateinit var courseMeta: CourseMeta
    private lateinit var course: CourseData
    private lateinit var learningStore: BatteryLearningStore
    private lateinit var chargingStore: ChargingStationStore
    private lateinit var logManager: RideLogManager
    private lateinit var basePlan: BatteryPlan
    private lateinit var actualStore: BatteryActualStore
    private lateinit var chargingSessionStore: ChargingSessionStore
    private lateinit var plan: AdaptiveBatteryPlan
    private lateinit var pacingAdvisor: EnergyPacingAdvisor

    private lateinit var btnCourseMenu: Button
    private lateinit var btnCourseImportQuick: Button
    private lateinit var tvCourseQuickSelect: TextView
    private lateinit var btnRideToggle: Button
    private lateinit var btnChargeToggle: Button
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
            renderAtKm(latestRouteKm, false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        LearningMigration.ensureV0110FreshStart(this)
        bindViews()

        courseRepo = CourseRepository(this)
        learningStore = BatteryLearningStore(this)
        chargingStore = ChargingStationStore(this)
        logManager = RideLogManager(this)
        actualStore = BatteryActualStore(this)
        chargingSessionStore = ChargingSessionStore(this)

        if (!logManager.isActive()) {
            actualStore.clear()
            chargingSessionStore.clear()
        }

        // 앱이 재시작된 경우 진행 중 세션의 코스를 우선 복구.
        logManager.activeRide()?.let { active -> runCatching { courseRepo.setActive(active.courseId) } }
        if (!loadSelectedCourse(resetProgress = false)) return
        applySettings()

        btnCourseMenu.setOnClickListener { startActivity(Intent(this, CourseActivity::class.java)) }
        btnCourseImportQuick.setOnClickListener { importGpxQuick() }
        tvCourseQuickSelect.setOnClickListener { showCoursePickerQuick() }
        btnRideToggle.setOnClickListener { if (logManager.isActive()) confirmEndRide() else startRide() }
        btnChargeToggle.setOnClickListener { toggleCharging() }
        btnSpeakNow.setOnClickListener { speakCurrentSummary() }
        btnMicBattery.setOnClickListener { requestVoiceCommand() }
        btnUndoActual.setOnClickListener { undoActual() }
        btnRideReport.setOnClickListener { showRideReport() }
        setupInlineSettings()
        setupLearningPage()
        setupSwipePager()

        renderCourseQuick()
        refreshInlineSettings()
        refreshLearningPage()
        renderRideState()
        renderCurrentMode()
    }

    private fun bindViews() {
        btnCourseMenu = findViewById(R.id.btnCourseMenu)
        btnCourseImportQuick = findViewById(R.id.btnCourseImportQuick)
        tvCourseQuickSelect = findViewById(R.id.tvCourseQuickSelect)
        btnRideToggle = findViewById(R.id.btnRideToggle)
        btnChargeToggle = findViewById(R.id.btnChargeToggle)
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
            profileView.setCourse(course)
            val prefs = AppSettings.prefs(this)
            if (resetProgress) {
                prefs.edit().putFloat(AppSettings.KEY_LAST_KM, 0f).putFloat(AppSettings.KEY_TEST_KM, 0f).apply()
            }
            latestRouteKm = prefs.getFloat(AppSettings.KEY_LAST_KM, 0f).toDouble().coerceIn(0.0, course.totalKm)
            latestCourseElevation = course.pointAtKm(latestRouteKm).ele
            tvVersion.text = "GPX Battery Copilot v${appVersionName()} · GPX Ride Copilot"
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

    private fun startRide() {
        if (logManager.isActive()) return
        actualStore.clear()
        chargingSessionStore.clear()
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
                    "이 주행 파일의 실제 배터리 기록을 앞으로의 배터리 예측 학습에 사용할까요?\n" +
                    "차량 테스트나 임의로 입력한 배터리 값이 포함됐다면 ‘사용 안 함’을 선택하세요."
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
        btnChargeToggle.isEnabled = active
        btnChargeToggle.text = if (charging != null) "⚡ 충전 완료" else "⚡ 충전 시작"
        tvChargeStatus.text = when {
            charging != null -> {
                val min = ((System.currentTimeMillis() - charging.startMs).coerceAtLeast(0L) / 60_000L)
                "충전 중 · 시작 ${charging.arrivalPct.roundToInt()}% · ${RideFormatter.one(charging.routeKm)}km · ${min}분"
            }
            active -> ""
            else -> "주행 시작 후 충전 기록 가능"
        }
        if (!active) tvGpsStatus.text = if (testMode) "테스트 모드" else "주행 대기"
    }


    private fun renderCourseQuick() {
        if (!::courseMeta.isInitialized) return
        val elev = if (courseMeta.hasElevation) {
            "▲${courseMeta.totalAscentM.roundToInt()}m · ▼${courseMeta.totalDescentM.roundToInt()}m"
        } else "고도 데이터 없음"
        val learned = learningStore.samples().size
        val source = if (courseMeta.builtIn) "기본 예비 코스" else "가져온 GPX"
        tvCourseQuickSelect.text = "${courseMeta.name}  ▼\n${RideFormatter.one(courseMeta.totalKm)} km · $elev\n$source · 개인 학습 ${learned}개 구간 적용"
        val riding = logManager.isActive()
        tvCourseQuickSelect.isEnabled = !riding
        btnCourseImportQuick.isEnabled = !riding
    }

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
        btnPageResetProgress.setOnClickListener { resetProgressQuick() }
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
        voiceInputRouteKm = latestRouteKm.coerceIn(0.0, course.totalKm)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "자연스럽게 말하세요 · 배터리 48프로야 · 종점 목표 20 · 5킬로마다 알려줘 · 앞에 업힐 있어?")
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
            VoiceCommand.RideStart -> startRide()
            VoiceCommand.RideStop -> confirmEndRide()
            VoiceCommand.AddSupplyPoint -> addCurrentSupplyPoint()
            VoiceCommand.Help -> speakVoiceHelp()
            VoiceCommand.Unknown -> Unit
        }
    }

    private fun saveActualBattery(percent: Int, heardText: String) {
        val pct = percent.coerceIn(0, 100)
        val km = if (voiceInputStartedMs > 0L) voiceInputRouteKm else latestRouteKm.coerceIn(0.0, course.totalKm)
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
        renderAtKm(latestRouteKm, testMode)
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
            setText(actualStore.latest()?.percent?.roundToInt()?.toString().orEmpty())
            selectAll()
        }
        val title = if (isStart) "충전 시작" else "충전 완료"
        val message = if (isStart) {
            "현재 실제 배터리 잔량을 입력하세요. 확인을 눌러야 저장됩니다."
        } else {
            "충전 후 실제 배터리 잔량을 입력하세요. 확인을 눌러야 저장됩니다."
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
        val km = latestRouteKm.coerceIn(0.0, course.totalKm)
        val now = System.currentTimeMillis()
        actualStore.save(pct.toDouble(), km, ActualEntryKind.ARRIVAL, now)
        chargingSessionStore.start(km, pct.toDouble(), now)
        if (logManager.isActive()) logManager.recordEvent("CHARGE_START", "충전 시작 · $pct%", km, pct.toDouble())
        renderRideState()
        renderAtKm(km, testMode)
        Toast.makeText(this, "충전 시작 · $pct%", Toast.LENGTH_SHORT).show()
    }

    private fun finishCharge(active: ActiveChargeSession, pct: Int) {
        val now = System.currentTimeMillis()
        // 충전 전/후는 동일한 코스 km에 고정해 소비 구간이 충전시간/GPS 드리프트에 오염되지 않게 한다.
        actualStore.save(pct.toDouble(), active.routeKm, ActualEntryKind.POST_CHARGE, now)
        if (logManager.isActive()) {
            logManager.recordEvent(
                "CHARGE_COMPLETE",
                "충전 완료 · ${active.arrivalPct.roundToInt()}% → $pct%",
                active.routeKm,
                pct.toDouble()
            )
        }
        chargingSessionStore.clear()
        renderRideState()
        renderAtKm(latestRouteKm, testMode)
        Toast.makeText(this, "충전 완료 · $pct%", Toast.LENGTH_SHORT).show()
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

    private fun setupSwipePager() {
        pagerFlipper.displayedChild = 1
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
        val labels = arrayOf("코스", "주행", "설정", "학습", "피드백")
        val dots = (0..4).joinToString("  ") { if (it == pagerFlipper.displayedChild) "●" else "○" }
        tvPagerIndicator.text = "$dots   ${labels[pagerFlipper.displayedChild]}"
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
        val pacing = pacingAdvisor.advice(km, latestSpeedKmh, reserve)
        speakText("현재 ${RideFormatter.one(km)}킬로미터. 예상 배터리 ${battery.percent.roundToInt()}퍼센트. 상태 ${reserve.label}. 종점 예상 ${plan.forecast(km, course.totalKm).percent.roundToInt()}퍼센트. 목표 ${finishTargetPct.roundToInt()}퍼센트. $elevText$cpText ${pacing.voiceText}")
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
        tvBatteryRange.text = "예상 ${range.start.roundToInt()}~${range.endInclusive.roundToInt()}%${if (battery.calibrated) " · 실측보정" else ""}"

        tvRiskStatus.text = reserve.label
        tvRiskStatus.setTextColor(when (reserve.label) {
            "여유" -> getColor(R.color.good)
            "주의" -> getColor(R.color.warn)
            else -> getColor(R.color.danger)
        })
        val diffAbs = abs(reserve.differencePct).roundToInt()
        val differenceText = if (reserve.differencePct >= 0) "여유 ${diffAbs}%" else "부족 ${diffAbs}%"
        tvRiskDetail.text = "${reserve.targetName} ${reserve.predictedPct.roundToInt()}%\n목표 ${reserve.targetPct.roundToInt()}%\n$differenceText"

        val latestActual = actualStatus?.entry
        if (latestActual == null) {
            tvActualBattery.text = "—"
            tvActualBattery.setTextColor(getColor(R.color.text_secondary))
            tvActualDetail.text = ""
            btnUndoActual.isEnabled = false
            btnUndoActual.visibility = View.GONE
        } else {
            tvActualBattery.text = "${latestActual.percent.roundToInt()}%"
            tvActualBattery.setTextColor(batteryColor(latestActual.percent))
            val delta = actualStatus.delta.roundToInt()
            val phase = when (latestActual.kind) {
                ActualEntryKind.ARRIVAL -> "도착값"
                ActualEntryKind.POST_CHARGE -> "충전 후 기준"
                ActualEntryKind.RIDING -> "주행 기준"
            }
            tvActualDetail.text = ""
            btnUndoActual.isEnabled = true
            btnUndoActual.visibility = View.GONE
        }

        val remainFinish = (course.totalKm - km).coerceAtLeast(0.0)
        tvSpeed.text = if (latestSpeedKmh >= 2.0) "속도 ${RideFormatter.one(latestSpeedKmh)}km/h" else "속도 -"
        tvFinishEta.text = "종점 ${RideFormatter.one(remainFinish)}km · ${RideFormatter.etaClock(remainFinish, latestSpeedKmh)}"

        if (cp != null) {
            val remain = (cp.km - km).coerceAtLeast(0.0)
            val atCurrent = abs(cp.km - km) <= 0.15
            val predicted = plan.forecast(km, cp.km).percent.roundToInt()
            tvNextCheckpoint.text = if (atCurrent) "현재 · ${cp.name}" else cp.name
            tvNextCheckpointDetail.text = when {
                cp.chargeToPct != null && atCurrent -> "현재 지점\n도착예상 $predicted%\n충전목표 ${cp.chargeToPct.roundToInt()}%"
                cp.chargeToPct != null -> "${RideFormatter.one(remain)} km 남음\n도착예상 $predicted%\n충전목표 ${cp.chargeToPct.roundToInt()}%"
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
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0.12.0"
    } catch (_: Exception) { "0.12.0" }

    override fun onResume() {
        super.onResume()
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
        }
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
