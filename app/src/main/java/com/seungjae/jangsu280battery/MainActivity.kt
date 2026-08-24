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
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
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
        private const val REQ_GPX = 1005
        private const val REQ_EXPORT = 1006
        private const val PREFS = "ride_state"
        private const val KEY_LAST_KM = "last_km"
        private const val KEY_VOICE = "voice_enabled"
        private const val KEY_VOICE_LEVEL = "voice_level"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_FINISH_TARGET = "finish_target"
    }

    private lateinit var courseRepo: CourseRepository
    private lateinit var courseMeta: CourseMeta
    private lateinit var course: CourseData
    private lateinit var learningStore: BatteryLearningStore
    private lateinit var logManager: RideLogManager
    private lateinit var basePlan: BatteryPlan
    private lateinit var actualStore: BatteryActualStore
    private lateinit var plan: AdaptiveBatteryPlan
    private lateinit var chargeStore: ChargingSessionStore

    private lateinit var tvCourseName: TextView
    private lateinit var tvCourseSummary: TextView
    private lateinit var btnImportGpx: Button
    private lateinit var btnSelectCourse: Button
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
    private var pendingExportFile: File? = null

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
        chargeStore = ChargingSessionStore(this)
        if (!logManager.isActive()) {
            // v0.7부터 실제 배터리/충전 상태는 '현재 주행 세션' 단위로 관리한다.
            actualStore.clear()
            chargeStore.clearSession()
        }

        // 앱이 죽었다가 다시 켜진 경우 진행 중 세션의 코스를 우선 복구한다.
        logManager.activeRide()?.let { active ->
            runCatching { courseRepo.setActive(active.courseId) }
        }
        if (!loadSelectedCourse(resetProgress = false)) return

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
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

        btnImportGpx.setOnClickListener { importGpx() }
        btnSelectCourse.setOnClickListener { showCourseSelector() }
        btnRideToggle.setOnClickListener { if (logManager.isActive()) confirmEndRide() else startRide() }
        btnExportLast.setOnClickListener { exportLastLog() }

        switchVoice.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_VOICE, checked).apply()
            if (logManager.isActive() && !testMode) sendServiceAction(RideService.ACTION_SET_VOICE) { putExtra(RideService.EXTRA_VOICE_ENABLED, checked) }
        }
        btnVoiceLevel.setOnClickListener {
            voiceLevel = voiceLevel.next()
            prefs.edit().putString(KEY_VOICE_LEVEL, voiceLevel.name).apply()
            updateVoiceLevelButton()
            if (logManager.isActive() && !testMode) sendServiceAction(RideService.ACTION_SET_VOICE_LEVEL) { putExtra(RideService.EXTRA_VOICE_LEVEL, voiceLevel.name) }
        }
        switchKeepScreen.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, checked).apply(); applyKeepScreenOn(checked)
        }
        seekFinishTarget.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                finishTargetPct = (progress + 5).toDouble()
                prefs.edit().putFloat(KEY_FINISH_TARGET, finishTargetPct.toFloat()).apply()
                updateFinishTargetLabel(); updateCourseHeader(); renderAtKm(latestRouteKm, testMode)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        btnSpeakNow.setOnClickListener { speakCurrentSummary() }
        btnMicBattery.setOnClickListener { requestVoiceCommand() }
        btnUndoActual.setOnClickListener { undoActual() }
        btnChargeToggle.setOnClickListener { if (chargeStore.state().active) stopCharging() else startCharging() }
        btnRideReport.setOnClickListener { showRideReport() }
        btnVersionInfo.setOnClickListener { showVersionInfo() }

        switchTestMode.setOnCheckedChangeListener { _, checked ->
            testMode = checked
            seekTestKm.isEnabled = checked
            if (checked) {
                stopRideService()
                tvGpsStatus.text = "테스트 모드 · 슬라이더로 GPX 진행 확인"
                latestRouteKm = seekTestKm.progress / 10.0
                renderAtKm(latestRouteKm, true)
            } else {
                if (logManager.isActive()) ensurePermissionsAndStart() else tvGpsStatus.text = "주행 대기 · GPX 선택 후 주행 시작"
            }
        }
        seekTestKm.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!testMode) return
                latestRouteKm = progress / 10.0
                tvTestKm.text = "테스트 위치 ${RideFormatter.one(latestRouteKm)} km"
                latestSpeedKmh = 17.0; latestOffCourseM = 0.0; latestAccuracyM = 5f
                latestCourseElevation = course.pointAtKm(latestRouteKm).ele
                renderAtKm(latestRouteKm, true)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        btnResetProgress.setOnClickListener { resetRideState() }

        renderRideState()
        renderAtKm(latestRouteKm, false)
        if (logManager.isActive()) ensurePermissionsAndStart()
    }

    private fun loadSelectedCourse(resetProgress: Boolean): Boolean {
        return try {
            courseMeta = courseRepo.activeMeta()
            course = courseRepo.loadCourse(courseMeta.id)
            basePlan = BatteryPlan(course, learningStore)
            plan = AdaptiveBatteryPlan(basePlan, actualStore)
            profileView.setCourse(course)
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            if (resetProgress) prefs.edit().putFloat(KEY_LAST_KM, 0f).apply()
            latestRouteKm = prefs.getFloat(KEY_LAST_KM, 0f).toDouble().coerceIn(0.0, course.totalKm)
            latestCourseElevation = course.pointAtKm(latestRouteKm).ele
            seekTestKm.max = (course.totalKm * 10).roundToInt().coerceAtLeast(1)
            seekTestKm.progress = (latestRouteKm * 10).roundToInt().coerceIn(0, seekTestKm.max)
            tvTestKm.text = "테스트 위치 ${RideFormatter.one(latestRouteKm)} km"
            tvVersion.text = "GPX Battery Copilot v${appVersionName()} · GPX Ride Copilot"
            updateCourseHeader()
            true
        } catch (e: Exception) {
            Toast.makeText(this, "GPX 읽기 실패: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun bindViews() {
        tvCourseName = findViewById(R.id.tvCourseName); tvCourseSummary = findViewById(R.id.tvCourseSummary)
        btnImportGpx = findViewById(R.id.btnImportGpx); btnSelectCourse = findViewById(R.id.btnSelectCourse)
        btnRideToggle = findViewById(R.id.btnRideToggle); btnExportLast = findViewById(R.id.btnExportLast)
        tvGpsStatus = findViewById(R.id.tvGpsStatus); tvCurrentKm = findViewById(R.id.tvCurrentKm)
        tvBattery = findViewById(R.id.tvBattery); tvBatteryRange = findViewById(R.id.tvBatteryRange)
        progressBattery = findViewById(R.id.progressBattery); tvRiskStatus = findViewById(R.id.tvRiskStatus)
        tvRiskDetail = findViewById(R.id.tvRiskDetail); tvActualBattery = findViewById(R.id.tvActualBattery)
        tvActualDetail = findViewById(R.id.tvActualDetail); tvMicHint = findViewById(R.id.tvMicHint)
        btnMicBattery = findViewById(R.id.btnMicBattery); btnUndoActual = findViewById(R.id.btnUndoActual)
        tvNextCheckpoint = findViewById(R.id.tvNextCheckpoint); tvNextCheckpointDetail = findViewById(R.id.tvNextCheckpointDetail)
        tvEta = findViewById(R.id.tvEta); tvFinishEta = findViewById(R.id.tvFinishEta); tvSpeed = findViewById(R.id.tvSpeed)
        tvElevationAhead = findViewById(R.id.tvElevationAhead); tvTenKmBattery = findViewById(R.id.tvTenKmBattery)
        tvAssist = findViewById(R.id.tvAssist); tvNextClimb = findViewById(R.id.tvNextClimb)
        tvNextClimbDetail = findViewById(R.id.tvNextClimbDetail); tvCourseStatus = findViewById(R.id.tvCourseStatus)
        tvNextPoi = findViewById(R.id.tvNextPoi); tvChargeTimer = findViewById(R.id.tvChargeTimer)
        tvChargeDetail = findViewById(R.id.tvChargeDetail); btnChargeToggle = findViewById(R.id.btnChargeToggle)
        tvFinishTarget = findViewById(R.id.tvFinishTarget); seekFinishTarget = findViewById(R.id.seekFinishTarget)
        tvVersion = findViewById(R.id.tvVersion); profileView = findViewById(R.id.profileView)
        switchVoice = findViewById(R.id.switchVoice); btnVoiceLevel = findViewById(R.id.btnVoiceLevel)
        switchKeepScreen = findViewById(R.id.switchKeepScreen); btnSpeakNow = findViewById(R.id.btnSpeakNow)
        btnRideReport = findViewById(R.id.btnRideReport); btnVersionInfo = findViewById(R.id.btnVersionInfo)
        switchTestMode = findViewById(R.id.switchTestMode); tvTestKm = findViewById(R.id.tvTestKm)
        seekTestKm = findViewById(R.id.seekTestKm); btnResetProgress = findViewById(R.id.btnResetProgress)
    }

    private fun updateCourseHeader() {
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
        tvCourseSummary.text = "${RideFormatter.one(course.totalKm)} km · $elev\n$batteryLine · ${plan.modelLabel()}"
    }

    private fun importGpx() {
        if (logManager.isActive()) return Toast.makeText(this, "주행을 종료한 뒤 코스를 변경해주세요.", Toast.LENGTH_LONG).show()
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream"))
        }
        try { startActivityForResult(intent, REQ_GPX) }
        catch (_: ActivityNotFoundException) { Toast.makeText(this, "파일 선택기를 열 수 없습니다.", Toast.LENGTH_LONG).show() }
    }

    private fun showCourseSelector() {
        if (logManager.isActive()) return Toast.makeText(this, "주행 중에는 코스를 변경할 수 없습니다.", Toast.LENGTH_LONG).show()
        val items = courseRepo.listCourses()
        if (items.isEmpty()) return
        var selected = items.indexOfFirst { it.id == courseMeta.id }.coerceAtLeast(0)
        val labels = items.map { m ->
            val elev = if (m.hasElevation) "▲${m.totalAscentM.roundToInt()}m" else "고도 없음"
            "${m.name}\n${RideFormatter.one(m.totalKm)}km · $elev${if (m.builtIn) " · 기본" else ""}"
        }.toTypedArray()
        val dialog = AlertDialog.Builder(this)
            .setTitle("GPX 코스 선택")
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setPositiveButton("선택", null)
            .setNeutralButton("선택 코스 삭제", null)
            .setNegativeButton("취소", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val meta = items[selected]
                courseRepo.setActive(meta.id)
                actualStore.clear(); chargeStore.clearSession()
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat(KEY_LAST_KM, 0f).apply()
                loadSelectedCourse(true); renderAtKm(0.0, false); dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val meta = items[selected]
                if (meta.builtIn) Toast.makeText(this, "기본 장수 코스는 삭제하지 않습니다.", Toast.LENGTH_SHORT).show()
                else AlertDialog.Builder(this).setTitle("코스 삭제").setMessage("${meta.name}을 삭제할까요?")
                    .setPositiveButton("삭제") { _, _ ->
                        courseRepo.deleteCourse(meta.id)
                        loadSelectedCourse(true); renderAtKm(0.0, false); dialog.dismiss()
                    }.setNegativeButton("취소", null).show()
            }
        }
        dialog.show()
    }

    private fun startRide() {
        if (logManager.isActive()) return
        actualStore.clear(); chargeStore.clearSession()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat(KEY_LAST_KM, 0f).apply()
        latestRouteKm = 0.0; latestSpeedKmh = 0.0; latestOffCourseM = 0.0
        logManager.start(courseMeta)
        renderRideState(); renderAtKm(0.0, false)
        if (!testMode) ensurePermissionsAndStart()
        Toast.makeText(this, "주행 기록을 시작했습니다. GPS 로그는 계속 자동 저장됩니다.", Toast.LENGTH_LONG).show()
    }

    private fun confirmEndRide() {
        AlertDialog.Builder(this).setTitle("주행 종료")
            .setMessage("주행을 종료하고 GPX · CSV · JSON 로그를 저장할까요?")
            .setPositiveButton("종료 및 저장") { _, _ -> endRide() }
            .setNegativeButton("계속 주행", null).show()
    }

    private fun endRide() {
        if (!logManager.isActive()) return
        stopRideService()
        try {
            val archive = logManager.finalizeRide(course, actualStore, learningStore)
            renderRideState(); updateCourseHeader()
            AlertDialog.Builder(this).setTitle("주행 로그 저장 완료")
                .setMessage("${archive.courseName}\n${RideFormatter.one(archive.maxRouteKm)} km\n\nGPX · CSV · JSON · ZIP 저장 완료\n개인 배터리 학습 ${archive.learnedSamples}개 구간 반영")
                .setPositiveButton("ZIP 내보내기") { _, _ -> exportFile(archive.zipFile) }
                .setNegativeButton("확인", null).show()
        } catch (e: Exception) {
            Toast.makeText(this, "로그 저장 실패: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun renderRideState() {
        val active = logManager.isActive()
        btnRideToggle.text = if (active) "■ 주행 종료 · 로그 저장" else "▶ 주행 시작"
        btnImportGpx.isEnabled = !active; btnSelectCourse.isEnabled = !active
        btnExportLast.isEnabled = logManager.lastZipFile() != null
        if (!active && !testMode) tvGpsStatus.text = "주행 대기 · GPX 선택 후 주행 시작"
    }

    private fun exportLastLog() {
        val file = logManager.lastZipFile() ?: return Toast.makeText(this, "내보낼 주행 로그가 없습니다.", Toast.LENGTH_SHORT).show()
        exportFile(file)
    }

    private fun exportFile(file: File) {
        pendingExportFile = file
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"; putExtra(Intent.EXTRA_TITLE, file.name)
        }
        startActivityForResult(intent, REQ_EXPORT)
    }

    private fun requestVoiceCommand() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            speechPendingAfterPermission = true; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MICROPHONE); return
        }
        launchVoiceCommand()
    }

    private fun launchVoiceCommand() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR"); putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "자연스럽게 말하세요 · 배터리 48프로야 · 앞에 업힐 있어? · 여기를 보급소로 등록해")
        }
        tvMicHint.text = "🎤 듣는 중… 평소 말투로 말씀하세요"
        try { startActivityForResult(intent, REQ_SPEECH) }
        catch (_: ActivityNotFoundException) { tvMicHint.text = "음성 인식 앱을 찾지 못했습니다." }
    }

    @Deprecated("Deprecated in Android, retained for minSdk 26 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_GPX -> {
                if (resultCode != RESULT_OK) return
                val uri = data?.data ?: return
                try {
                    val meta = courseRepo.importGpx(uri, displayName(uri))
                    actualStore.clear(); chargeStore.clearSession()
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat(KEY_LAST_KM, 0f).apply()
                    loadSelectedCourse(true); renderAtKm(0.0, false)
                    Toast.makeText(this, "${meta.name} · ${RideFormatter.one(meta.totalKm)}km 불러오기 완료", Toast.LENGTH_LONG).show()
                } catch (e: Exception) { Toast.makeText(this, "GPX 불러오기 실패: ${e.message}", Toast.LENGTH_LONG).show() }
            }
            REQ_EXPORT -> {
                if (resultCode == RESULT_OK) {
                    val uri = data?.data; val source = pendingExportFile
                    if (uri != null && source != null) try {
                        contentResolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                        Toast.makeText(this, "주행 로그 ZIP을 저장했습니다.", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) { Toast.makeText(this, "내보내기 실패: ${e.message}", Toast.LENGTH_LONG).show() }
                }
                pendingExportFile = null
            }
            REQ_SPEECH -> {
                if (resultCode != RESULT_OK) { tvMicHint.text = "큰 마이크를 누르고 자연스럽게 말하세요"; return }
                val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty()
                val parsed = results.map { VoiceCommandParser.parse(it) to it }.firstOrNull { it.first !is VoiceCommand.Unknown }
                if (parsed == null) { tvMicHint.text = "명령을 못 알아들었습니다 · 다시 말해주세요"; return }
                handleVoiceCommand(parsed.first, parsed.second)
            }
        }
    }

    private fun handleVoiceCommand(command: VoiceCommand, heardText: String) {
        when (command) {
            is VoiceCommand.Battery -> saveActualBattery(command.percent, heardText, command.forcePostCharge)
            is VoiceCommand.FinishTarget -> {
                finishTargetPct = command.percent.toDouble().coerceIn(5.0, 30.0); seekFinishTarget.progress = (finishTargetPct - 5).roundToInt()
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat(KEY_FINISH_TARGET, finishTargetPct.toFloat()).apply()
                updateFinishTargetLabel(); updateCourseHeader(); renderAtKm(latestRouteKm, testMode)
                speakText("종점 목표 잔량을 ${finishTargetPct.roundToInt()}퍼센트로 설정했습니다.")
            }
            VoiceCommand.Repeat, VoiceCommand.CurrentStatus -> speakCurrentSummary()
            VoiceCommand.NextCheckpoint -> speakNextCheckpoint()
            VoiceCommand.FinishInfo -> speakFinishInfo()
            VoiceCommand.RemainingOverview -> speakRemainingOverview()
            VoiceCommand.NextClimb -> speakNextClimb()
            VoiceCommand.LocationInfo -> speakLocationInfo()
            VoiceCommand.CourseInfo -> speakCourseInfo()
            is VoiceCommand.SetVoiceLevel -> setVoiceLevelFromCommand(command.level)
            VoiceCommand.ChargeStart -> startCharging()
            VoiceCommand.ChargeStop -> stopCharging()
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
        val km = latestRouteKm.coerceIn(0.0, course.totalKm)
        val charge = chargeStore.state()
        val effectiveForcePost = forcePostCharge || (charge.active && percent >= charge.targetPct - 0.5)
        val kind = plan.classifyInput(km, percent.toDouble(), effectiveForcePost)
        actualStore.save(percent.toDouble(), km, kind)
        if (logManager.isActive()) logManager.recordEvent("BATTERY", "$percent% · ${kind.name}", km, percent.toDouble())
        if (charge.active) {
            val state = chargeStore.observe(percent.toDouble())
            if (percent >= state.targetPct - 0.5) { chargeStore.stop(percent.toDouble()); logManager.recordEvent("CHARGE_END", "충전 완료 $percent%", km, percent.toDouble()) }
        }
        renderAtKm(km, testMode)
        val kindText = when (kind) { ActualEntryKind.ARRIVAL -> "도착 잔량"; ActualEntryKind.POST_CHARGE -> "충전 후 잔량"; ActualEntryKind.RIDING -> "주행 중 잔량" }
        tvMicHint.text = "인식: ‘$heardText’ · ${kindText}로 저장"
        val reserve = plan.reserveStatus(km, finishTargetPct)
        speakText("배터리 ${percent}퍼센트로 반영했습니다. ${reserve.targetName} 예상 ${reserve.predictedPct.roundToInt()}퍼센트, 상태 ${reserve.label}.")
    }

    private fun undoActual() {
        val removed = actualStore.undoLast()
        if (removed == null) Toast.makeText(this, "취소할 실제 배터리 입력이 없습니다.", Toast.LENGTH_SHORT).show()
        else { if (logManager.isActive()) logManager.recordEvent("BATTERY_UNDO", "마지막 배터리 입력 취소", latestRouteKm, null); renderAtKm(latestRouteKm, testMode) }
    }

    private fun startCharging() {
        val cp = plan.checkpointAt(latestRouteKm, 0.45)
        val target = cp?.chargeToPct ?: 80.0
        val actual = actualStore.latest()?.takeIf { abs(it.routeKm - latestRouteKm) <= 0.8 }?.percent
        val startPct = actual ?: plan.estimate(latestRouteKm).percent
        chargeStore.start(latestRouteKm, startPct, target)
        if (logManager.isActive()) logManager.recordEvent("CHARGE_START", "${startPct.roundToInt()}% → 목표 ${target.roundToInt()}%", latestRouteKm, startPct)
        renderChargeCard(); speakText("충전을 시작했습니다. 현재 약 ${startPct.roundToInt()}퍼센트, 목표 ${target.roundToInt()}퍼센트입니다.")
    }

    private fun stopCharging() {
        val s = chargeStore.state(); if (!s.active) return
        chargeStore.stop(); if (logManager.isActive()) logManager.recordEvent("CHARGE_END", "충전 타이머 종료", latestRouteKm, s.lastPct)
        renderChargeCard(); speakText("충전 타이머를 종료했습니다.")
    }

    private fun addCurrentSupplyPoint() {
        if (!::courseMeta.isInitialized) return
        val poi = courseRepo.addCustomSupplyPoint(courseMeta.id, latestRouteKm)
        if (logManager.isActive()) logManager.recordEvent("USER_SUPPLY", poi.name, latestRouteKm, actualStore.latest()?.percent)
        val wasActive = logManager.isActive() && !testMode
        if (wasActive) stopRideService()
        loadSelectedCourse(false); renderAtKm(latestRouteKm, testMode)
        if (wasActive) ensurePermissionsAndStart()
        speakText("현재 ${RideFormatter.one(latestRouteKm)}킬로미터 지점을 ${poi.name}로 등록했습니다.")
    }

    private fun renderChargeCard() {
        val s = chargeStore.state(); val cp = if (::plan.isInitialized) plan.checkpointAt(latestRouteKm, 0.45) else null
        if (!s.active) {
            tvChargeTimer.text = "충전 대기"
            tvChargeDetail.text = cp?.chargeToPct?.let { "${cp.name} · 계획 목표 ${it.roundToInt()}%" } ?: "어디서든 시작 가능 · 기본 목표 80%"
            btnChargeToggle.text = "충전 시작"; btnChargeToggle.isEnabled = true; return
        }
        val sec = s.elapsedMs() / 1000L; val mm = sec / 60; val ss = sec % 60
        tvChargeTimer.text = String.format(Locale.US, "%02d:%02d · %.0f%% → %.0f%%", mm, ss, s.lastPct, s.targetPct)
        val rate = s.effectiveRate(); val remain = s.remainingMinutes()
        tvChargeDetail.text = if (rate != null && remain != null) "측정 ${String.format(Locale.US, "%.2f", rate)}%/분 · 목표까지 약 ${remain}분" else "충전 중간 실제 배터리를 말하면 충전 ETA를 학습합니다."
        btnChargeToggle.text = "충전 종료"
    }

    private fun speakCurrentSummary() {
        val km = latestRouteKm; val battery = plan.estimate(km); val reserve = plan.reserveStatus(km, finishTargetPct)
        val stats = course.elevationAhead(km, 10.0); val cp = plan.currentOrNextCheckpoint(km)
        val cpText = cp?.takeIf { it.km < course.totalKm - 0.05 }?.let { " 다음 ${it.name}까지 ${RideFormatter.one((it.km - km).coerceAtLeast(0.0))}킬로미터." }.orEmpty()
        val elevText = if (course.hasElevation) "앞으로 10킬로미터 상승 ${stats.ascentM.roundToInt()}미터." else "GPX에 고도 데이터가 없습니다."
        speakText("현재 ${RideFormatter.one(km)}킬로미터. 예상 배터리 ${battery.percent.roundToInt()}퍼센트. 상태 ${reserve.label}. 종점 예상 ${plan.forecast(km, course.totalKm).percent.roundToInt()}퍼센트. $elevText$cpText")
    }

    private fun speakNextCheckpoint() {
        val cp = plan.currentOrNextCheckpoint(latestRouteKm) ?: return speakText("종점에 도착했습니다.")
        val remain = (cp.km - latestRouteKm).coerceAtLeast(0.0); val predicted = plan.forecast(latestRouteKm, cp.km).percent
        speakText("${cp.name}까지 ${RideFormatter.one(remain)}킬로미터. 예상 배터리 ${predicted.roundToInt()}퍼센트입니다.")
    }
    private fun speakFinishInfo() { speakText("종점까지 ${RideFormatter.one((course.totalKm - latestRouteKm).coerceAtLeast(0.0))}킬로미터. 종점 예상 배터리 ${plan.forecast(latestRouteKm, course.totalKm).percent.roundToInt()}퍼센트, 목표 ${finishTargetPct.roundToInt()}퍼센트입니다.") }
    private fun speakRemainingOverview() { speakNextCheckpoint(); }
    private fun speakNextClimb() {
        if (!course.hasElevation) return speakText("이 GPX에는 고도 데이터가 없어 업힐 분석을 할 수 없습니다.")
        val climb = course.nextMajorClimb(latestRouteKm) ?: return speakText("앞 22킬로미터 안에는 큰 연속 업힐이 없습니다.")
        val remain = (climb.startKm - latestRouteKm).coerceAtLeast(0.0)
        speakText("${if (remain <= 0.2) "현재 주요 업힐입니다." else "약 ${RideFormatter.one(remain)}킬로미터 후 주요 업힐입니다."} 길이 ${RideFormatter.one(climb.distanceKm)}킬로미터, 상승 ${climb.ascentM.roundToInt()}미터, 평균 경사 ${String.format(Locale.US, "%.1f", climb.averageGradePct)}퍼센트입니다.")
    }
    private fun speakLocationInfo() { speakText("현재 코스 ${RideFormatter.one(latestRouteKm)}킬로미터 지점${if (course.hasElevation) ", 고도 약 ${latestCourseElevation.roundToInt()}미터" else ""}입니다.") }
    private fun speakCourseInfo() {
        val elev = if (course.hasElevation) "누적 상승 ${course.totalAscentM.roundToInt()}미터." else "고도 데이터는 없습니다."
        speakText("${courseMeta.name}. 전체 ${RideFormatter.one(course.totalKm)}킬로미터. $elev 예상 총 배터리 사용량 ${plan.predictedTotalUsePct().roundToInt()}퍼센트입니다.")
    }
    private fun setVoiceLevelFromCommand(requested: RequestedVoiceLevel) {
        voiceLevel = when (requested) { RequestedVoiceLevel.QUIET -> VoiceLevel.QUIET; RequestedVoiceLevel.NORMAL -> VoiceLevel.NORMAL; RequestedVoiceLevel.CHATTY -> VoiceLevel.CHATTY }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_VOICE_LEVEL, voiceLevel.name).apply(); updateVoiceLevelButton()
        if (logManager.isActive() && !testMode) sendServiceAction(RideService.ACTION_SET_VOICE_LEVEL) { putExtra(RideService.EXTRA_VOICE_LEVEL, voiceLevel.name) }
        speakText("음성 안내를 ${voiceLevel.label} 모드로 변경했습니다.")
    }
    private fun speakVoiceHelp() { speakText("자연스럽게 말하세요. 지금 배터리 48프로야, 앞에 업힐 있어, 종점까지 얼마나 남았어, 주행 시작, 여기를 보급소로 등록해처럼 말할 수 있습니다.") }
    private fun speakText(text: String) { if (testMode || !logManager.isActive()) Toast.makeText(this, text, Toast.LENGTH_LONG).show() else sendServiceAction(RideService.ACTION_SPEAK_TEXT) { putExtra(RideService.EXTRA_SPEAK_TEXT, text) } }

    private fun ensurePermissionsAndStart() {
        if (testMode || !logManager.isActive()) return
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) { requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION); return }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS); return }
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
        tvGpsStatus.text = "GPS 추적 + 로그 자동 저장 중 · 화면을 꺼도 유지"
    }
    private fun stopRideService() { try { stopService(Intent(this, RideService::class.java)) } catch (_: Exception) { } }
    private fun sendServiceAction(action: String, block: Intent.() -> Unit = {}) {
        if (!logManager.isActive() && action != RideService.ACTION_STOP) return
        val intent = Intent(this, RideService::class.java).apply { this.action = action; block() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && action != RideService.ACTION_STOP) startForegroundService(intent) else startService(intent)
    }

    private fun resetRideState() {
        if (logManager.isActive()) {
            AlertDialog.Builder(this).setTitle("주행 기록 초기화").setMessage("현재 주행 로그를 버리고 초기화할까요? 저장되지 않습니다.")
                .setPositiveButton("기록 버리기") { _, _ ->
                    stopRideService(); logManager.discardActive(); actualStore.clear(); chargeStore.clearSession()
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat(KEY_LAST_KM, 0f).apply(); latestRouteKm = 0.0
                    renderRideState(); renderAtKm(0.0, testMode)
                }.setNegativeButton("취소", null).show()
        } else {
            actualStore.clear(); chargeStore.clearSession(); getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat(KEY_LAST_KM, 0f).apply()
            latestRouteKm = 0.0; seekTestKm.progress = 0; renderAtKm(0.0, testMode)
        }
    }

    private fun renderAtKm(kmValue: Double, simulated: Boolean) {
        val km = kmValue.coerceIn(0.0, course.totalKm); latestRouteKm = km
        val point = course.pointAtKm(km); val battery = plan.estimate(km); val range = plan.confidenceRange(km)
        val cp = plan.currentOrNextCheckpoint(km); val poi = course.nextPoi(km); val stats10 = course.elevationAhead(km, 10.0)
        val battery10 = plan.forecast(km, (km + 10.0).coerceAtMost(course.totalKm)); val actualStatus = plan.latestStatus(km)
        val reserve = plan.reserveStatus(km, finishTargetPct); val climb = course.nextMajorClimb(km)

        tvCurrentKm.text = "${RideFormatter.one(km)} km"
        val pct = battery.percent.roundToInt().coerceIn(0, 100); tvBattery.text = "$pct%"; tvBattery.setTextColor(batteryColor(battery.percent))
        progressBattery.progress = pct; progressBattery.progressTintList = android.content.res.ColorStateList.valueOf(batteryColor(battery.percent))
        tvBatteryRange.text = "${battery.note} · 예상 범위 ${range.start.roundToInt()}~${range.endInclusive.roundToInt()}%"
        tvRiskStatus.text = reserve.label; tvRiskStatus.setTextColor(when (reserve.label) { "여유" -> getColor(R.color.good); "주의" -> getColor(R.color.warn); else -> getColor(R.color.danger) })
        val diffAbs = abs(reserve.differencePct).roundToInt(); val differenceText = if (reserve.differencePct >= 0) "목표보다 ${diffAbs}% 여유" else "목표보다 ${diffAbs}% 부족 · 약 ${diffAbs}% 절약 필요"
        tvRiskDetail.text = "${reserve.targetName} 예상 ${reserve.predictedPct.roundToInt()}% / 목표 ${reserve.targetPct.roundToInt()}% · $differenceText · 실시간계수 ${String.format(Locale.US, "%.2f", reserve.consumptionFactor)}x"

        val latestActual = actualStatus?.entry
        if (latestActual == null) {
            tvActualBattery.text = "실제값 없음"; tvActualBattery.setTextColor(getColor(R.color.text_secondary))
            tvActualDetail.text = "실제 배터리를 말하면 이 코스의 이후 소비 예측을 즉시 보정합니다."; btnUndoActual.isEnabled = false
        } else {
            tvActualBattery.text = "실제 ${latestActual.percent.roundToInt()}%"; tvActualBattery.setTextColor(batteryColor(latestActual.percent))
            val delta = actualStatus.delta.roundToInt(); val phase = when (latestActual.kind) { ActualEntryKind.ARRIVAL -> "도착값"; ActualEntryKind.POST_CHARGE -> "충전 후 기준"; ActualEntryKind.RIDING -> "주행 기준" }
            tvActualDetail.text = "${RideFormatter.one(latestActual.routeKm)}km · 기준 대비 ${if (delta >= 0) "+" else ""}$delta% · $phase · 소비 ${String.format(Locale.US, "%.2f", actualStatus.consumptionFactor)}x · ${timeText(latestActual.timestampMs)}"
            btnUndoActual.isEnabled = true
        }

        val remainFinish = (course.totalKm - km).coerceAtLeast(0.0)
        tvSpeed.text = if (latestSpeedKmh >= 2.0) "이동 평균 ${RideFormatter.one(latestSpeedKmh)} km/h" else "이동 속도 계산 중"
        tvFinishEta.text = "종점 ${RideFormatter.one(remainFinish)}km · ${RideFormatter.etaClock(remainFinish, latestSpeedKmh)}"

        if (cp != null) {
            val remain = (cp.km - km).coerceAtLeast(0.0); val atCurrent = abs(cp.km - km) <= 0.15
            val predicted = plan.forecast(km, cp.km).percent.roundToInt(); tvNextCheckpoint.text = if (atCurrent) "현재 · ${cp.name}" else cp.name
            tvNextCheckpointDetail.text = when {
                cp.chargeToPct != null && atCurrent -> "도착 예상 $predicted% → 계획 ${cp.chargeToPct.roundToInt()}% 충전"
                cp.chargeToPct != null -> "${RideFormatter.one(remain)}km · 도착 예상 $predicted% · 계획 ${cp.chargeToPct.roundToInt()}% 충전"
                cp.km >= course.totalKm - 0.05 -> "${RideFormatter.one(remain)}km 남음 · 종점 예상 $predicted%"
                else -> "${RideFormatter.one(remain)}km 남음 · 예상 배터리 $predicted% · 보급/충전 가능 지점"
            }
            tvEta.text = "예상 도착 ${RideFormatter.etaClock(remain, latestSpeedKmh)} · ${RideFormatter.duration(remain, latestSpeedKmh)}"
        } else { tvNextCheckpoint.text = "코스 완료"; tvNextCheckpointDetail.text = "종점 도착"; tvEta.text = "완료" }

        if (course.hasElevation) {
            tvElevationAhead.text = "앞 10km  ▲${stats10.ascentM.roundToInt()}m   ▼${stats10.descentM.roundToInt()}m"
            if (climb == null) { tvNextClimb.text = "주요 업힐 없음"; tvNextClimbDetail.text = "앞 22km에서 큰 연속 업힐이 감지되지 않았습니다." }
            else { val rem = (climb.startKm - km).coerceAtLeast(0.0); tvNextClimb.text = if (rem <= 0.2) "현재 주요 업힐" else "${RideFormatter.one(rem)}km 후 주요 업힐"; tvNextClimbDetail.text = "길이 ${RideFormatter.one(climb.distanceKm)}km · 상승 +${climb.ascentM.roundToInt()}m · 평균 ${String.format(Locale.US, "%.1f", climb.averageGradePct)}%" }
        } else {
            tvElevationAhead.text = "GPX 고도 데이터 없음 · 거리 기반 모드"
            tvNextClimb.text = "업힐 분석 불가"; tvNextClimbDetail.text = "이 GPX에는 ele 고도 데이터가 충분하지 않습니다."
        }
        tvTenKmBattery.text = "10km 후 예상 ${battery10.percent.roundToInt()}%${if (battery10.calibrated) " · 실측 보정" else ""}"
        tvAssist.text = when { reserve.label == "위험" -> "⚠ 목표 달성을 위해 약 ${(-reserve.differencePct).coerceAtLeast(0.0).roundToInt()}% 절약 필요"; reserve.label == "주의" -> "배터리 목표선 근처 · 긴 업힐에서 보조 강도 절약"; else -> plan.assistText(km, battery, stats10) }
        tvAssist.setTextColor(when { reserve.label == "위험" -> getColor(R.color.danger); reserve.label == "주의" -> getColor(R.color.warn); else -> getColor(R.color.good) })
        tvNextPoi.text = poi?.let { "다음 포인트 · ${it.name} · ${RideFormatter.one((it.routeKm - km).coerceAtLeast(0.0))}km" } ?: "다음 포인트 · 종점"
        val accText = if (latestAccuracyM >= 0) "±${latestAccuracyM.roundToInt()}m" else "-"; val offText = if (simulated) "테스트" else "코스 이탈 ${latestOffCourseM.roundToInt()}m"
        val ele = if (latestCourseElevation > 0) latestCourseElevation else point.ele
        tvCourseStatus.text = if (course.hasElevation) "고도 ${ele.roundToInt()}m · GPS $accText · $offText" else "GPS $accText · $offText · 고도 없음"
        if (!simulated) {
            tvGpsStatus.text = when {
                !logManager.isActive() -> "주행 대기 · GPX 선택 후 주행 시작"
                latestOffCourseM >= 150 -> "⚠ 코스에서 ${latestOffCourseM.roundToInt()}m 벗어남 · 방향 확인"
                latestAccuracyM > 50 -> "GPS 정확도 낮음 ${latestAccuracyM.roundToInt()}m"
                else -> "GPS 추적 + 로그 자동 저장 정상"
            }
            tvGpsStatus.setTextColor(if (latestOffCourseM >= 150 && logManager.isActive()) getColor(R.color.danger) else getColor(R.color.text_secondary))
        }
        profileView.setCurrentKm(km); renderChargeCard(); renderRideState()
    }

    private fun updateVoiceLevelButton() { btnVoiceLevel.text = "안내 수준: ${voiceLevel.label}" }
    private fun updateFinishTargetLabel() { tvFinishTarget.text = "종점 목표 잔량 ${finishTargetPct.roundToInt()}%" }
    private fun showRideReport() {
        val text = if (logManager.isActive()) logManager.activeSummaryText() + "\n\n" + learningStore.summaryText() else logManager.lastReportText() + "\n\n" + learningStore.summaryText()
        AlertDialog.Builder(this).setTitle(if (logManager.isActive()) "현재 주행" else "최근 주행 리포트").setMessage(text).setPositiveButton("확인", null).show()
    }
    private fun showVersionInfo() {
        val msg = """
            현재 버전: ${appVersionName()} (versionCode 8)

            v0.7.0 GPX Ride Copilot
            • 휴대폰에서 임의 GPX 불러오기 / 여러 코스 저장·선택
            • trkpt 또는 rtept 코스 자동 분석
            • 거리·누적상승·하강·고도 프로필·다음 업힐 자동 계산
            • waypoint를 보급/충전/POI로 자동 활용
            • 음성으로 “여기를 보급소로 등록해” 지원
            • GPX별 범용 배터리 예측 + 주행 중 실측 재보정
            • 주행 종료 시 실제 GPS GPX + CSV + JSON + ZIP 자동 저장
            • 앱이 꺼져도 주행 중 로그를 계속 append해 복구 가능
            • 완료 라이딩의 실제 배터리 구간을 다음 GPX 예측에 학습
            • 기본 장수280 Stage1 코스와 기존 충전 계획은 그대로 포함
        """.trimIndent()
        AlertDialog.Builder(this).setTitle("GPX Battery Copilot").setMessage(msg).setPositiveButton("확인", null).show()
    }

    private fun displayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) cursor.getString(0) else null
        } catch (_: Exception) { null } finally { cursor?.close() }
    }
    private fun batteryColor(percent: Double): Int = when { percent >= 60 -> getColor(R.color.good); percent >= 40 -> getColor(R.color.warn); percent >= 25 -> getColor(R.color.orange); else -> getColor(R.color.danger) }
    private fun applyKeepScreenOn(enabled: Boolean) { if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    private fun appVersionName(): String {
        return try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName ?: "0.7.0"
        } catch (_: Exception) { "0.7.0" }
    }
    private fun timeText(timestampMs: Long): String = if (timestampMs <= 0) "" else SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(timestampMs))

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            val filter = IntentFilter(RideService.ACTION_UPDATE)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(rideReceiver, filter, Context.RECEIVER_NOT_EXPORTED) else { @Suppress("DEPRECATION") registerReceiver(rideReceiver, filter) }
            receiverRegistered = true
        }
        handler.removeCallbacks(chargeTicker); handler.post(chargeTicker)
        renderRideState(); renderAtKm(latestRouteKm, testMode)
        if (logManager.isActive() && !testMode) ensurePermissionsAndStart()
    }
    override fun onStop() {
        if (receiverRegistered) { try { unregisterReceiver(rideReceiver) } catch (_: Exception) { }; receiverRegistered = false }
        handler.removeCallbacks(chargeTicker); super.onStop()
    }
}
