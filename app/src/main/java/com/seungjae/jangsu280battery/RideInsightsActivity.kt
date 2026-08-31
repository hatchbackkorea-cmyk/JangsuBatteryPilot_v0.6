package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class RideInsightsActivity : Activity() {
    private lateinit var store: RideInsightStore
    private lateinit var tvSummary: TextView
    private lateinit var tvCurve: TextView
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ride_insights)
        applyInsets()
        store = RideInsightStore(this)
        tvSummary = findViewById(R.id.tvInsightSummary)
        tvCurve = findViewById(R.id.tvInsightPowerCurve)
        list = findViewById(R.id.llInsightRecords)
        findViewById<Button>(R.id.btnInsightBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnInsightImport).setOnClickListener {
            startActivity(Intent(this, HistoricalRideActivity::class.java))
        }
        findViewById<Button>(R.id.btnInsightClear).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("라이더 분석 초기화")
                .setMessage("파워커브와 사람/모터 기여도 분석 기록만 삭제합니다. 배터리 A/B급 학습 데이터는 유지합니다.")
                .setPositiveButton("초기화") { _, _ -> store.clear(); render() }
                .setNegativeButton("취소", null)
                .show()
        }
        render()
    }

    override fun onResume() { super.onResume(); if (::store.isInitialized) render() }

    private fun render() {
        tvSummary.text = store.summaryText()
        val recent = store.recent12WeekPeaks()
        val all = store.allTimePeaks()
        val labels = listOf(1 to "1초", 5 to "5초", 10 to "10초", 30 to "30초", 60 to "1분", 180 to "3분", 300 to "5분", 600 to "10분", 1200 to "20분", 2400 to "40분", 3600 to "60분")
        tvCurve.text = buildString {
            append("파워커브 · Rider Power 전용\n")
            labels.forEach { (sec, label) ->
                val r = recent[sec]
                val a = all[sec]
                if (r != null || a != null) {
                    append("$label  최근12주 ${r?.roundToInt()?.let { "${it}W" } ?: "—"} · 전체 ${a?.roundToInt()?.let { "${it}W" } ?: "—"}\n")
                }
            }
        }.trim()
        list.removeAllViews()
        val records = store.records().take(15)
        if (records.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "아직 분석된 FIT이 없습니다. B급 FIT 보조학습 또는 A급 FIT+ZIP 정식학습을 추가하면 자동으로 함께 분석합니다."
                setTextColor(getColor(R.color.text_secondary)); textSize = 12f
            })
            return
        }
        records.forEach { r ->
            val tv = TextView(this).apply {
                setBackgroundResource(R.drawable.panel_bg)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setTextColor(getColor(R.color.text_primary))
                textSize = 13f
                text = "${SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date(r.rideStartMs))}\n${store.recordSummary(r)}"
            }
            list.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
    }

    private fun applyInsets() {
        val root = findViewById<View>(R.id.rootRideInsights)
        val l = root.paddingLeft; val t = root.paddingTop; val r = root.paddingRight; val b = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom); insets
        }
    }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
}
