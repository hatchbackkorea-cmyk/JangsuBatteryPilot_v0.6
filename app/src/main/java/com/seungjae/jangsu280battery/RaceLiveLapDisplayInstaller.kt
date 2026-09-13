package com.seungjae.jangsu280battery

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * Owns BEST/PREVIOUS/CURRENT and repurposes the old DELTA area as a simple course-best panel.
 * Full lap history belongs on RaceLapHistoryActivity.
 *
 * Room isolation rule:
 * - PRACTICE uses this phone's exact local courseId.
 * - A joined event uses eventCode as the durable room boundary. Re-downloading the same server GPX
 *   may create a different local courseId, so local ids must never split one server room's history.
 * - The event course-best panel is server-authoritative and never merges unrelated room history.
 *
 * CP feedback rule:
 * - CP popup is driven only by the phone-local Snapshot.sectors list, never by a server round trip.
 * - The first observed snapshot is a baseline so opening the live screen after CP1 does not replay it.
 * - FINISH is intentionally excluded because the normal FINISH UI owns that moment.
 */
object RaceLiveLapDisplayInstaller {
    private const val TAG_HISTORY = "timegate_lap_history_v03444"
    private const val TAG_OLD_LEADER_ROW = "timegate_live_leader_row_v03444"
    private const val TAG_COURSE_BEST_LABEL = "timegate_course_best_label_v03453"
    private const val TAG_COURSE_BEST_VALUE = "timegate_course_best_value_v03453"
    private const val CP_POPUP_HOLD_MS = 1_450L

    private class State(val handler: Handler, val runnable: Runnable) {
        var cpRunId: String = ""
        var cpSectorCount: Int = 0
        var cpPopup: PopupWindow? = null
        var cpDismiss: Runnable? = null
    }

    private val states = WeakHashMap<RaceActivity, State>()

    fun install(activity: RaceActivity) {
        if (states.containsKey(activity)) return
        val handler = Handler(Looper.getMainLooper())
        lateinit var runner: Runnable
        runner = Runnable {
            if (activity.isFinishing || activity.isDestroyed) return@Runnable
            update(activity)
            handler.postDelayed(runner, 100L)
        }
        states[activity] = State(handler, runner)
        handler.post(runner)
    }

    fun uninstall(activity: RaceActivity) {
        states.remove(activity)?.let { state ->
            state.handler.removeCallbacks(state.runnable)
            state.cpDismiss?.let(state.handler::removeCallbacks)
            runCatching { state.cpPopup?.dismiss() }
            state.cpPopup = null
            state.cpDismiss = null
        }
    }

    private fun update(activity: RaceActivity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val store = RaceDataStore(activity)
        val snapshot = store.snapshot()
        val active = store.activeConfig()
        val courseId = snapshot.courseId.ifBlank { active?.second.orEmpty() }
        if (courseId.isBlank()) return
        val eventCode = snapshot.eventCode.ifBlank { active?.first?.eventCode.orEmpty() }.ifBlank { "PRACTICE" }.uppercase()

        updateCourseBestPanel(activity, root, store, snapshot, eventCode, courseId)

        val bestBlock = findBlock(root, "BEST") ?: return
        val previousBlock = findBlock(root, "PREVIOUS") ?: return
        val currentBlock = findBlock(root, "CURRENT") ?: return

        installHistoryButton(activity, root, courseId, eventCode)

        // App process/session restarts must not hide records already created in the same server room.
        // A server-room record is identified by eventCode; local PRACTICE still uses exact courseId.
        val completedForRoom = store.completed()
            .asSequence()
            .filter { sameRoom(it, eventCode, courseId) }
            .filter { it.elapsedMs > 0L }
            .sortedBy { it.finishedAtMs }
            .toList()
        val comparisonRuns = completedForRoom
            .filter { it.status.equals("VALID", ignoreCase = true) }

        states[activity]?.let { state ->
            updateCpPopup(activity, root, state, snapshot, comparisonRuns)
        }

        val currentCompletedIndex = completedForRoom.indexOfFirst { it.runId == snapshot.runId }
        val best = comparisonRuns.minByOrNull { it.elapsedMs }
        val bestLapNo = best?.let { target ->
            completedForRoom.indexOfFirst { it.runId == target.runId }
                .takeIf { it >= 0 }
                ?.plus(1)
        }

        val previous = comparisonRuns.lastOrNull { run ->
            when {
                snapshot.startedAtMs > 0L -> run.finishedAtMs <= snapshot.startedAtMs && run.runId != snapshot.runId
                else -> run.runId != snapshot.runId
            }
        } ?: comparisonRuns.lastOrNull { it.runId != snapshot.runId }
        val previousLapNo = previous?.let { target ->
            completedForRoom.indexOfFirst { it.runId == target.runId }
                .takeIf { it >= 0 }
                ?.plus(1)
        }

        val currentLapNo = when {
            currentCompletedIndex >= 0 -> currentCompletedIndex + 1
            else -> completedForRoom.size + 1
        }.coerceAtLeast(1)

        setBlock(bestBlock, bestLapNo, best?.elapsedMs)
        setBlock(previousBlock, previousLapNo, previous?.elapsedMs)

        val heldId = if (snapshot.state == "ARMED") RaceTimingPendingStore.justFinalized(activity) else null
        val heldIndex = if (heldId == null) -1 else completedForRoom.indexOfFirst { it.runId == heldId }
        if (heldIndex >= 0) {
            val held = completedForRoom[heldIndex]
            if (held.status.equals("VALID", ignoreCase = true)) {
                setBlock(currentBlock, heldIndex + 1, held.elapsedMs)
                val before = comparisonRuns.lastOrNull { it.finishedAtMs < held.finishedAtMs }
                val beforeLap = before?.let { b -> completedForRoom.indexOfFirst { it.runId == b.runId } + 1 }?.takeIf { it > 0 }
                setBlock(previousBlock, beforeLap, before?.elapsedMs)
                return
            }
        }

        val verifying = snapshot.state == "FINISHED" && snapshot.serverStatus.contains("랩타임 확인중")
        if (verifying) {
            setBlockText(currentBlock, currentLapNo, "확인중")
        } else {
            setBlock(currentBlock, currentLapNo, currentElapsed(snapshot))
        }
    }

