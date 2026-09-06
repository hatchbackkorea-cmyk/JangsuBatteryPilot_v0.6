package com.seungjae.jangsu280battery

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Injects stable one-click diagnostics launchers into 관리자 > 실험/진단. */
object SystemDiagnosticsUiInstaller {
    private const val TAG_ROOT = "system_diagnostics_ui_v03426"
    private const val PREFS = "system_diagnostics"
    private const val KEY_REPORT = "latest_report"

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
            text = "진단 결과는 별도 화면에서 표시됩니다. 앱·GPS·네트워크·코스·서버·DB·Tailscale/Funnel·외부주소·APK를 한 번에 확인합니다."
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(activity, 4), 0, dp(activity, 6))
        }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val diagnose = Button(activity).apply {
            text = "🩺 진단"
            isAllCaps = false
            setOnClickListener { openDiagnostics(activity, false) }
        }
        val repair = Button(activity).apply {
            text = "🛠 자동복구"
            isAllCaps = false
            setOnClickListener { openDiagnostics(activity, true) }
        }
        row.addView(diagnose, LinearLayout.LayoutParams(0, dp(activity, 48), 1f))
        row.addView(repair, LinearLayout.LayoutParams(0, dp(activity, 48), 1f).apply { marginStart = dp(activity, 6) })

        val copy = Button(activity).apply {
            text = "📋 최근 진단보고서 복사"
            isAllCaps = false
            setOnClickListener {
                val report = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_REPORT, "").orEmpty()
                if (report.isBlank()) {
                    Toast.makeText(activity, "아직 저장된 진단보고서가 없습니다. 먼저 진단을 실행해 주세요.", Toast.LENGTH_SHORT).show()
                } else {
                    val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Ride Copilot 진단보고서", report))
                    Toast.makeText(activity, "최근 진단보고서를 복사했습니다.", Toast.LENGTH_LONG).show()
                }
            }
        }

        box.addView(title)
        box.addView(status)
        box.addView(row)
        box.addView(copy, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 46)).apply { topMargin = dp(activity, 6) })
        parent.addView(box)
    }

    private fun openDiagnostics(activity: AdminCenterActivity, repair: Boolean) {
        activity.startActivity(Intent(activity, SystemDiagnosticsActivity::class.java).apply {
            putExtra(SystemDiagnosticsActivity.EXTRA_MODE, if (repair) SystemDiagnosticsActivity.MODE_REPAIR else SystemDiagnosticsActivity.MODE_DIAGNOSE)
        })
    }

    private fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
