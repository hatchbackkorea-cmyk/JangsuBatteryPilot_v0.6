package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.WeakHashMap

/**
 * Process-start room/session policy for TimeGate.
 *
 * The participant identity (RaceProfileStore.profileId) and downloaded GPX files remain durable,
 * but a room selection/token is intentionally session-scoped. After the app process starts again,
 * the rider must enter the room again. The server then reconnects that profileId to the existing
 * participant and previous attempts.
 */
class RaceRuntimeSessionGuardProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val ctx = context?.applicationContext ?: return true
        clearLocalRoomSelection(ctx)
        (ctx as? Application)?.registerActivityLifecycleCallbacks(RaceDnfLifecycleCallbacks)
        return true
    }

    private fun clearLocalRoomSelection(context: Context) {
        val prefs = context.getSharedPreferences("race_runtime_v1", Context.MODE_PRIVATE)
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith("joined_") }.forEach(edit::remove)
        edit.remove("last_event_code").remove("active_config").apply()

        context.getSharedPreferences("race_lap_session_v1", Context.MODE_PRIVATE)
            .edit().clear().apply()

        RaceDataStore(context).writeSnapshot(
            RaceDataStore.Snapshot(
                state = "STOPPED",
                serverStatus = "앱 재실행 · 참가할 방을 다시 선택해 주세요."
            )
        )
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

private object RaceDnfLifecycleCallbacks : Application.ActivityLifecycleCallbacks {
    override fun onActivityResumed(activity: Activity) {
        if (activity is RaceActivity) RaceDnfUiInstaller.install(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity is RaceActivity) RaceDnfUiInstaller.uninstall(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}

/** Adds a DNF control to the blue live timing screen only while a lap is RUNNING. */
object RaceDnfUiInstaller {
    private const val TAG = "timegate_dnf_button_v03473"
    private data class State(val handler: Handler, val runnable: Runnable)
    private val states = WeakHashMap<RaceActivity, State>()

    fun install(activity: RaceActivity) {
        if (states.containsKey(activity)) return
        val handler = Handler(Looper.getMainLooper())
        lateinit var runner: Runnable
        runner = Runnable {
            if (activity.isFinishing || activity.isDestroyed) return@Runnable
            update(activity)
            handler.postDelayed(runner, 250L)
        }
        states[activity] = State(handler, runner)
        handler.post(runner)
    }

    fun uninstall(activity: RaceActivity) {
        states.remove(activity)?.let { it.handler.removeCallbacks(it.runnable) }
    }

    private fun update(activity: RaceActivity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val snapshot = RaceDataStore(activity).snapshot()
        val currentLabel = findText(root, "CURRENT")
        val existing = root.findViewWithTag<Button>(TAG)

        if (currentLabel == null) {
            existing?.visibility = View.GONE
            return
        }

        val topBack = findButton(root) { it.text?.toString()?.contains("Live", ignoreCase = true) == true }
        val top = topBack?.parent as? LinearLayout ?: return
        val button = existing ?: Button(activity).apply {
            tag = TAG
            text = "DNF"
            isAllCaps = false
            textSize = 14f
            gravity = Gravity.CENTER
            setOnClickListener { confirmDnf(activity) }
        }.also { b ->
            val index = (top.childCount - 1).coerceAtLeast(1)
            top.addView(b, index, LinearLayout.LayoutParams(dp(activity, 72), dp(activity, 42)).apply {
                marginEnd = dp(activity, 6)
            })
        }
        button.visibility = if (snapshot.state == "RUNNING") View.VISIBLE else View.GONE
        button.isEnabled = snapshot.state == "RUNNING"
    }

    private fun confirmDnf(activity: RaceActivity) {
        val snapshot = RaceDataStore(activity).snapshot()
        if (snapshot.state != "RUNNING") return
        AlertDialog.Builder(activity)
            .setTitle("현재 랩을 DNF 처리할까요?")
            .setMessage("현재 랩 타이머가 즉시 멈추고 DNF로 저장됩니다. 이후 START 게이트 대기 상태로 돌아가며, 다시 START 지점을 통과하면 새 랩이 0부터 시작됩니다.")
            .setNegativeButton("계속 주행", null)
            .setPositiveButton("DNF 처리") { _, _ -> RaceDnfController.finishAsDnf(activity) }
            .show()
    }

    private fun findText(root: View, text: String): TextView? {
        if (root is TextView && root.text?.toString() == text) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) findText(root.getChildAt(i), text)?.let { return it }
        return null
    }

    private fun findButton(root: View, predicate: (Button) -> Boolean): Button? {
        if (root is Button && predicate(root)) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) findButton(root.getChildAt(i), predicate)?.let { return it }
        return null
    }

