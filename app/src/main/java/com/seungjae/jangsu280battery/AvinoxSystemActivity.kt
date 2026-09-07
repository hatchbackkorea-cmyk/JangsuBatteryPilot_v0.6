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

/** Admin-only Avinox battery/learning/rider system settings. */
class AvinoxSystemActivity : Activity() {
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
    private lateinit var learningStore: BatteryLearningStore
    private lateinit var historicalStore: HistoricalRideStore
    private lateinit var fitAuxStore: FitAuxLearningStore
    private lateinit var insightStore: RideInsightStore
    private lateinit var logManager: RideLogManager
    private lateinit var tvLearning: TextView
    private lateinit var tvInsight: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sync = RiderServerSync(this)
        if (!sync.isAdminDeviceCached()) {
            Toast.makeText(this, "AVINOX SYSTEM은 관리자 핸드폰에서만 열 수 있습니다.", Toast.LENGTH_LONG).show()
            finish(); return
        }
        prefs = AppSettings.prefs(this)
        learningStore = BatteryLearningStore(this)
        historicalStore = HistoricalRideStore(this)
        fitAuxStore = FitAuxLearningStore(this)
        insightStore = RideInsightStore(this)
        logManager = RideLogManager(this)
        buildUi()
        if (sync.configured()) {
            sync.checkAdminStatusAsync { result ->
                if (!result.ok && !sync.isAdminDeviceCached()) runOnUiThread {
                    Toast.makeText(this, "관리자폰 권한이 확인되지 않아 AVINOX SYSTEM을 닫습니다.", Toast.LENGTH_LONG).show(); finish()
                }
            }
        }
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(30)) }
        scroll.addView(body); setContentView(scroll)
        body.addView(header("AVINOX SYSTEM"))

        val reserve = panelBox(blueSoft, blue)
        val tvReserve = title("", blue)
        val seekReserve = SeekBar(this).apply { max = 98; progress = AppSettings.finishTarget(this@AvinoxSystemActivity).roundToInt() - 1 }
        fun updateReserve() { tvReserve.text = "충전권장 기준 잔량 ${seekReserve.progress + 1}%" }
        updateReserve(); reserve.addView(tvReserve); reserve.addView(seekReserve, LinearLayout.LayoutParams(-1, dp(48)))
        reserve.addView(note("1~99% · 다음 충전소 또는 종점 도착 때 남기고 싶은 기준 잔량입니다."))
        seekReserve.setOnSeekBarChangeListener(listener { prefs.edit().putInt(AppSettings.KEY_FINISH_TARGET, (it + 1).coerceIn(1, 99)).apply(); updateReserve() })
        body.addView(reserve, panelLp())

        val charge = panelBox(redSoft, red)
        charge.addView(title("충전 도달 알림", red))
        val swCharge = Switch(this).apply { text = "충전 목표 도달 시 알림"; setTextColor(primary); textSize = 15f; isChecked = AppSettings.chargeAlertEnabled(this@AvinoxSystemActivity) }
        charge.addView(swCharge, LinearLayout.LayoutParams(-1, dp(50)))
        val tvCharge = label("")
        val seekCharge = SeekBar(this).apply { max = 50; progress = AppSettings.chargeAlertTarget(this@AvinoxSystemActivity) - 50 }
        fun updateCharge() { val pct = (seekCharge.progress + 50).coerceIn(50, 100); tvCharge.text = if (swCharge.isChecked) "기본 충전 알림 ${pct}%" else "충전 도달 알림 사용 안 함"; seekCharge.isEnabled = swCharge.isChecked }
        updateCharge(); charge.addView(tvCharge); charge.addView(seekCharge, LinearLayout.LayoutParams(-1, dp(48)))
        charge.addView(note("임의주행은 기본 목표, 계획주행은 충전소의 내 충전 계획 %에 도달하면 알려줍니다."))
        swCharge.setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(AppSettings.KEY_CHARGE_ALERT_ENABLED, checked).apply(); updateCharge() }
        seekCharge.setOnSeekBarChangeListener(listener { prefs.edit().putInt(AppSettings.KEY_CHARGE_ALERT_TARGET, (it + 50).coerceIn(50, 100)).apply(); updateCharge() })
        body.addView(charge, panelLp())

        val learning = panelBox(panel, line)
        learning.addView(title("배터리 학습 데이터", primary)); tvLearning = note(""); learning.addView(tvLearning)
        learning.addView(actionButton("학습 관리 · Avinox 원본 A+ / FIT 백업", blue) {
            if (logManager.isActive()) Toast.makeText(this, "주행 종료 후 학습을 관리해 주세요.", Toast.LENGTH_LONG).show()
            else startActivity(Intent(this, HistoricalRideActivity::class.java))
        }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(10) })
        learning.addView(actionButton("개인 배터리 학습 데이터 초기화", red) { confirmClearLearning() }.apply { isEnabled = !logManager.isActive() }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })
        learning.addView(note("A+는 Avinox 원본 .proto의 실제 SOC·모드·파워를 함께 학습하고 FIT 단독은 B급 백업으로 관리합니다."))
        body.addView(learning, panelLp())

        val rider = panelBox(blueSoft, blue)
        rider.addView(title("라이더 · eMTB 분석", blue)); tvInsight = note(""); rider.addView(tvInsight)
        rider.addView(actionButton("파워커브 · 사람/모터 기여도 보기", blue) { startActivity(Intent(this, RideInsightsActivity::class.java)) }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) })
        rider.addView(note("Rider Power는 사람의 운동능력, Motor Power는 모터 출력/기여도 분석에 분리 사용합니다."))
        body.addView(rider, panelLp())
        refreshSummaries()
    }

    override fun onResume() { super.onResume(); if (::tvLearning.isInitialized) refreshSummaries() }

    private fun refreshSummaries() {
        val count = learningStore.samples().size
        val contextCount = AvinoxAssistMode.values().sumOf { learningStore.strategyContextSampleCountForMode(it) }
        val historical = historicalStore.records(); val proto = historical.count { it.sourceType == HistoricalSourceType.PROTO }; val aux = fitAuxStore.records().size
        tvLearning.text = if (count == 0 && contextCount == 0 && historical.isEmpty() && aux == 0) "학습 데이터 없음 · 중립 초기 모델 사용 중" else "A+/A급 ${count}구간 · 상황 v2 ${contextCount}구간 · Avinox 원본 ${proto}개 · B급 FIT ${aux}개"
        tvInsight.text = insightStore.summaryText()
    }

    private fun confirmClearLearning() {
        AlertDialog.Builder(this).setTitle("배터리 학습 데이터 초기화")
            .setMessage("저장된 개인 배터리 소비 학습 데이터를 모두 삭제할까요? 주행 로그 파일과 실제 배터리 기록은 삭제하지 않습니다.")
            .setPositiveButton("학습 데이터 삭제") { _, _ ->
                learningStore.clear(); ContextualBatteryLearningStore(this).clear(); historicalStore.clear(); fitAuxStore.clear(); AvinoxProtoSyncManager(this).clearHistory(); HistoricalRideDataStore(this).clearAll(); refreshSummaries(); Toast.makeText(this, "배터리 학습 데이터를 초기화했습니다.", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("취소", null).show()
    }

    private fun header(name: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(Button(this@AvinoxSystemActivity).apply { text = "‹"; textSize = 28f; setTextColor(blue); setBackgroundColor(Color.TRANSPARENT); setOnClickListener { finish() } }, LinearLayout.LayoutParams(dp(58), dp(54)))
        addView(TextView(this@AvinoxSystemActivity).apply { text = name; textSize = 24f; setTextColor(primary); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, dp(54), 1f))
        addView(TextView(this@AvinoxSystemActivity).apply { text = "🔒 관리자"; textSize = 12f; setTextColor(red); setTypeface(typeface, Typeface.BOLD) })
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