    private fun updateCpPopup(
        activity: RaceActivity,
        anchor: View,
        state: State,
        snapshot: RaceDataStore.Snapshot,
        comparisonRuns: List<RaceRunSummary>
    ) {
        val runId = snapshot.runId
        if (runId.isBlank()) {
            state.cpRunId = ""
            state.cpSectorCount = 0
            return
        }

        // New run/activity baseline: do not replay CPs that happened before this screen observed it.
        if (state.cpRunId != runId) {
            state.cpRunId = runId
            state.cpSectorCount = snapshot.sectors.size
            dismissCpPopup(state)
            return
        }

        val count = snapshot.sectors.size
        if (count <= state.cpSectorCount) {
            if (count < state.cpSectorCount) state.cpSectorCount = count
            return
        }

        val latest = snapshot.sectors.lastOrNull()
        state.cpSectorCount = count
        // FINISH appends one final sector result too. FINISH keeps its dedicated screen/animation.
        if (snapshot.state != "RUNNING" || latest == null) return

        val bestSplit = comparisonRuns
            .asSequence()
            .mapNotNull { run -> run.sectors.firstOrNull { it.index == latest.index }?.splitMs }
            .filter { it > 0L }
            .minOrNull()
        val bestDelta = bestSplit?.let { latest.splitMs - it }
        showCpPopup(activity, anchor, state, latest, bestDelta)
    }

