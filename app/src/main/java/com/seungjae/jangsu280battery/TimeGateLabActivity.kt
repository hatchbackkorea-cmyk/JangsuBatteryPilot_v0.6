package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/** Admin-only experimental tools: test mode and mobile source deployment. */
class TimeGateLabActivity : Activity() {
    private val bg = Color.WHITE
    private val panel = Color.rgb(247, 249, 252)
    private val blueSoft = Color.rgb(232, 240, 255)
    private val redSoft = Color.rgb(255, 236, 240)
    private val blue = Color.rgb(12, 91, 235)
    private val red = Color.rgb(255, 18, 56)
    private val primary = Color.rgb(8, 10, 13)
    private val secondary = Color.rgb(94, 105, 120)
    private val line = Color.rgb(215, 223, 234)

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var repo: CourseRepository
    private lateinit var logManager: RideLogManager
    private lateinit var seekKm: SeekBar
    private lateinit var tvKm: TextView
    private lateinit var tvHint: TextView
    private lateinit var switchTest: Switch
    private var totalKm = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sync = RiderServerSync(this)
        if (!sync.isAdminDeviceCached()) {
            Toast.makeText(this, "실험실은 관리자 핸드폰에서만 열 수 있습니다.", Toast.LENGTH_LONG).show(); finish(); return
        }
        prefs = AppSettings.prefs(this); repo = CourseRepository(this); logManager = RideLogManager(this)
        totalKm = runCatching { repo.loadActiveCourse().totalKm }.getOrDefault(0.0)
        buildUi()
        if (sync.configured()) sync.checkAdminStatusAsync { result ->
            if (!result.ok && !sync.isAdminDeviceCached()) runOnUiThread {
                Toast.makeText(this, "관리자폰 권한이 확인되지 않아 실험실을 닫습니다.", Toast.LENGTH_LONG).show(); finish()
            }
        }
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(30)) }
        scroll.addView(body); setContentView(scroll); body.addView(header("실험실"))

        val test = panelBox(blueSoft, blue)
        test.addView(title("테스트 모드", blue))
        test.addView(note(runCatching { val m = repo.activeMeta(); "테스트 기준 코스 · ${m.name} · ${RideFormatter.one(m.totalKm)} km" }.getOrDefault("선택 코스 정보를 불러오지 못했습니다.")))
        switchTest = Switch(this).apply { text = "GPS 대신 테스트 위치 사용"; setTextColor(primary); textSize = 15f; isChecked = AppSettings.testMode(this@TimeGateLabActivity); isEnabled = !logManager.isActive() }
        test.addView(switchTest, LinearLayout.LayoutParams(-1, dp(50)))
        tvKm = label(""); test.addView(tvKm)
        seekKm = SeekBar(this).apply { max = (totalKm * 10.0).roundToInt().coerceAtLeast(1); progress = (AppSettings.testKm(this@TimeGateLabActivity).coerceIn(0.0, totalKm) * 10.0).roundToInt() }
        test.addView(seekKm, LinearLayout.LayoutParams(-1, dp(48))); tvHint = note(""); test.addView(tvHint)
        switchTest.setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(AppSettings.KEY_TEST_MODE, checked).apply(); updateTestUi() }
        seekKm.setOnSeekBarChangeListener(listener { val km = (it / 10.0).coerceIn(0.0, totalKm); prefs.edit().putFloat(AppSettings.KEY_TEST_KM, km.toFloat()).apply(); updateTestUi() })
        updateTestUi()
        test.addView(actionButton("진행 위치 / 실제 배터리 보정 초기화", red) { resetProgress() }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        body.addView(test, panelLp())

        val deploy = panelBox(redSoft, red)
        deploy.addView(title("모바일 소스 배포", red))
        deploy.addView(note("전체 소스 ZIP을 선택해 GitHub main에 배포하고 signed Release APK 생성까지 이어서 확인합니다."))
        deploy.addView(actionButton("새 ZIP 모바일 배포 열기", blue) { startActivity(Intent(this, ReleaseUploaderActivity::class.java)) }, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(10) })
        deploy.addView(note("관리자폰 전용입니다. GitHub Fine-grained PAT은 이 기기에만 암호화 저장됩니다."))
        body.addView(deploy, panelLp())
    }

    private fun updateTestUi() {
        val km = (seekKm.progress / 10.0).coerceIn(0.0, totalKm)
        tvKm.text = "테스트 위치 ${RideFormatter.one(km)} km / ${RideFormatter.one(totalKm)} km"
        seekKm.isEnabled = switchTest.isChecked
        tvHint.text = when {
            logManager.isActive() && switchTest.isChecked -> "테스트 주행 중 · 모드 전환은 잠겨 있고 슬라이더 위치만 주행 화면에 반영됩니다."
            logManager.isActive() -> "실제 주행 기록 중에는 테스트 모드 전환이 잠깁니다."
            switchTest.isChecked -> "GPS 대신 이 위치를 주행 화면에 표시합니다."
            else -> "테스트 모드를 켜면 GPS 없이 코스 진행 상황을 확인할 수 있습니다."
        }
    }

    private fun resetProgress() {
        if (logManager.isActive()) { Toast.makeText(this, "주행 기록 중에는 진행 기록을 초기화할 수 없습니다.", Toast.LENGTH_LONG).show(); return }
        AlertDialog.Builder(this).setTitle("진행 기록 초기화").setMessage("현재 코스 진행 위치와 실제 배터리 보정값을 0km 상태로 초기화할까요?")
            .setPositiveButton("초기화") { _, _ -> BatteryActualStore(this).clear(); prefs.edit().putFloat(AppSettings.KEY_LAST_KM, 0f).putFloat(AppSettings.KEY_TEST_KM, 0f).apply(); seekKm.progress = 0; updateTestUi(); Toast.makeText(this, "진행 기록을 초기화했습니다.", Toast.LENGTH_SHORT).show() }
            .setNegativeButton("취소", null).show()
    }

    private fun header(name: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(Button(this@TimeGateLabActivity).apply { text = "‹"; textSize = 28f; setTextColor(blue); setBackgroundColor(Color.TRANSPARENT); setOnClickListener { finish() } }, LinearLayout.LayoutParams(dp(58), dp(54)))
        addView(TextView(this@TimeGateLabActivity).apply { text = name; textSize = 27f; setTextColor(primary); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, dp(54), 1f))
        addView(TextView(this@TimeGateLabActivity).apply { text = "🔒 관리자"; textSize = 12f; setTextColor(red); setTypeface(typeface, Typeface.BOLD) })
    }
    private fun panelBox(fill: Int, stroke: Int) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(15), dp(16), dp(15)); background = rounded(fill, stroke) }
    private fun rounded(fill: Int, stroke: Int) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(18).toFloat(); setColor(fill); setStroke(dp(1), stroke) }
    private fun actionButton(textValue: String, color: Int, onClick: () -> Unit) = Button(this).apply { text = textValue; isAllCaps = false; setTextColor(Color.WHITE); background = rounded(color, color); setOnClickListener { onClick() } }
    private fun panelLp() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
    private fun title(v: String, color: Int) = TextView(this).apply { text = v; textSize = 19f; setTextColor(color); setTypeface(typeface, Typeface.BOLD) }
    private fun label(v: String) = TextView(this).apply { text = v; textSize = 15f; setTextColor(primary); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(8), 0, 0) }
    private fun note(v: String) = TextView(this).apply { text = v; textSize = 11.5f; setTextColor(secondary); setPadding(0, dp(5), 0, 0) }
    private fun listener(block: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { block(progress) }; override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit; override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
