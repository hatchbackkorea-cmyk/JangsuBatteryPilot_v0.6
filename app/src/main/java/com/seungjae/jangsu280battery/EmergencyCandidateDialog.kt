package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 비상충전 후보 선택용 공통 다이얼로그.
 *
 * Android AlertDialog는 setMessage()와 setItems()를 함께 지정하면 기기/테마에 따라
 * message 영역만 표시되고 list가 붙지 않는 경우가 있다. v0.26.9부터는 후보를
 * 명시적인 버튼 목록으로 그려 실제 주행/시뮬레이터 모두에서 선택 가능하게 한다.
 */
object EmergencyCandidateDialog {
    fun show(
        activity: Activity,
        title: String,
        intro: String,
        items: List<EvaluatedEmergencyCandidate>,
        hardReserve: Int,
        onSelect: (EvaluatedEmergencyCandidate) -> Unit
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(4))
        }

        root.addView(TextView(activity).apply {
            text = intro
            setTextColor(activity.getColor(R.color.text_secondary))
            textSize = 12f
            setLineSpacing(0f, 1.12f)
            setPadding(0, 0, 0, dp(8))
        })

        val firstSafeIndex = items.indexOfFirst { it.predictedArrivalSoc >= hardReserve }
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val bindings = mutableListOf<Pair<Button, EvaluatedEmergencyCandidate>>()

        items.forEachIndexed { index, c ->
            val safe = c.predictedArrivalSoc >= hardReserve
            val reach = if (safe) "도달 가능" else "위험"
            val recommended = if (index == firstSafeIndex) "★ 추천 · " else ""
            val detail = buildString {
                append("$recommended${c.place.confidence}급 · $reach · ${c.place.name}\n")
                append("편도 ${RideFormatter.one(c.outbound.distanceKm)}km · 약 ${c.outbound.minutes.toInt().coerceAtLeast(1)}분")
                append(" · 도착 ${c.predictedArrivalSoc.toInt()}%\n")
                append("왕복 ${RideFormatter.one(c.roundTripKm)}km · ${c.place.confidenceLabel}")
            }
            val button = Button(activity).apply {
                setAllCaps(false)
                text = detail
                textSize = 13f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setTextColor(activity.getColor(R.color.text_primary))
                setPadding(dp(12), dp(8), dp(12), dp(8))
                minHeight = dp(82)
                if (index == firstSafeIndex) setTypeface(typeface, Typeface.BOLD)
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
            list.addView(button, lp)
            bindings += button to c
        }

        val scroll = ScrollView(activity).apply {
            isFillViewport = false
            addView(list)
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(330)
        ))

        root.addView(TextView(activity).apply {
            text = "후보를 누르면 다음 단계로 진행합니다."
            setTextColor(activity.getColor(R.color.accent))
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(7), 0, 0)
        })

        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(root)
            .setNegativeButton("취소", null)
            .create()

        bindings.forEach { (button, item) ->
            button.setOnClickListener {
                dialog.dismiss()
                onSelect(item)
            }
        }
        dialog.show()
    }
}
