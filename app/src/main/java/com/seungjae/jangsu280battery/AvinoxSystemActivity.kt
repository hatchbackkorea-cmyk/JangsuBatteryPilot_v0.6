package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/** Admin-only Avinox/eMTB battery, learning and rider system. */
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
            finish()
            return
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
                    Toast.makeText(this, "관리자폰 권한이 확인되지 않아 AVINOX SYSTEM을 닫습니다.", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        scroll.addView(body)
        setContentView(scroll)
        body.addView(header("AVINOX SYSTEM"))

        val emtb = panelBox(blueSoft, blue)
        emtb.addView(title("eMTB 배터리 코파일럿", blue))
        emtb.addView(note("주행 · 코스 · 배터리 예측 · 충전 계획"))
        emtb.addView(actionButton("eMTB 주행 화면 열기", blue) {
            startActivity(Intent(this, MainActivity::class.java))
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(7) })
        body.addView(emtb, panelLp())

        val compactRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
        }

        val reserve = panelBox(blueSoft, blue)
        reserve.addView(compactTitle("충전권장 기준잔량", blue))
        val reserveOptions = (1..99).map { "$it%" }
        val reserveSpinner = compactSpinner(reserveOptions)
        reserveSpinner.setSelection(AppSettings.finishTarget(this).roundToInt().coerceIn(1, 99) - 1)
        reserveSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putInt(AppSettings.KEY_FINISH_TARGET, position + 1).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        reserve.addView(reserveSpinner, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) })
        compactRow.addView(reserve, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) })

        val charge = panelBox(redSoft, red)
        charge.addView(compactTitle("충전 도달 알림", red))
        val chargeOptions = listOf("사용 안 함") + (50..100).map { "$it%" }
        val chargeSpinner = compactSpinner(chargeOptions)
        val chargeEnabled = AppSettings.chargeAlertEnabled(this)
        val chargeTarget = AppSettings.chargeAlertTarget(this).coerceIn(50, 100)
        chargeSpinner.setSelection(if (chargeEnabled) chargeTarget - 49 else 0)
        chargeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == 0) {
                    prefs.edit().putBoolean(AppSettings.KEY_CHARGE_ALERT_ENABLED, false).apply()
                } else {
                    val target = position + 49
                    prefs.edit()
                        .putBoolean(AppSettings.KEY_CHARGE_ALERT_ENABLED, true)
                        .putInt(AppSettings.KEY_CHARGE_ALERT_TARGET, target)
                        .apply()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        charge.addView(chargeSpinner, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) })
        compactRow.addView(charge, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(5) })
        body.addView(compactRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val learning = panelBox(panel, line)
        learning.addView(title("배터리 학습 데이터", primary))
        tvLearning = note("")
        learning.addView(tvLearning)
        learning.addView(actionButton("학습 관리 · Avinox 원본 A+ / FIT 백업", blue) {
            if (logManager.isActive()) {
                Toast.makeText(this, "주행 종료 후 학습을 관리해 주세요.", Toast.LENGTH_LONG).show()
            } else {
                startActivity(Intent(this, HistoricalRideActivity::class.java))
            }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        learning.addView(actionButton("개인 배터리 학습 데이터 초기화", red) {
            confirmClearLearning()
        }.apply { isEnabled = !logManager.isActive() }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(5) })
        body.addView(learning, panelLp())

        val rider = panelBox(blueSoft, blue)
        rider.addView(title("라이더 · eMTB 분석", blue))
        tvInsight = note("")
        rider.addView(tvInsight)
        rider.addView(actionButton("파워커브 · 사람/모터 기여도 보기", blue) {
            startActivity(Intent(this, RideInsightsActivity::class.java))
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(7) })
        body.addView(rider, panelLp())

        refreshSummaries()
    }

    override fun onResume() {
        super.onResume()
        if (::tvLearning.isInitialized) refreshSummaries()
    }

    private fun compactSpinner(items: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@AvinoxSystemActivity, android.R.layout.simple_spinner_dropdown_item, items)
        background = rounded(Color.WHITE, line, 10)
        setPadding(dp(8), 0, dp(8), 0)
    }

    private fun refreshSummaries() {
        val count = learningStore.samples().size
        val contextCount = AvinoxAssistMode.values().sumOf { learningStore.strategyContextSampleCountForMode(it) }
        val historical = historicalStore.records()
        val proto = historical.count { it.sourceType == HistoricalSourceType.PROTO }
        val aux = fitAuxStore.records().size
        tvLearning.text = if (count == 0 && contextCount == 0 && historical.isEmpty() && aux == 0) {
            "학습 데이터 없음 · 중립 초기 모델 사용 중"
        } else {
            "A+/A급 ${count}구간 · 상황 v2 ${contextCount}구간 · Avinox 원본 ${proto}개 · B급 FIT ${aux}개"
        }
        tvInsight.text = insightStore.summaryText()
    }

    private fun confirmClearLearning() {
        AlertDialog.Builder(this)
            .setTitle("배터리 학습 데이터 초기화")
            .setMessage("저장된 개인 배터리 소비 학습 데이터를 모두 삭제할까요? 주행 로그 파일과 실제 배터리 기록은 삭제하지 않습니다.")
            .setPositiveButton("학습 데이터 삭제") { _, _ ->
                learningStore.clear()
                ContextualBatteryLearningStore(this).clear()
                historicalStore.clear()
                fitAuxStore.clear()
                AvinoxProtoSyncManager(this).clearHistory()
                HistoricalRideDataStore(this).clearAll()
                refreshSummaries()
                Toast.makeText(this, "배터리 학습 데이터를 초기화했습니다.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun header(name: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(Button(this@AvinoxSystemActivity).apply {
            text = "‹"
            textSize = 28f
            setTextColor(blue)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(58), dp(58)))
        addView(TextView(this@AvinoxSystemActivity).apply {
            text = name
            textSize = 24f
            setTextColor(primary)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(58), 1f))
        addView(TextView(this@AvinoxSystemActivity).apply {
            text = "🔒 관리자"
            textSize = 12f
            setTextColor(red)
            setTypeface(typeface, Typeface.BOLD)
        })
    }

    private fun panelBox(fill: Int, stroke: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(fill, stroke, 16)
    }

    private fun rounded(fill: Int, stroke: Int, radius: Int = 18) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radius).toFloat()
        setColor(fill)
        setStroke(dp(1), stroke)
    }

    private fun actionButton(textValue: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = textValue
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = rounded(color, color, 12)
        setOnClickListener { onClick() }
    }

    private fun panelLp() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }

    private fun title(v: String, color: Int) = TextView(this).apply {
        text = v
        textSize = 19f
        setTextColor(color)
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun compactTitle(v: String, color: Int) = TextView(this).apply {
        text = v
        textSize = 13f
        setTextColor(color)
        setTypeface(typeface, Typeface.BOLD)
        maxLines = 1
    }

    private fun note(v: String) = TextView(this).apply {
        text = v
        textSize = 11.5f
        setTextColor(secondary)
        setPadding(0, dp(4), 0, 0)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
