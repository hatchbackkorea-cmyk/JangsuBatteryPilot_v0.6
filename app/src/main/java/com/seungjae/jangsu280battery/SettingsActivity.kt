package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt
import rikka.shizuku.Shizuku

class SettingsActivity : Activity() {
    companion object { private const val REQ_AUTO_FIT_FOLDER = 7301 }
    private lateinit var courseRepo: CourseRepository
    private lateinit var logManager: RideLogManager
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var learningStore: BatteryLearningStore
    private lateinit var tvLearningSummary: TextView
    private lateinit var btnClearLearning: Button
    private lateinit var historicalRideStore: HistoricalRideStore
    private lateinit var fitAuxStore: FitAuxLearningStore
    private lateinit var rideInsightStore: RideInsightStore
    private lateinit var autoFitManager: AutoFitImportManager
    private lateinit var protoSyncManager: AvinoxProtoSyncManager
    private lateinit var tvProtoSyncStatus: TextView
    private lateinit var btnProtoPermission: Button
    private lateinit var btnProtoSync: Button
    private lateinit var tvAutoFitStatus: TextView
    private lateinit var btnAutoFitFolder: Button
    private lateinit var btnAutoFitScan: Button
    private lateinit var tvHistoricalLearningSummary: TextView
    private lateinit var tvRideInsightSummary: TextView
    private lateinit var syncManager: RiderServerSync
    private lateinit var etSyncServerUrl: EditText
    private lateinit var etSyncToken: EditText
    private lateinit var etSyncName: EditText
    private lateinit var etSyncWeight: EditText
    private lateinit var etSyncFtp: EditText
    private lateinit var switchSyncAuto: Switch
    private lateinit var tvSyncStatus: TextView
    private lateinit var btnSyncSave: Button
    private lateinit var btnSyncNow: Button

