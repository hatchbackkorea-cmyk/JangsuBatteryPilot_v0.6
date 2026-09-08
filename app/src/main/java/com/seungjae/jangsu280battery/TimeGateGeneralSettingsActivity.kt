package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

/** Public TimeGate settings: voice, display, app update, version/changelog only. */
class TimeGateGeneralSettingsActivity : Activity() {
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
    private lateinit var tvUpdate: TextView
    private lateinit var btnUpdate: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppSettings.prefs(this)
        buildUi()
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(30))
        }
        scroll.addView(body)
        setContentView(scroll)

        body.addView(header("설정"))

        val voice = panelBox(blueSoft, blue)
        voice.addView(title("음성 안내", blue))
        val swVoice = Switch(this).apply {
            text = "자동 음성 안내 사용"
            setTextColor(primary)
            textSize = 15f
            isChecked = AppSettings.voiceEnabled(this@TimeGateGeneralSettingsActivity)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(AppSettings.KEY_VOICE, checked).apply() }
        }
        voice.addView(swVoice, LinearLayout.LayoutParams(-1, dp(50)))

        val tvDistance = label("")
        val seekDistance = SeekBar(this).apply { max = 50; progress = AppSettings.distanceIntervalKm(this@TimeGateGeneralSettingsActivity) }
        fun updateDistance() { tvDistance.text = if (seekDistance.progress == 0) "거리 기준 안내 · 사용 안 함" else "거리 기준 안내 · ${seekDistance.progress} km마다" }
        updateDistance()
        voice.addView(tvDistance)
        voice.addView(seekDistance, LinearLayout.LayoutParams(-1, dp(48)))
        seekDistance.setOnSeekBarChangeListener(listener {
            prefs.edit().putInt(AppSettings.KEY_ANNOUNCE_DISTANCE_KM, it).apply(); updateDistance()
        })

        val tvTime = label("")
        val seekTime = SeekBar(this).apply { max = 120; progress = AppSettings.timeIntervalMin(this@TimeGateGeneralSettingsActivity) }
        fun updateTime() { tvTime.text = if (seekTime.progress == 0) "시간 기준 안내 · 사용 안 함" else "시간 기준 안내 · ${seekTime.progress}분마다" }
        updateTime()
        voice.addView(tvTime)
        voice.addView(seekTime, LinearLayout.LayoutParams(-1, dp(48)))
        seekTime.setOnSeekBarChangeListener(listener {
            prefs.edit().putInt(AppSettings.KEY_ANNOUNCE_TIME_MIN, it).apply(); updateTime()
        })
        voice.addView(note("거리/시간 중 먼저 설정 간격에 도달한 기준으로 안내합니다."))
        body.addView(voice, panelLp())

        val screen = panelBox(panel, line)
        screen.addView(title("화면", primary))
        screen.addView(Switch(this).apply {
            text = "주행 화면 항상 켜기"
            setTextColor(primary)
            textSize = 15f
            isChecked = AppSettings.keepScreenOn(this@TimeGateGeneralSettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(AppSettings.KEY_KEEP_SCREEN_ON, checked).apply()
                if (checked) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }, LinearLayout.LayoutParams(-1, dp(50)))
        body.addView(screen, panelLp())

        val update = panelBox(redSoft, red)
        update.addView(title("앱 업데이트", red))
        tvUpdate = note("")
        update.addView(tvUpdate)
        btnUpdate = Button(this).apply {
            text = "⬆ 최신 안정판 업데이트 확인"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(red, red)
            setOnClickListener { checkUpdate() }
        }
        update.addView(btnUpdate, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) })
        body.addView(update, panelLp())
        refreshUpdate("일반 사용자도 안정판 APK를 직접 업데이트할 수 있습니다.")

        body.addView(Button(this).apply {
            text = "버전 / 변경사항"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(blue, blue)
            setOnClickListener { showVersion() }
        }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(12) })
    }

    private fun checkUpdate() {
        btnUpdate.isEnabled = false
        refreshUpdate("GitHub에서 최신 릴리스를 확인 중…")
        UpdateManager.checkAsync(this, UpdateChannel.STABLE) { result ->
            btnUpdate.isEnabled = true
            result.onSuccess { info ->
                if (info == null) refreshUpdate("최신 버전입니다.")
                else {
                    refreshUpdate("새 버전 v${info.versionName} 사용 가능")
                    UpdateManager.showUpdateDialog(this, info)
                }
            }.onFailure { refreshUpdate("업데이트 확인 실패 · ${it.message ?: "네트워크를 확인하세요."}") }
        }
    }

    private fun refreshUpdate(extra: String) {
        tvUpdate.text = "현재 v${UpdateManager.currentVersion(this)} · 안정판\n$extra"
    }

    private fun showVersion() {
        AlertDialog.Builder(this)
            .setTitle("TimeGate")
            .setMessage(
                "v${UpdateManager.currentVersion(this)}\n\n" +
                    "• eMTB 기능을 AVINOX SYSTEM 내부로 통합\n" +
                    "• 모바일 소스 배포 기능 제거\n" +
                    "• 모든 페이지에서 상단/하단 Android 시스템 영역 보존\n" +
                    "• 페이지 제목 위치를 공통 헤더 규격으로 통일\n" +
                    "• 충전 기준/도달 알림을 콤팩트 드롭다운으로 변경"
            )
            .setPositiveButton("확인", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        UpdateManager.resumePendingInstall(this)
    }

    private fun header(name: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(Button(this@TimeGateGeneralSettingsActivity).apply {
            text = "‹"
            textSize = 28f
            setTextColor(blue)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(58), dp(58)))
        addView(TextView(this@TimeGateGeneralSettingsActivity).apply {
            text = name
            textSize = 24f
            setTextColor(primary)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(58), 1f))
        addView(TextView(this@TimeGateGeneralSettingsActivity).apply {
            text = "TimeGate"
            textSize = 13f
            setTextColor(red)
            setTypeface(typeface, Typeface.BOLD)
        })
    }

    private fun panelBox(fill: Int, stroke: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = rounded(fill, stroke)
    }

    private fun rounded(fill: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(18).toFloat()
        setColor(fill)
        setStroke(dp(1), stroke)
    }

    private fun panelLp() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
    private fun title(v: String, color: Int) = TextView(this).apply { text = v; textSize = 19f; setTextColor(color); setTypeface(typeface, Typeface.BOLD) }
    private fun label(v: String) = TextView(this).apply { text = v; textSize = 15f; setTextColor(primary); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(8), 0, 0) }
    private fun note(v: String) = TextView(this).apply { text = v; textSize = 11.5f; setTextColor(secondary); setPadding(0, dp(5), 0, 0) }
    private fun listener(block: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { block(progress) }
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
