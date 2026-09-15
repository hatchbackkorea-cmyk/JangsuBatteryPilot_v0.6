package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.WeakHashMap

/**
 * Process-start room/session policy for TimeGate.
 *
 * Participant identity and completed records stay durable, but room selection is session-scoped.
 * After a fresh app process starts, the rider must explicitly enter a room again. Rejoining with
 * the same profileId lets the server reconnect the rider to the existing participant/history.
 */
class RaceRuntimeSessionGuardProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val ctx = context?.applicationContext ?: return true
        clearLocalRoomSelection(ctx)
        (ctx as? Application)?.registerActivityLifecycleCallbacks(RaceRuntimeUiCallbacks)
        return true
    }

    private fun clearLocalRoomSelection(context: Context) {
        val prefs = context.getSharedPreferences("race_runtime_v1", Context.MODE_PRIVATE)
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith("joined_") }.forEach(edit::remove)
        edit.remove("last_event_code").remove("active_config").apply()

        // Local screen-session numbering starts fresh after an app restart. Server-side attempts
        // remain attached to profileId and are recovered when the rider explicitly rejoins.
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
    override fun update(uri: Uri, values: ContentValues?): Int = 0
}

private object RaceRuntimeUiCallbacks : Application.ActivityLifecycleCallbacks {
    override fun onActivityResumed(activity: Activity) {
        if (activity is RaceActivity) RaceRuntimeLiveUiInstaller.install(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity is RaceActivity) RaceRuntimeLiveUiInstaller.uninstall(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}

/**
 * Live-screen cleanup requested for the field UI:
 * - hide the old coloured GPX banner and replace it with plain "fileName · 다운로드 완료" text;
 * - put DNF inside the bottom GPS/status strip as a text action, only while RUNNING.
 *
 * RaceActivity already owns the 10 Hz live renderer. This helper intentionally avoids scanning and
 * restyling the whole view tree every 100 ms; structural work is performed once per content root
 * and later ticks only react to actual GPX/DNF state changes.
 */
object RaceRuntimeLiveUiInstaller {
    private const val TAG_PLAIN_GPX = "timegate_plain_gpx_status_v03473"
    private const val TAG_FOOTER_ROW = "timegate_live_footer_row_v03473"
    private const val TAG_DNF = "timegate_dnf_text_v03473"
    private const val TAG_LEGACY_GPX = "timegate_gpx_status_banner_v03449"
    private const val REFRESH_MS = 250L

    private class State(val handler: Handler) {
        lateinit var runnable: Runnable
        var root: ViewGroup? = null
        var legacyBannerHidden = false
        var footerPrepared = false
        var lastGpxKey = ""
        var lastRunning: Boolean? = null
    }

    private val states = WeakHashMap<RaceActivity, State>()

    fun install(activity: RaceActivity) {
        if (states.containsKey(activity)) return
        val state = State(Handler(Looper.getMainLooper()))
        state.runnable = Runnable {
            if (activity.isFinishing || activity.isDestroyed) return@Runnable
            update(activity, state)
            state.handler.postDelayed(state.runnable, REFRESH_MS)
        }
        states[activity] = state
        state.handler.post(state.runnable)
    }

    fun uninstall(activity: RaceActivity) {
        states.remove(activity)?.let { it.handler.removeCallbacks(it.runnable) }
    }

    private fun update(activity: RaceActivity, state: State) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (state.root !== content) {
            state.root = content
            state.legacyBannerHidden = false
            state.footerPrepared = false
            state.lastGpxKey = ""
            state.lastRunning = null
        }

        if (!state.legacyBannerHidden) {
            // RaceDebugUiInstaller is registered later in the process lifecycle and can create its
            // legacy banner after this installer has already been asked to run. Keep retrying only
            // until that banner exists, then never scan the tree again for the current content root.
            state.legacyBannerHidden = hideLegacyGpxStatus(content)
        }

        updatePlainGpxStatus(activity, content, state)
        state.footerPrepared = installFooterDnf(activity, content, state, state.footerPrepared)
    }

    private fun hideLegacyGpxStatus(root: ViewGroup): Boolean {
        root.findViewWithTag<View>(TAG_LEGACY_GPX)?.let { banner ->
            if (banner.visibility != View.GONE) banner.visibility = View.GONE
            return true
        }

        // Compatibility fallback for an older build where the banner might not have carried a tag.
        var found = false
        walkText(root) { tv ->
            val t = tv.text?.toString().orEmpty()
            if (
                t.startsWith("✓ GPX 다운로드 완료") ||
                t.startsWith("GPX 확인 중") ||
                t.startsWith("GPX 다운로드 중") ||
                t.startsWith("⚠ GPX 다운로드 실패") ||
                t.startsWith("대회 미참가") ||
                t.startsWith("대회 참가됨")
            ) {
                if (tv.visibility != View.GONE) tv.visibility = View.GONE
                found = true
            }
        }
        return found
    }

    private fun updatePlainGpxStatus(activity: RaceActivity, content: ViewGroup, state: State) {
        val info = RaceGpxDownloadStatus.read(activity)
        val joined = RaceDataStore(activity).lastJoined()
        val showCompleted = joined != null &&
            info.eventCode.equals(joined.config.eventCode, ignoreCase = true) &&
            info.state in setOf("DOWNLOADED", "COMPLETED")
        val fileName = info.serverFileName.trim().ifBlank { "GPX 파일" }
        val nextKey = "${showCompleted}|${info.eventCode}|${info.state}|$fileName"

        var view = content.findViewWithTag<TextView>(TAG_PLAIN_GPX)
        if (state.lastGpxKey == nextKey && view != null) return
        state.lastGpxKey = nextKey

        if (!showCompleted) {
            if (view?.visibility != View.GONE) view?.visibility = View.GONE
            return
        }

        if (view == null) {
            view = TextView(activity).apply {
                tag = TAG_PLAIN_GPX
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(Color.WHITE)
                setPadding(dp(activity, 8), dp(activity, 3), dp(activity, 8), dp(activity, 3))
                background = null
                elevation = dp(activity, 8).toFloat()
            }
            val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 28)).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                marginStart = dp(activity, 10)
                marginEnd = dp(activity, 10)
                topMargin = dp(activity, 58)
            }
            content.addView(view, lp)
        }

        val nextText = "$fileName · 다운로드 완료"
        if (view.text?.toString() != nextText) view.text = nextText
        if (view.visibility != View.VISIBLE) view.visibility = View.VISIBLE
        view.bringToFront()
    }

    private fun installFooterDnf(
        activity: RaceActivity,
        content: ViewGroup,
        state: State,
        alreadyPrepared: Boolean
    ): Boolean {
        var row = content.findViewWithTag<LinearLayout>(TAG_FOOTER_ROW)

        if (!alreadyPrepared || row == null) {
            val footer = findFooter(content) ?: return false
            row = footer.parent as? LinearLayout

            if (row?.tag != TAG_FOOTER_ROW) {
                val parent = footer.parent as? LinearLayout ?: return false
                val index = parent.indexOfChild(footer)
                if (index < 0) return false
                parent.removeView(footer)

                row = LinearLayout(activity).apply {
                    tag = TAG_FOOTER_ROW
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(Color.rgb(28, 28, 28))
                }
                footer.setBackgroundColor(Color.TRANSPARENT)
                footer.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                footer.setTextColor(Color.LTGRAY)
                footer.setPadding(dp(activity, 8), 0, dp(activity, 4), 0)
                row.addView(footer, LinearLayout.LayoutParams(0, dp(activity, 38), 1f))
                parent.addView(row, index, LinearLayout.LayoutParams(-1, dp(activity, 38)))
            }
        }

        val targetRow = row ?: return false
        val dnf = targetRow.findViewWithTag<TextView>(TAG_DNF) ?: TextView(activity).apply {
            tag = TAG_DNF
            text = "DNF"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(activity, 8), 0, dp(activity, 10), 0)
            setOnClickListener { confirmDnf(activity) }
        }.also { tv ->
            targetRow.addView(tv, LinearLayout.LayoutParams(dp(activity, 62), dp(activity, 38)))
        }

        val running = RaceDataStore(activity).snapshot().state == "RUNNING"
        if (state.lastRunning != running) {
            dnf.visibility = if (running) View.VISIBLE else View.GONE
            dnf.isEnabled = running
            state.lastRunning = running
        }
        return true
    }

    private fun confirmDnf(activity: RaceActivity) {
        val snapshot = RaceDataStore(activity).snapshot()
        if (snapshot.state != "RUNNING") return
        AlertDialog.Builder(activity)
            .setTitle("현재 랩을 DNF 처리할까요?")
            .setMessage("현재 랩 타이머를 즉시 멈추고 DNF로 저장합니다. 이후 START 게이트 대기로 돌아가며, 다시 START 지점을 통과하면 새 랩이 0부터 시작됩니다.")
            .setNegativeButton("계속 주행", null)
            .setPositiveButton("DNF 처리") { _, _ -> RaceDnfController.finishAsDnf(activity) }
            .show()
    }

    private fun findFooter(root: View): TextView? {
        if (root is TextView && root.text?.toString()?.startsWith("GPS ±") == true) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findFooter(root.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun walkText(root: View, block: (TextView) -> Unit) {
        if (root is TextView) block(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) walkText(root.getChildAt(i), block)
    }

    private fun dp(activity: Activity, value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}

/**
 * Manual DNF terminates only the current lap. DNF is durable history but never a BEST/PREVIOUS/
 * leaderboard record. The service is re-armed so the next START crossing begins a fresh lap.
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
            Toast.makeText(activity, "DNF 처리 완료 · 다음 START 통과 시 새 랩 시작", Toast.LENGTH_LONG).show()
        }, 350L)
    }
}