    private fun dp(activity: Activity, value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}

/**
 * Manual DNF terminates only the current lap. It never clears the room participation for this
 * process. A fresh ARMED run is created immediately so the next START crossing starts a new lap.
 */
object RaceDnfController {
    fun finishAsDnf(activity: RaceActivity) {
        val store = RaceDataStore(activity)
        val snapshot = store.snapshot()
        val active = store.activeConfig()
        if (snapshot.state != "RUNNING" || active == null || snapshot.runId.isBlank()) {
            Toast.makeText(activity, "현재 진행 중인 랩이 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        val cfg = active.first
        val courseId = active.second
        val elapsed = when {
            snapshot.startedElapsedNs > 0L -> ((SystemClock.elapsedRealtimeNanos() - snapshot.startedElapsedNs) / 1_000_000L).coerceAtLeast(1L)
            snapshot.startedAtMs > 0L -> (System.currentTimeMillis() - snapshot.startedAtMs).coerceAtLeast(1L)
            else -> snapshot.elapsedMs.coerceAtLeast(1L)
        }
        val finishedAt = System.currentTimeMillis()
        val summary = RaceRunSummary(
            runId = snapshot.runId,
            runNumber = snapshot.runNumber.coerceAtLeast(1),
            eventCode = cfg.eventCode,
            eventName = cfg.name,
            courseId = courseId,
            courseName = snapshot.courseName.ifBlank { cfg.courseName },
            startedAtMs = snapshot.startedAtMs,
            finishedAtMs = finishedAt,
            elapsedMs = elapsed,
            status = "DNF",
            sectors = snapshot.sectors,
            reference = emptyList(),
            maxSpeedKph = snapshot.maxSpeedKph,
            maxGpsAccuracyM = snapshot.maxGpsAccuracyM,
            maxOffRouteM = snapshot.maxOffRouteM,
            timingJson = JSONObject().apply {
                put("dnf", true)
                put("reason", "USER_DNF")
            }.toString()
        )
        store.saveCompleted(summary)

        val client = RaceServerClient(activity)
        if (!cfg.eventCode.equals("PRACTICE", ignoreCase = true)) {
            val joined = store.joined(cfg.eventCode, client.baseUrl())
            if (joined != null) {
                val payload = summary.toJson().apply {
                    put("dnf", true)
                    put("dnf_reason", "USER_DNF")
                    put("validation_reason", "DNF")
                }
                store.enqueue("FINISH", cfg.eventCode, payload, joined.serverUrl.ifBlank { client.baseUrl() })
                Thread { runCatching { client.flushPending() } }.start()
            }
        }

        store.writeSnapshot(
            snapshot.copy(
                state = "STOPPED",
                elapsedMs = elapsed,
                validation = "DNF",
                serverStatus = "DNF · 현재 랩 종료 · 다음 START 대기 준비"
            )
        )
        runCatching { activity.stopService(Intent(activity, RaceTimingService::class.java)) }

        Handler(Looper.getMainLooper()).postDelayed({
            if (activity.isFinishing || activity.isDestroyed) return@postDelayed
            store.saveActiveConfig(cfg, courseId, cfg.reference)
            activity.startForegroundService(Intent(activity, RaceTimingService::class.java).apply {
                action = RaceTimingService.ACTION_ARM
                putExtra(RaceTimingService.EXTRA_CONFIG, cfg.toJson().toString())
                putExtra(RaceTimingService.EXTRA_COURSE_ID, courseId)
            })
            Toast.makeText(activity, "DNF 처리 완료 · 다음 START 게이트를 통과하면 새 랩이 시작됩니다.", Toast.LENGTH_LONG).show()
        }, 350L)
    }
}
