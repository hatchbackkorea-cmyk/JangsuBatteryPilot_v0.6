package com.seungjae.jangsu280battery

import android.app.Activity
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged

/** 메인 첫 화면에 RACE용 최소 프로필(이름/닉네임)만 노출한다. */
object RaceProfileUiInstaller {
    private const val TAG = "race_profile_panel_v1"

    fun install(activity: Activity) {
        val anchor = activity.findViewById<View?>(R.id.btnBikeModeEmtb) ?: return
        val parent = anchor.parent as? ViewGroup ?: return
        if (parent.findViewWithTag<View>(TAG) != null) return

        val profile = RaceProfileStore.profile(activity)
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val panel = LinearLayout(activity).apply {
            tag = TAG
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(activity.getColor(R.color.panel))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }

        panel.addView(TextView(activity).apply {
            text = "RACE 프로필"
            textSize = 13f
            setTextColor(activity.getColor(R.color.text_secondary))
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(50)
            ).apply { topMargin = dp(4) }
        }

        val name = EditText(activity).apply {
            hint = "이름"
            setSingleLine(true)
            textSize = 15f
            setText(profile.name)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        val nickname = EditText(activity).apply {
            hint = "닉네임"
            setSingleLine(true)
            textSize = 15f
            setText(profile.nickname)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dp(6)
            }
        }

        fun save() {
            RaceProfileStore.save(activity, name.text?.toString().orEmpty(), nickname.text?.toString().orEmpty())
        }
        name.doAfterTextChanged { save() }
        nickname.doAfterTextChanged { save() }

        row.addView(name)
        row.addView(nickname)
        panel.addView(row)

        val index = parent.indexOfChild(anchor).coerceAtLeast(0)
        parent.addView(panel, index)
    }
}
