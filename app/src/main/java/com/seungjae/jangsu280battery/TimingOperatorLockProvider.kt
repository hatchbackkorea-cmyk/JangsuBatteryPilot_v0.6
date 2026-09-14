package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.WeakHashMap
import java.util.concurrent.Executors

/** Locks QR-assigned Camera Gate roles and receives admin identify pings. */
class TimingOperatorLockProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val pollJobs = WeakHashMap<Activity, Runnable>()
    private val lastPingSeq = WeakHashMap<Activity, Int>()
    private val dialogs = WeakHashMap<Activity, AlertDialog>()

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(this)
        return true
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is CameraGateHighSpeedActivity) return
        applyLock(activity)
        main.postDelayed({ applyLock(activity) }, 500L)
        main.postDelayed({ applyLock(activity) }, 1_700L)
        startPoll(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity is CameraGateHighSpeedActivity) stopPoll(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        stopPoll(activity)
        dialogs.remove(activity)?.dismiss()
        lastPingSeq.remove(activity)
    }

    private fun applyLock(activity: CameraGateHighSpeedActivity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val assignment = TimingOperatorStore.current(activity) ?: return
        val status = findText(activity.window.decorView as? ViewGroup) ?: return
        status.text = "게이트 역할 · 🔒 ${assignment.role} · ${assignment.eventCode} · 공식 계측폰"
        val parent = status.parent as? ViewGroup
        if (parent != null) {
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                if (child is Spinner) {
                    child.isEnabled = false
                    child.visibility = View.GONE
                }
            }
        }
    }

    private fun startPoll(activity: CameraGateHighSpeedActivity) {
        stopPoll(activity)
        val job = object : Runnable {
            override fun run() {
                if (activity.isFinishing || activity.isDestroyed) {
                    pollJobs.remove(activity)
                    return
                }
                val assignment = TimingOperatorStore.current(activity)
                if (assignment == null) {
                    pollJobs.remove(activity)
                    return
                }
                executor.execute {
                    val result = runCatching { TimingDeviceClient.poll(activity.applicationContext, assignment) }.getOrNull()
                    activity.runOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        if (result != null) {
                            if (!result.active) {
                                TimingOperatorStore.clear(activity)
                                Toast.makeText(activity, "이 계측폰은 관리자 운영툴에서 해제되었습니다.", Toast.LENGTH_LONG).show()
                                activity.finish()
                                return@runOnUiThread
                            }
                            val previous = lastPingSeq[activity] ?: 0
                            if (result.pingSeq > previous && result.pingRequestedAtMs > 0L && System.currentTimeMillis() - result.pingRequestedAtMs <= 10_000L) {
                                showIdentifyPopup(activity, assignment)
                            }
                            lastPingSeq[activity] = maxOf(previous, result.pingSeq)
                        }
                    }
                }
                main.postDelayed(this, 1_000L)
            }
        }
        pollJobs[activity] = job
        main.post(job)
    }

    private fun stopPoll(activity: Activity) {
        pollJobs.remove(activity)?.let { main.removeCallbacks(it) }
    }

    private fun showIdentifyPopup(activity: Activity, assignment: TimingOperatorStore.Assignment) {
        dialogs.remove(activity)?.dismiss()
        val body = TextView(activity).apply {
            text = "${assignment.role}\n이 기기입니다\n\n${TimingOperatorStore.deviceLabel(activity)}"
            textSize = 31f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(activity, 24), dp(activity, 36), dp(activity, 24), dp(activity, 36))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("계측폰 확인")
            .setView(body)
            .create()
        dialogs[activity] = dialog
        dialog.setOnDismissListener { dialogs.remove(activity) }
        dialog.show()
        main.postDelayed({ if (dialog.isShowing) dialog.dismiss() }, 5_000L)
    }

    private fun findText(root: ViewGroup?): TextView? {
        if (root == null) return null
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is TextView && child.text?.toString()?.startsWith("게이트 역할 ·") == true) return child
            if (child is ViewGroup) findText(child)?.let { return it }
        }
        return null
    }

    private fun dp(activity: Activity, v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
