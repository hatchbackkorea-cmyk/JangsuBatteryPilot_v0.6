package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

class SettingsActivity : Activity() {
    private lateinit var courseRepo: CourseRepository
    private lateinit var logManager: RideLogManager
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var learningStore: BatteryLearningStore
    private lateinit var tvLearningSummary: TextView
    private lateinit var btnClearLearning: Button
    private lateinit var historicalRideStore: HistoricalRideStore
    private lateinit var tvHistoricalLearningSummary: TextView

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        LearningMigration.ensureV0110FreshStart(this)

        courseRepo = CourseRepository(this)
        logManager = RideLogManager(this)
        prefs = AppSettings.prefs(this)
        learningStore = BatteryLearningStore(this)
        historicalRideStore = HistoricalRideStore(this)

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
        tvUpdateStatus = findViewById(R.id.tvUpdateStatus)
        switchBetaUpdates = findViewById(R.id.switchBetaUpdates)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        switchChargeAlert = findViewById(R.id.switchChargeAlert)
        tvChargeAlertTarget = findViewById(R.id.tvChargeAlertTarget)
        seekChargeAlertTarget = findViewById(R.id.seekChargeAlertTarget)
        refreshLearningSummary()
        setupUpdateUi()

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
        btnClearLearning.setOnClickListener { confirmClearLearning() }
        btnClearLearning.isEnabled = !logManager.isActive()
        if (logManager.isActive()) btnClearLearning.text = "주행 종료 후 학습 데이터 초기화"
        findViewById<Button>(R.id.btnBleDiagnostic).setOnClickListener {
            startActivity(Intent(this, BleDiagnosticActivity::class.java))
        }
        findViewById<Button>(R.id.btnStravaSettings).setOnClickListener {
            startActivity(Intent(this, StravaActivity::class.java))
        }
        findViewById<Button>(R.id.btnSettingsVersion).setOnClickListener { showVersionInfo() }
    }

    private fun setupUpdateUi() {
        switchBetaUpdates.isChecked = AppSettings.betaUpdates(this)
        refreshUpdateStatus()
        switchBetaUpdates.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(AppSettings.KEY_BETA_UPDATES, checked).apply()
            refreshUpdateStatus()
        }
        btnCheckUpdate.setOnClickListener { checkForUpdate() }
    }

    private fun refreshUpdateStatus(extra: String? = null) {
        val channel = if (AppSettings.betaUpdates(this)) "테스트판 포함" else "안정판"
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
        UpdateManager.checkAsync(this) { result ->
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
        tvFinishTarget.text = "종점 목표 잔량 ${seekFinishTarget.progress + 1}%"
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
        if (::tvLearningSummary.isInitialized) refreshLearningSummary()
        if (::tvUpdateStatus.isInitialized) {
            refreshUpdateStatus()
            UpdateManager.resumePendingInstall(this)
        }
        findViewById<TextView>(R.id.tvStravaSettingsStatus).text = if (StravaSecureStore(this).isConnected()) {
            "● Strava 연결됨 · 클린 FIT + 전체 텔레메트리 업로드"
        } else {
            "○ Strava 연결 안 됨 · Client ID ${StravaSecureStore.CLIENT_ID}"
        }
    }

    private fun refreshLearningSummary() {
        val count = learningStore.samples().size
        val historical = historicalRideStore.records()
        tvLearningSummary.text = if (count == 0) {
            "학습 데이터 없음 · 중립 초기 모델 사용 중"
        } else {
            "저장된 개인 학습 데이터 ${count}개 구간\n${learningStore.summaryText()}"
        }
        tvHistoricalLearningSummary.text = if (historical.isEmpty()) {
            "검증 FIT+ZIP / 과거 FIT·GPX 학습 없음"
        } else {
            "검증/과거 학습 ${historical.size}개 · 생성된 학습 샘플 ${historical.sumOf { it.sampleCount }}개"
        }
    }

    private fun confirmClearLearning() {
        AlertDialog.Builder(this)
            .setTitle("배터리 학습 데이터 초기화")
            .setMessage("지금까지 저장된 개인 배터리 소비 학습 데이터를 모두 삭제할까요? 검증 FIT+ZIP 및 과거 FIT/GPX 학습 기록도 함께 초기화됩니다. 주행 로그 파일과 실제 배터리 기록은 삭제하지 않습니다.")
            .setPositiveButton("학습 데이터 삭제") { _, _ ->
                learningStore.clear()
                historicalRideStore.clear()
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
                    "• v0.21.0 검증 학습: FIT 거리·고도·파워 + ZIP BLE SOC·모드 결합\n" +
                    "• 충전 목표 도달 알림 · 계획 목표 우선 · 충전은 자동 중단하지 않음\n" +
                    "• Rider Power/심박/Cadence/GPS/고도/속도 + Motor/Battery/Assist Mode 기록"
            )
            .setPositiveButton("확인", null)
            .show()
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
