package com.seungjae.jangsu280battery

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Dedicated diagnostics screen to avoid modal/dialog lifecycle crashes in AdminCenterActivity. */
class SystemDiagnosticsActivity : Activity() {
    private lateinit var resultText: TextView
    private lateinit var diagnoseButton: Button
    private lateinit var repairButton: Button
    private lateinit var copyButton: Button
    private var latestReport: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        latestReport = prefs().getString(KEY_REPORT, "").orEmpty()
        setContentView(buildUi())

        when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_REPAIR -> runDiagnostic(true)
            MODE_DIAGNOSE -> runDiagnostic(false)
        }
    }

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 13, 20))
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = Button(this).apply {
            text = "←"
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = "🩺 전체 시스템 진단"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), 0, 0, 0)
        }
        header.addView(back, LinearLayout.LayoutParams(dp(58), dp(48)))
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        val hint = TextView(this).apply {
            text = "휴대폰과 Rider Control Center를 한 번에 검사합니다. 문제가 있어도 이 화면을 벗어나지 않고 결과와 복구 내역을 확인할 수 있습니다."
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(8), 0, dp(10))
        }
        root.addView(hint)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        diagnoseButton = Button(this).apply {
            text = "🩺 진단"
            isAllCaps = false
            setOnClickListener { runDiagnostic(false) }
        }
        repairButton = Button(this).apply {
            text = "🛠 자동복구"
            isAllCaps = false
            setOnClickListener { runDiagnostic(true) }
        }
        actions.addView(diagnoseButton, LinearLayout.LayoutParams(0, dp(52), 1f))
        actions.addView(repairButton, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(8) })
        root.addView(actions)

        copyButton = Button(this).apply {
            text = "📋 진단보고서 복사"
            isAllCaps = false
            isEnabled = latestReport.isNotBlank()
            setOnClickListener { copyReport() }
        }
        root.addView(copyButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })

        resultText = TextView(this).apply {
            text = if (latestReport.isBlank()) "아직 실행한 진단이 없습니다." else latestReport
            textSize = 12f
            setTextColor(Color.WHITE)
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(24))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(16, 24, 34))
            addView(resultText, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })
        return root
    }

    private fun runDiagnostic(repair: Boolean) {
        diagnoseButton.isEnabled = false
        repairButton.isEnabled = false
        resultText.setTextColor(Color.LTGRAY)
        resultText.text = if (repair) "안전 자동복구를 실행 중입니다…\n완료될 때까지 이 화면에서 기다려 주세요." else "전체 시스템 진단 중입니다…\n완료될 때까지 이 화면에서 기다려 주세요."

        val done: (SystemDiagnosticsClient.Result) -> Unit = { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                diagnoseButton.isEnabled = true
                repairButton.isEnabled = true
                latestReport = buildString {
                    append(result.title).append("\n\n").append(result.text)
                    if (result.raw.isNotBlank()) append("\n\n[RAW JSON]\n").append(result.raw)
                }
                prefs().edit().putString(KEY_REPORT, latestReport).apply()
                copyButton.isEnabled = true
                resultText.setTextColor(if (result.ok) Color.rgb(210, 255, 220) else Color.rgb(255, 220, 180))
                resultText.text = latestReport
            }
        }

        if (repair) SystemDiagnosticsClient(this).safeRepairAsync(done)
        else SystemDiagnosticsClient(this).diagnoseAsync(done)
    }

    private fun copyReport() {
        if (latestReport.isBlank()) {
            Toast.makeText(this, "먼저 진단을 실행해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Ride Copilot 진단보고서", latestReport))
        Toast.makeText(this, "진단보고서를 복사했습니다. ChatGPT에 그대로 붙여넣으세요.", Toast.LENGTH_LONG).show()
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_DIAGNOSE = "diagnose"
        const val MODE_REPAIR = "repair"
        private const val PREFS = "system_diagnostics"
        private const val KEY_REPORT = "latest_report"
    }
}
