package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.util.WeakHashMap

/**
 * Keeps the blue lap-timer screen uncluttered by moving the live state/identity line out of the
 * grey top bar and into the bottom GPS/status strip.
 */
class RaceLiveStatusRelocatorProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(RaceLiveStatusRelocatorCallbacks)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

private object RaceLiveStatusRelocatorCallbacks : Application.ActivityLifecycleCallbacks {
    override fun onActivityResumed(activity: Activity) {
        if (activity is RaceActivity) RaceLiveStatusRelocator.install(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity is RaceActivity) RaceLiveStatusRelocator.uninstall(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}

object RaceLiveStatusRelocator {
    private const val TAG_STATUS = "timegate_live_footer_state_v03474"
    private const val TAG_FOOTER_ROW = "timegate_live_footer_row_v03473"
    private const val STATUS_WIDTH_DP = 152
    private const val STATUS_TEXT_SP = 10f

    private data class State(val handler: Handler, val runnable: Runnable)
    private val states = WeakHashMap<RaceActivity, State>()

    fun install(activity: RaceActivity) {
        if (states.containsKey(activity)) return
        val handler = Handler(Looper.getMainLooper())
        lateinit var runner: Runnable
        runner = Runnable {
            if (activity.isFinishing || activity.isDestroyed) return@Runnable
            apply(activity)
            handler.postDelayed(runner, 100L)
        }
        states[activity] = State(handler, runner)
        handler.post(runner)
    }

    fun uninstall(activity: RaceActivity) {
        states.remove(activity)?.let { it.handler.removeCallbacks(it.runnable) }
    }

    private fun apply(activity: RaceActivity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val snapshot = RaceDataStore(activity).snapshot()

        // Remove the ARMED/RUNNING/FINISH identity line from the grey top bar entirely.
        hideTopStateText(root)

        // RaceRuntimeLiveUiInstaller owns the GPS/DNF row. Wait until it has wrapped the footer,
        // then add the moved state text as the first item in that same bottom row.
        val row = root.findViewWithTag<LinearLayout>(TAG_FOOTER_ROW) ?: return
        val status = row.findViewWithTag<TextView>(TAG_STATUS) ?: TextView(activity).apply {
            tag = TAG_STATUS
            textSize = STATUS_TEXT_SP
            setTextColor(Color.WHITE)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(activity, 8), 0, dp(activity, 4), 0)
        }.also { view ->
            row.addView(view, 0, LinearLayout.LayoutParams(dp(activity, STATUS_WIDTH_DP), dp(activity, 38)))
        }

        status.text = footerState(snapshot)
        status.visibility = View.VISIBLE
        status.setTextColor(Color.WHITE)
        status.textSize = STATUS_TEXT_SP
    }

    private fun footerState(s: RaceDataStore.Snapshot): String {
        val identity = s.courseName.ifBlank { s.eventName.ifBlank { s.eventCode.ifBlank { "TimeGate" } } }
        return when (s.state) {
            "WATCHING" -> "AUTO · START 탐색 · $identity"
            "ARMED" -> "ARMED · START GATE · $identity"
            "RUNNING" -> "RUNNING · ${s.currentSector.ifBlank { "NEXT CP" }} · $identity"
            "FINISHED" -> if (s.serverStatus.contains("랩타임 확인중")) "FINISH · 확인중 · $identity" else "FINISH · $identity"
            else -> "READY · $identity"
        }
    }

    private fun hideTopStateText(root: View) {
        if (root is TextView) {
            val text = root.text?.toString().orEmpty().trim()
            if (
                text.startsWith("ARMED ·") ||
                text.startsWith("RUNNING ·") ||
                text.startsWith("FINISH ·") ||
                text.startsWith("READY ·") ||
                text.startsWith("AUTO · 코스 START")
            ) {
                root.visibility = View.GONE
            }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) hideTopStateText(root.getChildAt(i))
        }
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