    private lateinit var switchVoice: Switch
    private lateinit var switchKeepScreen: Switch
    private lateinit var tvDistanceInterval: TextView
    private lateinit var seekDistanceInterval: SeekBar
    private lateinit var tvTimeInterval: TextView
    private lateinit var seekTimeInterval: SeekBar
    private lateinit var tvFinishTarget: TextView
    private lateinit var seekFinishTarget: SeekBar
    private lateinit var switchTestMode: Switch
    private lateinit var tvTestKm: TextView
    private lateinit var seekTestKm: SeekBar
    private lateinit var tvTestHint: TextView
    private lateinit var tvUpdateStatus: TextView
    private lateinit var switchBetaUpdates: Switch
    private lateinit var btnCheckUpdate: Button
    private lateinit var switchChargeAlert: Switch
    private lateinit var tvChargeAlertTarget: TextView
    private lateinit var seekChargeAlertTarget: SeekBar

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == AvinoxProtoSyncManager.PERMISSION_REQUEST) {
            refreshProtoSyncUi(if (grantResult == PackageManager.PERMISSION_GRANTED) "Shizuku 권한 허용됨" else "Shizuku 권한 거부됨")
            if (grantResult == PackageManager.PERMISSION_GRANTED) runProtoSync(manual = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        LearningMigration.ensureV0110FreshStart(this)

        courseRepo = CourseRepository(this)
        logManager = RideLogManager(this)
        prefs = AppSettings.prefs(this)
        learningStore = BatteryLearningStore(this)
        historicalRideStore = HistoricalRideStore(this)
        fitAuxStore = FitAuxLearningStore(this)
        rideInsightStore = RideInsightStore(this)
        autoFitManager = AutoFitImportManager(this)
        protoSyncManager = AvinoxProtoSyncManager(this)
        syncManager = RiderServerSync(this)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        findViewById<Button>(R.id.btnSettingsBack).setOnClickListener { finish() }
        switchVoice = findViewById(R.id.switchSettingsVoice)
        switchKeepScreen = findViewById(R.id.switchSettingsKeepScreen)
        tvDistanceInterval = findViewById(R.id.tvDistanceInterval)
        seekDistanceInterval = findViewById(R.id.seekDistanceInterval)
        tvTimeInterval = findViewById(R.id.tvTimeInterval)
        seekTimeInterval = findViewById(R.id.seekTimeInterval)
        tvFinishTarget = findViewById(R.id.tvSettingsFinishTarget)
        seekFinishTarget = findViewById(R.id.seekSettingsFinishTarget)
        switchTestMode = findViewById(R.id.switchSettingsTestMode)
        tvTestKm = findViewById(R.id.tvSettingsTestKm)
        seekTestKm = findViewById(R.id.seekSettingsTestKm)
        tvTestHint = findViewById(R.id.tvSettingsTestHint)
        tvLearningSummary = findViewById(R.id.tvLearningSummary)
        btnClearLearning = findViewById(R.id.btnClearLearning)
        tvHistoricalLearningSummary = findViewById(R.id.tvHistoricalLearningSummary)
        tvRideInsightSummary = findViewById(R.id.tvRideInsightSummary)
        tvProtoSyncStatus = findViewById(R.id.tvProtoSyncStatus)
        btnProtoPermission = findViewById(R.id.btnProtoPermission)
        btnProtoSync = findViewById(R.id.btnProtoSync)
        tvAutoFitStatus = findViewById(R.id.tvAutoFitStatus)
        btnAutoFitFolder = findViewById(R.id.btnAutoFitFolder)
        btnAutoFitScan = findViewById(R.id.btnAutoFitScan)
        etSyncServerUrl = findViewById(R.id.etSyncServerUrl)
        etSyncToken = findViewById(R.id.etSyncToken)
        etSyncName = findViewById(R.id.etSyncName)
        etSyncWeight = findViewById(R.id.etSyncWeight)
        etSyncFtp = findViewById(R.id.etSyncFtp)
        switchSyncAuto = findViewById(R.id.switchSyncAuto)
        tvSyncStatus = findViewById(R.id.tvSyncStatus)
        btnSyncSave = findViewById(R.id.btnSyncSave)
        btnSyncNow = findViewById(R.id.btnSyncNow)
        findViewById<View>(R.id.panelLegacyRiderServerSync).visibility = View.GONE
        tvUpdateStatus = findViewById(R.id.tvUpdateStatus)
        switchBetaUpdates = findViewById(R.id.switchBetaUpdates)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        switchChargeAlert = findViewById(R.id.switchChargeAlert)
        tvChargeAlertTarget = findViewById(R.id.tvChargeAlertTarget)
        seekChargeAlertTarget = findViewById(R.id.seekChargeAlertTarget)
        refreshLearningSummary()
        tvRideInsightSummary.text = rideInsightStore.summaryText()
        setupSyncUi()
        setupUpdateUi()
        refreshProtoSyncUi()
        refreshAutoFitUi()

        findViewById<TextView>(R.id.tvSettingsCourse).text = runCatching {
            val m = courseRepo.activeMeta()
            "테스트 기준 코스 · ${m.name} · ${RideFormatter.one(m.totalKm)} km"
        }.getOrDefault("선택 코스 정보를 불러오지 못했습니다.")

        switchVoice.isChecked = AppSettings.voiceEnabled(this)
        switchKeepScreen.isChecked = AppSettings.keepScreenOn(this)
        applyKeepScreen(switchKeepScreen.isChecked)

        seekDistanceInterval.max = 50
        seekDistanceInterval.progress = AppSettings.distanceIntervalKm(this)
        updateDistanceLabel()

        seekTimeInterval.max = 120
        seekTimeInterval.progress = AppSettings.timeIntervalMin(this)
        updateTimeLabel()

        seekFinishTarget.max = 98
        seekFinishTarget.progress = AppSettings.finishTarget(this).roundToInt() - 1
        updateFinishLabel()

        switchChargeAlert.isChecked = AppSettings.chargeAlertEnabled(this)
        seekChargeAlertTarget.max = 50
        seekChargeAlertTarget.progress = AppSettings.chargeAlertTarget(this) - 50
        seekChargeAlertTarget.isEnabled = switchChargeAlert.isChecked
        updateChargeAlertLabel()

        val activeCourse = courseRepo.loadActiveCourse()
        seekTestKm.max = (activeCourse.totalKm * 10.0).roundToInt().coerceAtLeast(1)
        seekTestKm.progress = (AppSettings.testKm(this).coerceIn(0.0, activeCourse.totalKm) * 10.0).roundToInt()
        switchTestMode.isChecked = AppSettings.testMode(this)
        updateTestUi(activeCourse.totalKm)

        if (logManager.isActive()) {
            switchTestMode.isEnabled = false
            tvTestHint.text = if (switchTestMode.isChecked) {
                "테스트 주행 중입니다. 위치 슬라이더는 계속 사용할 수 있고, 테스트 모드 해제는 주행 종료 후 가능합니다."
            } else {
                "실제 주행 기록 중에는 테스트 모드를 켤 수 없습니다. 주행 종료 후 설정하세요."
            }
        }

        switchVoice.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(AppSettings.KEY_VOICE, checked).apply()
        }
        switchKeepScreen.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(AppSettings.KEY_KEEP_SCREEN_ON, checked).apply()
            applyKeepScreen(checked)
        }
        seekDistanceInterval.setOnSeekBarChangeListener(simpleListener {
            prefs.edit().putInt(AppSettings.KEY_ANNOUNCE_DISTANCE_KM, it).apply()
            updateDistanceLabel()
        })
        seekTimeInterval.setOnSeekBarChangeListener(simpleListener {
            prefs.edit().putInt(AppSettings.KEY_ANNOUNCE_TIME_MIN, it).apply()
            updateTimeLabel()
        })
        seekFinishTarget.setOnSeekBarChangeListener(simpleListener {
            val pct = (it + 1).coerceIn(1, 99)
            prefs.edit().putInt(AppSettings.KEY_FINISH_TARGET, pct).apply()
            updateFinishLabel()
        })
        switchChargeAlert.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(AppSettings.KEY_CHARGE_ALERT_ENABLED, checked).apply()
            seekChargeAlertTarget.isEnabled = checked
            updateChargeAlertLabel()
        }
        seekChargeAlertTarget.setOnSeekBarChangeListener(simpleListener { progress ->
            val pct = (progress + 50).coerceIn(50, 100)
            prefs.edit().putInt(AppSettings.KEY_CHARGE_ALERT_TARGET, pct).apply()
            updateChargeAlertLabel()
        })
        switchTestMode.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(AppSettings.KEY_TEST_MODE, checked).apply()
            seekTestKm.isEnabled = checked
            updateTestUi(activeCourse.totalKm)
        }
        seekTestKm.setOnSeekBarChangeListener(simpleListener {
            val km = (it / 10.0).coerceIn(0.0, activeCourse.totalKm)
            prefs.edit().putFloat(AppSettings.KEY_TEST_KM, km.toFloat()).apply()
            updateTestUi(activeCourse.totalKm)
        })

        findViewById<Button>(R.id.btnResetProgress).setOnClickListener { resetProgress() }
        findViewById<Button>(R.id.btnHistoricalLearning).setOnClickListener {
            if (logManager.isActive()) {
                Toast.makeText(this, "주행 종료 후 과거 라이딩 학습을 관리해 주세요.", Toast.LENGTH_LONG).show()
            } else {
                startActivity(Intent(this, HistoricalRideActivity::class.java))
            }
        }
        btnProtoPermission.setOnClickListener {
            if (!protoSyncManager.binderReady()) Toast.makeText(this, "Shizuku 앱을 실행하고 먼저 시작해 주세요.", Toast.LENGTH_LONG).show()
            else if (protoSyncManager.permissionGranted()) Toast.makeText(this, "Shizuku 권한이 이미 허용되어 있습니다.", Toast.LENGTH_SHORT).show()
            else protoSyncManager.requestPermission()
        }
        btnProtoSync.setOnClickListener { runProtoSync(manual = true) }
        btnAutoFitFolder.setOnClickListener { pickAutoFitFolder() }
        btnAutoFitScan.setOnClickListener { runAutoFitScan(showNoNew = true) }
        btnClearLearning.setOnClickListener { confirmClearLearning() }
        btnClearLearning.isEnabled = !logManager.isActive()
        if (logManager.isActive()) btnClearLearning.text = "주행 종료 후 학습 데이터 초기화"
        findViewById<Button>(R.id.btnRideInsights).setOnClickListener {
            startActivity(Intent(this, RideInsightsActivity::class.java))
        }
        findViewById<Button>(R.id.btnBleDiagnostic).setOnClickListener {
            startActivity(Intent(this, BleDiagnosticActivity::class.java))
        }
        findViewById<Button>(R.id.btnSramDiagnostic).setOnClickListener {
            startActivity(Intent(this, SramBleActivity::class.java))
        }
        findViewById<Button>(R.id.btnMobileRelease).setOnClickListener {
            startActivity(Intent(this, AdminCenterActivity::class.java))
        }
        findViewById<Button>(R.id.btnSettingsVersion).setOnClickListener { showVersionInfo() }
    }

    @Deprecated("Deprecated in Android API, kept for min-dependency project")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_AUTO_FIT_FOLDER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        autoFitManager.setFolder(uri)
        refreshAutoFitUi("폴더 연결 완료 · 새 FIT을 자동 검색합니다.")
        runAutoFitScan(showNoNew = true)
    }

    private fun refreshProtoSyncUi(extra: String? = null) {
        if (!::protoSyncManager.isInitialized) return
        tvProtoSyncStatus.text = buildString {
            append(protoSyncManager.statusText())
            if (!extra.isNullOrBlank()) append("\n").append(extra)
        }
        btnProtoPermission.isEnabled = !logManager.isActive()
        btnProtoSync.isEnabled = !logManager.isActive() && protoSyncManager.permissionGranted()
    }

    private fun runProtoSync(manual: Boolean) {
        if (logManager.isActive()) { Toast.makeText(this, "주행 종료 후 원본 동기화를 실행해 주세요.", Toast.LENGTH_SHORT).show(); return }
        if (!protoSyncManager.binderReady()) { Toast.makeText(this, "Shizuku 앱을 실행하고 시작해 주세요.", Toast.LENGTH_LONG).show(); return }
        if (!protoSyncManager.permissionGranted()) { protoSyncManager.requestPermission(); return }
        btnProtoSync.isEnabled = false
        refreshProtoSyncUi("새 Avinox 원본 검색 중…")
        protoSyncManager.syncAsync(if (manual) 80 else 8) { result ->
            runOnUiThread {
                refreshProtoSyncUi(result.message)
                refreshLearningSummary()
                if (manual || result.imported > 0 || result.failed > 0) Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun pickAutoFitFolder() {
        if (logManager.isActive()) {
            Toast.makeText(this, "주행 종료 후 자동 FIT 폴더를 변경해 주세요.", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQ_AUTO_FIT_FOLDER)
    }

    private fun refreshAutoFitUi(extra: String? = null) {
        if (!::autoFitManager.isInitialized) return
        tvAutoFitStatus.text = buildString {
            append(autoFitManager.statusText())
            if (!extra.isNullOrBlank()) append("\n").append(extra)
        }
        btnAutoFitScan.isEnabled = autoFitManager.folderConfigured() && !logManager.isActive()
        btnAutoFitFolder.isEnabled = !logManager.isActive()
    }

    private fun runAutoFitScan(showNoNew: Boolean) {
        if (logManager.isActive()) {
            Toast.makeText(this, "주행 종료 후 FIT 백업 검색을 실행해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!autoFitManager.folderConfigured()) {
            Toast.makeText(this, "먼저 FIT 백업 폴더를 지정해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        btnAutoFitScan.isEnabled = false
        tvAutoFitStatus.text = autoFitManager.statusText() + "\n새 FIT 검색 중…"
        autoFitManager.scanAsync { result ->
            runOnUiThread {
                refreshAutoFitUi(result.message)
                refreshLearningSummary()
                tvRideInsightSummary.text = rideInsightStore.summaryText()
                if (result.imported > 0 || result.failed > 0 || showNoNew) {
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setupSyncUi() {
        etSyncServerUrl.setText(syncManager.serverUrl())
        etSyncToken.setText("")
        etSyncName.setText(syncManager.riderName())
        etSyncWeight.setText(String.format(java.util.Locale.US, "%.1f", syncManager.weightKg()))
        etSyncFtp.setText(String.format(java.util.Locale.US, "%.0f", syncManager.ftpW()))
        switchSyncAuto.isChecked = syncManager.autoEnabled()
        refreshSyncUi()
        btnSyncSave.setOnClickListener {
            val weight = etSyncWeight.text.toString().toDoubleOrNull()
            val ftp = etSyncFtp.text.toString().toDoubleOrNull()
            if (weight == null || ftp == null) {
                Toast.makeText(this, "체중/FTP 숫자를 확인해 주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            syncManager.configure(
                etSyncServerUrl.text.toString(), etSyncToken.text.toString(), etSyncName.text.toString(),
                weight, ftp, switchSyncAuto.isChecked
            )
            etSyncToken.setText("")
            refreshSyncUi("설정 저장됨")
            if (syncManager.configured()) runServerSync(showToast = true)
        }
        btnSyncNow.setOnClickListener { runServerSync(showToast = true) }
    }

    private fun refreshSyncUi(extra: String? = null) {
        tvSyncStatus.text = buildString {
            append(syncManager.statusText())
            if (!extra.isNullOrBlank()) append("\n").append(extra)
        }
        btnSyncNow.isEnabled = syncManager.configured()
    }

    private fun runServerSync(showToast: Boolean) {
        if (!syncManager.configured()) {
            if (showToast) Toast.makeText(this, "서버 주소와 연결 토큰을 먼저 저장해 주세요.", Toast.LENGTH_LONG).show()
            refreshSyncUi()
            return
        }
        btnSyncNow.isEnabled = false
        refreshSyncUi("서버 동기화 중…")
        syncManager.syncAllAsync { result ->
            runOnUiThread {
                btnSyncNow.isEnabled = true
                refreshSyncUi(result.message)
                if (showToast) Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupUpdateUi() {
        switchBetaUpdates.visibility = View.GONE
        btnCheckUpdate.text = "⬆ 최신 안정판 업데이트 확인"
        btnCheckUpdate.setOnClickListener { checkForUpdate() }
        refreshUpdateStatus("일반 사용자도 안정판 APK를 직접 업데이트할 수 있습니다. Beta/RC 관리는 관리자 메뉴에서만 합니다.")
    }

    private fun refreshUpdateStatus(extra: String? = null) {
        val channel = "안정판"
        val repo = UpdateManager.repository()
        tvUpdateStatus.text = buildString {
            append("현재 v${UpdateManager.currentVersion(this@SettingsActivity)} · $channel")
            if (repo.isNotBlank()) append(" · $repo")
            if (!extra.isNullOrBlank()) append("\n$extra")
        }
    }

    private fun checkForUpdate() {
        btnCheckUpdate.isEnabled = false
        refreshUpdateStatus("GitHub에서 최신 릴리스를 확인 중…")
        UpdateManager.checkAsync(this, UpdateChannel.STABLE) { result ->
            btnCheckUpdate.isEnabled = true
            result.onSuccess { info ->
                if (info == null) {
                    refreshUpdateStatus("최신 버전입니다.")
                } else {
                    refreshUpdateStatus("새 버전 v${info.versionName} 사용 가능")
                    UpdateManager.showUpdateDialog(this, info)
                }
            }.onFailure { e ->
                refreshUpdateStatus("업데이트 확인 실패 · ${e.message ?: "네트워크/설정을 확인하세요"}")
            }
        }
    }

    private fun simpleListener(onChanged: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { onChanged(progress) }
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    private fun updateDistanceLabel() {
        val v = seekDistanceInterval.progress
        tvDistanceInterval.text = if (v == 0) "거리 기준 안내 · 사용 안 함" else "거리 기준 안내 · ${v} km마다"
    }

    private fun updateTimeLabel() {
        val v = seekTimeInterval.progress
        tvTimeInterval.text = if (v == 0) "시간 기준 안내 · 사용 안 함" else "시간 기준 안내 · ${v}분마다"
    }

    private fun updateFinishLabel() {
        tvFinishTarget.text = "충전권장 기준 잔량 ${seekFinishTarget.progress + 1}%"
    }

    private fun updateChargeAlertLabel() {
        val pct = (seekChargeAlertTarget.progress + 50).coerceIn(50, 100)
        tvChargeAlertTarget.text = if (switchChargeAlert.isChecked) {
            "기본 충전 알림 ${pct}%"
        } else {
            "충전 도달 알림 사용 안 함"
        }
    }

    private fun updateTestUi(totalKm: Double) {
        val km = (seekTestKm.progress / 10.0).coerceIn(0.0, totalKm)
        tvTestKm.text = "테스트 위치 ${RideFormatter.one(km)} km / ${RideFormatter.one(totalKm)} km"
        seekTestKm.isEnabled = switchTestMode.isChecked
        if (!logManager.isActive()) {
            tvTestHint.text = if (switchTestMode.isChecked) "GPS 대신 이 위치를 주행 화면에 표시합니다." else "테스트 모드를 켜면 GPS 없이 코스 진행 상황을 확인할 수 있습니다."
        } else if (switchTestMode.isChecked) {
            tvTestHint.text = "테스트 주행 중 · 슬라이더 위치가 주행 화면에 반영됩니다."
        }
    }

    override fun onResume() {
        super.onResume()
        if (::protoSyncManager.isInitialized) refreshProtoSyncUi()
        if (::autoFitManager.isInitialized) refreshAutoFitUi()
        if (::tvLearningSummary.isInitialized) refreshLearningSummary()
        if (::tvRideInsightSummary.isInitialized) tvRideInsightSummary.text = rideInsightStore.summaryText()
        if (::syncManager.isInitialized && ::tvSyncStatus.isInitialized) {
            refreshSyncUi()
            if (syncManager.autoEnabled() && syncManager.configured()) runServerSync(showToast = false)
        }
        if (::tvUpdateStatus.isInitialized) {
            refreshUpdateStatus()
            UpdateManager.resumePendingInstall(this)
        }
    }

    private fun refreshLearningSummary() {
        val count = learningStore.samples().size
        val contextCount = AvinoxAssistMode.values().sumOf { learningStore.strategyContextSampleCountForMode(it) }
        val historical = historicalRideStore.records()
        val protoRecords = historical.filter { it.sourceType == HistoricalSourceType.PROTO }
        val legacyA = historical.filter { it.sourceType != HistoricalSourceType.PROTO }
        val auxRecords = fitAuxStore.records()
        tvLearningSummary.text = if (count == 0 && contextCount == 0) {
            if (auxRecords.isEmpty()) "학습 데이터 없음 · 중립 초기 모델 사용 중"
            else "A급 배터리 학습 없음 · 중립 소비모델 유지\n${fitAuxStore.summaryText()}"
        } else {
            buildString {
                append("저장된 A+/A급 개인 학습 데이터 ${count}개 구간")
                if (count > 0) append("\n${learningStore.summaryText()}")
                if (contextCount > 0) append("\n${learningStore.strategyContextSummary()}")
                if (auxRecords.isNotEmpty()) append("\n${fitAuxStore.summaryText()}")
            }
        }
        tvHistoricalLearningSummary.text = when {
            historical.isEmpty() && auxRecords.isEmpty() && contextCount == 0 -> "Avinox 원본 A+ / FIT 백업 학습 없음"
            else -> "A+ 원본 ${protoRecords.size}개 · 상황 v2 ${contextCount}구간 · 기존 A급 ${legacyA.size}개 · B급 FIT ${auxRecords.size}개"
        }
    }

    private fun confirmClearLearning() {
        AlertDialog.Builder(this)
            .setTitle("배터리 학습 데이터 초기화")
            .setMessage("지금까지 저장된 개인 배터리 소비 학습 데이터를 모두 삭제할까요? Avinox 원본 A+ 학습과 기존 A급, B급 FIT 보조학습도 함께 초기화됩니다. 주행 로그 파일과 실제 배터리 기록은 삭제하지 않습니다.")
            .setPositiveButton("학습 데이터 삭제") { _, _ ->
                learningStore.clear()
                ContextualBatteryLearningStore(this).clear()
                historicalRideStore.clear()
                fitAuxStore.clear()
                protoSyncManager.clearHistory()
                HistoricalRideDataStore(this).clearAll()
                refreshLearningSummary()
                Toast.makeText(this, "배터리 학습 데이터를 초기화했습니다.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun resetProgress() {
        if (logManager.isActive()) {
            Toast.makeText(this, "주행 기록 중에는 진행 기록을 초기화할 수 없습니다.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("진행 기록 초기화")
            .setMessage("현재 코스 진행 위치와 실제 배터리 보정값을 0km 상태로 초기화할까요? 저장된 과거 라이딩 로그는 삭제하지 않습니다.")
            .setPositiveButton("초기화") { _, _ ->
                BatteryActualStore(this).clear()
                prefs.edit()
                    .putFloat(AppSettings.KEY_LAST_KM, 0f)
                    .putFloat(AppSettings.KEY_TEST_KM, 0f)
                    .apply()
                seekTestKm.progress = 0
                updateTestUi(courseRepo.loadActiveCourse().totalKm)
                Toast.makeText(this, "진행 기록을 초기화했습니다.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showVersionInfo() {
        AlertDialog.Builder(this)
            .setTitle("GPX Battery Copilot")
            .setMessage(
                "v${appVersionName()}\n\n" +
                    "• GitHub Releases 기반 앱 내 업데이트 확인/다운로드/설치\n" +
                    "• 안정판 기본 + 선택형 테스트판(Beta/RC) 업데이트 채널\n" +
                    "• 앱 실행 시 하루 1회 자동 확인 · 새 버전이 있을 때만 안내\n" +
                    "• 고정 서명 APK로 기존 데이터 유지 업데이트\n" +
                    "• 업데이트 확인 시 주행/FIT/배터리/학습 데이터 외부 전송 없음\n" +
                    "• v0.26.2 Proto 대량학습 성능패치 · 학습 캐시/코스 계산 최적화\n• v0.26.1 과거 라이딩 학습 화면에 Avinox Proto A+ 동기화 메뉴 추가\n• 누적 에너지 비교 보조값 2줄 표시 + 상단 버전 표기 확대\n• v0.26.0 Shizuku 기반 Avinox 원본 .proto 자동동기화 + A+ 학습\n• FIT 단독은 원본 동기화 불가 시 B급 백업 학습\n" +
                    "• 기준 잔량으로 앱 권장 SOC 역산 · 계획 % 도달 알림\n" +
                    "• 목표 도달 후에도 충전은 자동 중단하지 않으며 100%에서 재알림\n" +
                    "• Rider Power/심박/Cadence/GPS/고도/속도 + Motor/Battery/Assist Mode 기록"
            )
            .setPositiveButton("확인", null)
            .show()
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
        super.onDestroy()
    }

    private fun appVersionName(): String = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0.16.0"
    } catch (_: Exception) { "0.16.0" }

    private fun applyKeepScreen(enabled: Boolean) {
        if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
