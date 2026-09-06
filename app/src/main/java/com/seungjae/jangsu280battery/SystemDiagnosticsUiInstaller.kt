package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Injects one-click system diagnostics into the existing 관리자 > 실험/진단 panel. */
object SystemDiagnosticsUiInstaller {
    private const val TAG_ROOT = "system_diagnostics_ui_v03425"
    private var latestReport: String = ""

    fun install(activity: AdminCenterActivity) {
        val anchor = activity.findViewById<Button?>(R.id.btnAdminSramDiagnostic) ?: return
        val parent = anchor.parent as? LinearLayout ?: return
        if (parent.findViewWithTag<View>(TAG_ROOT) != null) return

        val box = LinearLayout(activity).apply {
            tag = TAG_ROOT
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(activity, 8), 0, 0)
        }
        val title = TextView(activity).apply {
            text = "🩺 전체 시스템 진단"
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }
        val status = TextView(activity).apply {
            text = "앱·GPS 권한·네트워크·저장 코스·서버·DB·Tailscale/Funnel·외부주소·APK를 한 번에 확인합니다."
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(activity, 4), 0, dp(activity, 6))
        }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val diagnose = Button(activity).apply {
            text = "🩺 진단"
            isAllCaps = false
            setOnClickListener {
                showRunDialog(activity, repair = false, inlineStatus = status, sourceButton = this)
            }
        }
        val repair = Button(activity).apply {
            text = "🛠 자동복구"
            isAllCaps = false
            setOnClickListener {
                showRunDialog(activity, repair = true, inlineStatus = status, sourceButton = this)
            }
        }
        row.addView(diagnose, LinearLayout.LayoutParams(0, dp(activity, 48), 1f))
        row.addView(repair, LinearLayout.LayoutParams(0, dp(activity, 48), 1f).apply { marginStart = dp(activity, 6) })

        val copy = Button(activity).apply {
            text = "📋 진단보고서 복사"
            isAllCaps = false
            setOnClickListener {
                copyReport(activity)
            }
        }

        box.addView(title)
        box.addView(status)
        box.addView(row)
        box.addView(copy, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 46)).apply { topMargin = dp(activity, 6) })
        parent.addView(box)
    }

    /**
     * Always opens a dedicated modal first, then runs diagnostics in the background.
     * This prevents the result from appearing to vanish when the administrator pager refreshes.
     */
    private fun showRunDialog(
        activity: AdminCenterActivity,
        repair: Boolean,
        inlineStatus: TextView,
        sourceButton: Button
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val resultView = TextView(activity).apply {
            text = if (repair) "안전 자동복구를 시작합니다…" else "전체 시스템 진단을 시작합니다…"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 18))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(activity).apply {
            addView(resultView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(if (repair) "🛠 안전 자동복구" else "🩺 전체 시스템 진단")
            .setView(scroll)
            .setNegativeButton("닫기", null)
            .setNeutralButton("보고서 복사", null)
            .create()

        dialog.setOnShowListener {
            val copyButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            copyButton.isEnabled = latestReport.isNotBlank()
            copyButton.setOnClickListener { copyReport(activity) }
        }
        dialog.show()

        sourceButton.isEnabled = false
        inlineStatus.setTextColor(Color.LTGRAY)
        inlineStatus.text = if (repair) "안전 자동복구 중… 결과창을 닫아도 작업은 계속됩니다." else "전체 진단 중… 결과창을 닫아도 작업은 계속됩니다."

        val done: (SystemDiagnosticsClient.Result) -> Unit = { result ->
            activity.runOnUiThread {
                sourceButton.isEnabled = true
                latestReport = buildString {
                    append(result.title).append("\n\n").append(result.text)
                    if (result.raw.isNotBlank()) append("\n\n[RAW JSON]\n").append(result.raw)
                }
                inlineStatus.setTextColor(if (result.ok) Color.rgb(95, 220, 135) else Color.rgb(255, 184, 92))
                inlineStatus.text = result.title + " · 결과창 또는 진단보고서 복사에서 확인"

                if (dialog.isShowing) {
                    resultView.setTextColor(if (result.ok) Color.rgb(220, 255, 230) else Color.rgb(255, 220, 180))
                    resultView.text = latestReport
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = true
                } else {
                    Toast.makeText(activity, result.title, Toast.LENGTH_LONG).show()
                }
            }
        }

        if (repair) SystemDiagnosticsClient(activity).safeRepairAsync(done)
        else SystemDiagnosticsClient(activity).diagnoseAsync(done)
    }

    private fun copyReport(activity: Activity) {
        if (latestReport.isBlank()) {
            Toast.makeText(activity, "먼저 진단을 실행해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Ride Copilot 진단보고서", latestReport))
        Toast.makeText(activity, "진단보고서를 복사했습니다. ChatGPT에 그대로 붙여넣으세요.", Toast.LENGTH_LONG).show()
    }

    private fun dp(activity: Activity, v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
}
