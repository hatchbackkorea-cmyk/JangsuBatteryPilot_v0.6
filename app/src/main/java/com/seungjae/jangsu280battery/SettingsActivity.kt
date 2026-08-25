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
        refreshLearningSummary()

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
        findViewById<Button>(R.id.btnSettingsVersion).setOnClickListener { showVersionInfo() }
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
            "과거 FIT/GPX 학습 라이딩 없음"
        } else {
            "과거 FIT/GPX 학습 ${historical.size}개 · 생성된 학습 샘플 ${historical.sumOf { it.sampleCount }}개"
        }
    }

    private fun confirmClearLearning() {
        AlertDialog.Builder(this)
            .setTitle("배터리 학습 데이터 초기화")
            .setMessage("지금까지 저장된 개인 배터리 소비 학습 데이터를 모두 삭제할까요? 과거 FIT/GPX에서 가져온 학습 기록도 함께 초기화됩니다. 주행 로그 파일과 실제 배터리 기록은 삭제하지 않습니다.")
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
                    "• v0.11.0부터 배터리 학습을 0에서 새로 시작\n" +
                    "• 일반 배터리 10초 이내 재입력은 직전값 자동 무효화\n" +
                    "• 충전 시작/완료 단일 버튼 + 확인/취소\n" +
                    "• FIT 원본과 GPS·고도·속도·케이던스·라이더/모터 파워 시계열 보존\n" +
                    "• 심박 데이터는 수집/학습에서 제외\n" +
                    "• FIT/GPX 거리 · 이동시간 · 평속 · 획득/손실고도 분석\n" +
                    "• 좌우 스와이프 4페이지 · 피드백 데이터 기반 준비"
            )
            .setPositiveButton("확인", null)
            .show()
    }

    private fun appVersionName(): String = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0.11.2"
    } catch (_: Exception) { "0.11.2" }

    private fun applyKeepScreen(enabled: Boolean) {
        if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
