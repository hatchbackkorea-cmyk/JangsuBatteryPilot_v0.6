package com.seungjae.jangsu280battery

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout

/**
 * Keeps the saved-course launcher in the same action row as "서버 등록".
 * This avoids both the GPS overlay at the top and the Android system navigation bar at the bottom.
 */
object RaceSavedCourseInlineFix {
    private const val TAG = "race_saved_courses_inline_v03422"

    fun apply(activity: RaceTrackBuilderActivity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val serverButton = findButton(content) { it.text?.toString() == "서버 등록" } ?: return
        val row = serverButton.parent as? LinearLayout ?: return

        // Remove the older launcher wherever it was injected (top/bottom) so only one remains.
        findButton(content) { b ->
            b !== serverButton && (b.tag?.toString()?.contains("race_saved_courses_launcher") == true ||
                b.text?.toString()?.contains("저장된 RACE 코스") == true ||
                b.tag?.toString() == TAG)
        }?.let { old -> (old.parent as? ViewGroup)?.removeView(old) }

        if (findButton(row) { it.tag == TAG } != null) return

        val button = Button(activity).apply {
            tag = TAG
            text = "저장 코스"
            isAllCaps = false
            textSize = 12f
            setOnClickListener {
                activity.startActivity(Intent(activity, RaceSavedCoursesActivity::class.java))
            }
        }
        val index = (row.indexOfChild(serverButton) + 1).coerceAtMost(row.childCount)
        row.addView(
            button,
            index,
            LinearLayout.LayoutParams(0, dp(activity, 48), 1f).apply { marginStart = dp(activity, 6) }
        )
    }

    private fun findButton(root: View, predicate: (Button) -> Boolean): Button? {
        if (root is Button && predicate(root)) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findButton(root.getChildAt(i), predicate)?.let { return it }
            }
        }
        return null
    }

    private fun dp(activity: RaceTrackBuilderActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
