package com.seungjae.jangsu280battery

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout

/**
 * Keeps the saved-course launcher at the very bottom of the RACE course-builder screen.
 *
 * The GPS quality overlay occupies the upper portion of the screen, so placing the launcher near
 * the top can make it look hidden. This fixer runs immediately after the launcher is installed and
 * moves that exact button to the end of the root vertical layout.
 */
object RaceSavedCourseBrowserBottomFix {
    fun apply(activity: RaceTrackBuilderActivity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = content.getChildAt(0) as? LinearLayout ?: return
        val button = findSavedCourseButton(root) ?: return
        if (root.indexOfChild(button) == root.childCount - 1) return

        (button.parent as? ViewGroup)?.removeView(button)
        root.addView(button, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(activity, 46)
        ))
    }

    private fun findSavedCourseButton(root: LinearLayout): Button? {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is Button && child.text?.toString()?.contains("저장된 RACE 코스") == true) {
                return child
            }
        }
        return null
    }

    private fun dp(activity: RaceTrackBuilderActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
