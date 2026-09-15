package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout

/** Adds a normal-user broadcast-camera entry in the unused bottom area of the TimeGate home. */
class BroadcastCameraHomeLauncherProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(this)
        return true
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is BikeModeChooserActivity) return
        activity.window.decorView.post { install(activity) }
    }

    private fun install(activity: BikeModeChooserActivity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val home = findHome(activity.window.decorView as? ViewGroup) ?: return
        val existing = home.findViewWithTag<View>(TAG)
        if (existing is Button) {
            refreshLabel(activity, existing)
            return
        }
        val button = Button(activity).apply {
            tag = TAG
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.rgb(12, 91, 235))
                cornerRadius = dp(activity, 14).toFloat()
            }
            elevation = dp(activity, 5).toFloat()
            setOnClickListener {
                activity.startActivity(Intent(activity, BroadcastCameraEnrollmentActivity::class.java))
            }
        }
        home.addView(button, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(activity, 54), Gravity.BOTTOM).apply {
            leftMargin = dp(activity, 24)
            rightMargin = dp(activity, 24)
            bottomMargin = dp(activity, 16)
        })
        refreshLabel(activity, button)
    }

    private fun refreshLabel(activity: Activity, button: Button) {
        val current = TimingOperatorStore.current(activity)
        button.text = if (current != null && TimingOperatorStore.isBroadcastRole(current.role)) {
            "🎥 중계 카메라 · ${current.role} · ${current.eventCode}"
        } else {
            "🎥 중계 카메라 · 경기코드로 연결"
        }
    }

    private fun findHome(root: ViewGroup?): TimeGateHomeView? {
        if (root == null) return null
        if (root is TimeGateHomeView) return root
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is TimeGateHomeView) return child
            if (child is ViewGroup) findHome(child)?.let { return it }
        }
        return null
    }

    private fun dp(activity: Activity, value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val TAG = "timegate_broadcast_camera_launcher_v1"
    }
}