    private fun showCpPopup(
        activity: RaceActivity,
        anchor: View,
        state: State,
        sector: RaceSectorResult,
        bestDeltaMs: Long?
    ) {
        dismissCpPopup(state)

        val fast = bestDeltaMs != null && bestDeltaMs < -50L
        val slow = bestDeltaMs != null && bestDeltaMs > 50L
        val accent = when {
            fast -> Color.rgb(43, 225, 140)
            slow -> Color.rgb(255, 98, 109)
            else -> Color.rgb(45, 156, 255)
        }
        val gapText = when {
            bestDeltaMs == null -> "첫 구간 기록"
            abs(bestDeltaMs) < 50L -> "BEST 대비 ±0.0초"
            bestDeltaMs < 0L -> "BEST 대비 −%.1f초".format(abs(bestDeltaMs) / 1000.0)
            else -> "BEST 대비 +%.1f초".format(bestDeltaMs / 1000.0)
        }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(activity, 26), dp(activity, 22), dp(activity, 26), dp(activity, 22))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(activity, 22).toFloat()
                setColor(Color.rgb(7, 17, 29))
                setStroke(dp(activity, 3), accent)
            }
            alpha = 0f
            scaleX = 0.86f
            scaleY = 0.86f
        }
        card.addView(TextView(activity).apply {
            text = "${sector.name.ifBlank { "CP${sector.index}" }} 통과!"
            textSize = 32f
            setTextColor(accent)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
        })
        card.addView(TextView(activity).apply {
            text = formatTime(sector.splitMs)
            textSize = 52f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(0, dp(activity, 9), 0, 0)
        })
        card.addView(TextView(activity).apply {
            text = gapText
            textSize = 20f
            setTextColor(accent)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(0, dp(activity, 8), 0, 0)
        })

        val width = (activity.resources.displayMetrics.widthPixels * 0.88f).toInt()
        val popup = PopupWindow(card, width, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isTouchable = false
            isOutsideTouchable = false
            isClippingEnabled = true
            elevation = dp(activity, 14).toFloat()
        }
        state.cpPopup = popup
        popup.showAtLocation(anchor, Gravity.CENTER, 0, 0)
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170L).start()

        val dismiss = Runnable {
            if (state.cpPopup !== popup) return@Runnable
            card.animate()
                .alpha(0f)
                .scaleX(0.94f)
                .scaleY(0.94f)
                .setDuration(160L)
                .withEndAction {
                    if (state.cpPopup === popup) {
                        runCatching { popup.dismiss() }
                        state.cpPopup = null
                        state.cpDismiss = null
                    }
                }
                .start()
        }
        state.cpDismiss = dismiss
        state.handler.postDelayed(dismiss, CP_POPUP_HOLD_MS)
    }

    private fun dismissCpPopup(state: State) {
        state.cpDismiss?.let(state.handler::removeCallbacks)
        state.cpDismiss = null
        runCatching { state.cpPopup?.dismiss() }
        state.cpPopup = null
    }

    private fun sameRoom(run: RaceRunSummary, eventCode: String, courseId: String): Boolean =
        if (eventCode == "PRACTICE") {
            run.eventCode.equals("PRACTICE", ignoreCase = true) && run.courseId == courseId
        } else {
            run.eventCode.equals(eventCode, ignoreCase = true)
        }

    private fun updateCourseBestPanel(
        activity: RaceActivity,
        root: ViewGroup,
        store: RaceDataStore,
        snapshot: RaceDataStore.Snapshot,
        eventCode: String,
        courseId: String
    ) {
        val panel = ensureCourseBestPanel(activity, root) ?: return
        panel.visibility = View.VISIBLE
        val label = panel.findViewWithTag<TextView>(TAG_COURSE_BEST_LABEL)
        val value = panel.findViewWithTag<TextView>(TAG_COURSE_BEST_VALUE) ?: return

        if (eventCode == "PRACTICE") {
            label?.text = "코스 최고 기록"
            val localBest = store.completed()
                .asSequence()
                .filter { it.eventCode.equals("PRACTICE", ignoreCase = true) }
                .filter { it.courseId == courseId && it.elapsedMs > 0L }
                .filter { it.status.equals("VALID", ignoreCase = true) }
                .minByOrNull { it.elapsedMs }
            value.text = localBest?.let { "내 기록  ${formatTime(it.elapsedMs)}" } ?: "기록 대기 중"
            return
        }

        // Joined-event HUD is strictly server-authoritative. Never merge a phone-local best here.
        label?.text = "경기방 최고 기록"
        RaceLiveLeaderStatus.refreshIfDue(activity, snapshot)
        val live = RaceLiveLeaderStatus.cached(eventCode)
        val leaderMs = live?.leaderElapsedMs ?: snapshot.leaderElapsedMs
        val leaderName = live?.leaderName?.takeIf { it.isNotBlank() } ?: snapshot.leaderName
        val leaderBib = live?.leaderBib.orEmpty().trim()
        val leaderNickname = live?.leaderNickname.orEmpty().trim()

        if (leaderMs == null) {
            value.text = "기록 대기 중"
            return
        }

        val identity = buildList {
            if (leaderBib.isNotBlank()) add(leaderBib)
            if (leaderName.isNotBlank()) add(leaderName.trim())
            if (leaderNickname.isNotBlank() && !leaderNickname.equals(leaderName.trim(), ignoreCase = true)) add(leaderNickname)
            add(formatTime(leaderMs))
        }
        value.text = identity.joinToString("   ")
    }

    private fun ensureCourseBestPanel(activity: RaceActivity, root: ViewGroup): LinearLayout? {
        root.findViewWithTag<View>(TAG_OLD_LEADER_ROW)?.let { old ->
            (old.parent as? ViewGroup)?.removeView(old)
        }

        root.findViewWithTag<TextView>(TAG_COURSE_BEST_VALUE)?.let { existing ->
            return existing.parent as? LinearLayout
        }

        val oldLabel = findText(root, "DELTA · PREVIOUS")
            ?: findText(root, "코스 최고 기록")
            ?: findText(root, "경기방 최고 기록")
            ?: return null
        val panel = oldLabel.parent as? LinearLayout ?: return null
        panel.removeAllViews()
        panel.orientation = LinearLayout.VERTICAL
        panel.gravity = Gravity.CENTER
        panel.setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 8))
        panel.setBackgroundColor(Color.rgb(38, 38, 38))

        panel.addView(TextView(activity).apply {
            tag = TAG_COURSE_BEST_LABEL
            text = "코스 최고 기록"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }, LinearLayout.LayoutParams(-1, dp(activity, 28)))

        panel.addView(TextView(activity).apply {
            tag = TAG_COURSE_BEST_VALUE
            text = "기록 대기 중"
            textSize = 30f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = false
            isSingleLine = true
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        return panel
    }

    private fun installHistoryButton(activity: RaceActivity, root: ViewGroup, courseId: String, eventCode: String) {
        root.findViewWithTag<Button>(TAG_HISTORY)?.apply {
            setOnClickListener { openHistory(activity, courseId, eventCode) }
            return
        }
        val back = findButton(root) {
            val t = it.text?.toString()?.trim().orEmpty()
            t.contains("Live", ignoreCase = true) || t == "‹"
        } ?: return
        val row = back.parent as? LinearLayout ?: return
        val button = Button(activity).apply {
            tag = TAG_HISTORY
            text = "랩 기록"
            isAllCaps = false
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(70, 70, 70))
            setOnClickListener { openHistory(activity, courseId, eventCode) }
        }
        // Add after the weighted header so the control naturally sits at the far right.
        row.addView(button, row.childCount, LinearLayout.LayoutParams(dp(activity, 86), dp(activity, 42)).apply { marginEnd = dp(activity, 6) })
    }

    private fun openHistory(activity: RaceActivity, courseId: String, eventCode: String) {
        activity.startActivity(Intent(activity, RaceLapHistoryActivity::class.java).apply {
            putExtra(RaceLapHistoryActivity.EXTRA_COURSE_ID, courseId)
            putExtra(RaceLapHistoryActivity.EXTRA_EVENT_CODE, eventCode)
        })
    }

    private fun currentElapsed(s: RaceDataStore.Snapshot): Long? = when {
        s.state == "RUNNING" && s.startedAtMs > 0L -> if (s.startedElapsedNs > 0L) ((SystemClock.elapsedRealtimeNanos() - s.startedElapsedNs) / 1_000_000L).coerceAtLeast(0L) else s.elapsedMs
        s.state == "FINISHED" -> s.elapsedMs
        s.state == "ARMED" || s.state == "WATCHING" -> 0L
        else -> null
    }

    private data class Block(val lap: TextView, val time: TextView)

    private fun findBlock(root: View, label: String): Block? {
        val labelView = findText(root, label) ?: return null
        val block = labelView.parent as? LinearLayout ?: return null
        if (block.childCount < 2) return null
        val row = block.getChildAt(1) as? LinearLayout ?: return null
        val lap = row.getChildAt(0) as? TextView ?: return null
        val time = row.getChildAt(1) as? TextView ?: return null
        return Block(lap, time)
    }

    private fun setBlock(block: Block, lapNumber: Int?, elapsedMs: Long?) {
        block.lap.text = lapNumber?.toString() ?: "—"
        block.time.text = elapsedMs?.let(::formatTime) ?: "—"
    }

    private fun setBlockText(block: Block, lapNumber: Int?, value: String) {
        block.lap.text = lapNumber?.toString() ?: "—"
        block.time.text = value
    }

    private fun formatTime(ms: Long): String = formatRaceTime(ms)

    private fun findText(root: View, text: String): TextView? {
        if (root is TextView && root.text?.toString() == text) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findText(root.getChildAt(i), text)?.let { return it }
        }
        return null
    }

    private fun findButton(root: View, predicate: (Button) -> Boolean): Button? {
        if (root is Button && predicate(root)) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findButton(root.getChildAt(i), predicate)?.let { return it }
        }
        return null
    }

    private fun dp(activity: RaceActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
