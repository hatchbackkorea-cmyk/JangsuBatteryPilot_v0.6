package com.seungjae.jangsu280battery

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged

/** 메인 첫 화면에 RACE용 최소 프로필(이름/닉네임)과 RACE 메뉴를 노출한다. */
object RaceProfileUiInstaller {
    private const val TAG = "race_profile_panel_v1"
    private const val TAG_BUTTON = "race_mode_button_v1"

    fun install(activity: Activity) {
        val anchor = activity.findViewById<View?>(R.id.btnBikeModeEmtb) ?: return
        val parent = anchor.parent as? ViewGroup ?: return
        if (parent.findViewWithTag<View>(TAG) != null) return

        val profile = RaceProfileStore.profile(activity)
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val panel = LinearLayout(activity).apply {
            tag = TAG; orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8)); setBackgroundColor(activity.getColor(R.color.panel))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        panel.addView(TextView(activity).apply {
            text = "프로필 · RACE/순위 표시용"; textSize = 13f; setTextColor(activity.getColor(R.color.text_secondary)); setTypeface(typeface, Typeface.BOLD)
        })
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(4) }
        }
        val name = EditText(activity).apply {
            hint = "이름"; setSingleLine(true); textSize = 15f; setText(profile.name)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        val nickname = EditText(activity).apply {
            hint = "닉네임"; setSingleLine(true); textSize = 15f; setText(profile.nickname)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(6) }
        }
        fun save() { RaceProfileStore.save(activity, name.text?.toString().orEmpty(), nickname.text?.toString().orEmpty()) }
        name.doAfterTextChanged { save() }; nickname.doAfterTextChanged { save() }
        row.addView(name); row.addView(nickname); panel.addView(row)

        val raceButton = Button(activity).apply {
            tag = TAG_BUTTON; text = "🏁 RACE MODE\n실시간 타임어택 · 섹터 · LIVE"; textSize = 19f; setTypeface(typeface, Typeface.BOLD); isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(92)).apply { topMargin = dp(12) }
            setOnClickListener {
                val p = RaceProfileStore.profile(activity)
                if (!p.isReady) Toast.makeText(activity, "이름과 닉네임만 입력해 주세요.", Toast.LENGTH_SHORT).show()
                else activity.startActivity(Intent(activity, RaceActivity::class.java))
            }
        }

        val index = parent.indexOfChild(anchor).coerceAtLeast(0)
        parent.addView(panel, index)
        parent.addView(raceButton, index + 1)
    }
}
