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

/** Dedicated diagnostics screen. It never auto-closes and persists progress/results immediately. */
class SystemDiagnosticsActivity : Activity() {
    private lateinit var resultText: TextView
    private lateinit var diagnoseButton: Button
    private lateinit var repairButton: Button
    private lateinit var copyButton: Button
    private var latestReport: String = ""
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        latestReport = prefs().getString(KEY_REPORT, "").orEmpty()
        setContentView(buildUi())

        val mode = intent.getStringExtra(EXTRA_MODE)
        if (savedInstanceState == null) {
            when (mode) {
                MODE_REPAIR -> runDiagnostic(true)
                MODE_DIAGNOSE -> runDiagnostic(false)
            }
        }
    }

    override fun onBackPressed() {
        if (running) {
            Toast.makeText(this, "진단이 끝날 때까지 잠시 기다려 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        super.onBackPressed()
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
            setOnClickListener {
                if (running) Toast.makeText(this@SystemDiagnosticsActivity, "진단이 끝날 때까지 잠시 기다려 주세요.", Toast.LENGTH_SHORT).show()
                else finish()
            }
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
            text = "이 화면은 진단이 끝날 때까지 자동으로 닫히지 않습니다. 중간 오류도 보고서로 저장합니다."
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
        if (running) return
        running = true
        diagnoseButton.isEnabled = false
        repairButton.isEnabled = false

        val started = if (repair) {
            "자동복구 시작됨\n\n진행 중입니다. 이 문구가 마지막으로 남아 있으면 진단 도중 앱이 중단된 것입니다."
        } else {
            "전체 시스템 진단 시작됨\n\n진행 중입니다. 이 문구가 마지막으로 남아 있으면 진단 도중 앱이 중단된 것입니다."
        }
        latestReport = started
        prefs().edit().putString(KEY_REPORT, latestReport).commit()
        copyButton.isEnabled = true
        resultText.setTextColor(Color.LTGRAY)
        resultText.text = started + "\n\n1/3 휴대폰 상태 확인\n2/3 Rider Control Center 연결 확인\n3/3 결과 저장"

        val done: (SystemDiagnosticsClient.Result) -> Unit = { result ->
            runOnUiThread {
                try {
                    latestReport = buildString {
                        append(result.title).append("\n\n").append(result.text)
                        if (result.raw.isNotBlank()) append("\n\n[RAW JSON]\n").append(result.raw)
                    }
                    prefs().edit().putString(KEY_REPORT, latestReport).commit()
                    copyButton.isEnabled = true
                    resultText.setTextColor(if (result.ok) Color.rgb(210, 255, 220) else Color.rgb(255, 220, 180))
                    resultText.text = latestReport
                } catch (t: Throwable) {
                    latestReport = "진단 결과 표시 실패\n\n${t.javaClass.simpleName}: ${t.message ?: "메시지 없음"}"
                    prefs().edit().putString(KEY_REPORT, latestReport).commit()
                    resultText.text = latestReport
                    copyButton.isEnabled = true
                } finally {
                    running = false
                    diagnoseButton.isEnabled = true
                    repairButton.isEnabled = true
                }
            }
        }

        try {
            val client = SystemDiagnosticsClient(applicationContext)
            if (repair) client.safeRepairAsync(done) else client.diagnoseAsync(done)
        } catch (t: Throwable) {
            done(SystemDiagnosticsClient.Result(false, "진단 시작 실패", "${t.javaClass.simpleName}: ${t.message ?: "메시지 없음"}"))
        }
    }

    private fun copyReport() {
        val report = prefs().getString(KEY_REPORT, latestReport).orEmpty()
        if (report.isBlank()) {
            Toast.makeText(this, "아직 저장된 진단이 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        latestReport = report
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Ride Copilot 진단보고서", report))
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
