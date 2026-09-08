package com.seungjae.jangsu280battery

import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * Keeps the live RACE screen limited to BEST/PREVIOUS/CURRENT while adding a compact event
 * leader/rank strip. Full lap history belongs on RaceLapHistoryActivity.
 */
object RaceLiveLapDisplayInstaller {
    private const val TAG_HISTORY = "timegate_lap_history_v03444"
    private const val TAG_LEADER_ROW = "timegate_live_leader_row_v03444"
    private const val TAG_LEADER = "timegate_live_leader_v03444"
    private const val TAG_LEADER_GAP = "timegate_live_leader_gap_v03444"
    private const val TAG_RANK = "timegate_live_rank_v03444"
    private data class State(val handler: Handler, val runnable: Runnable)
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
        states.remove(activity)?.let { it.handler.removeCallbacks(it.runnable) }
    }

    private fun update(activity: RaceActivity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val store = RaceDataStore(activity)
        val snapshot = store.snapshot()
        val active = store.activeConfig()
        val courseId = snapshot.courseId.ifBlank { active?.second.orEmpty() }
        if (courseId.isBlank()) return
        val eventCode = snapshot.eventCode.ifBlank { active?.first?.eventCode.orEmpty() }.ifBlank { "PRACTICE" }.uppercase()

        updateLeaderStrip(activity, root, snapshot, eventCode)

        val sessionStore = RaceLapSessionStore(activity)
        if (snapshot.state == "RUNNING" && snapshot.startedAtMs > 0L) {
            sessionStore.beginOrResume(eventCode, courseId, snapshot.startedAtMs)
        } else if (snapshot.state == "FINISHED" && snapshot.startedAtMs > 0L) {
            sessionStore.beginOrResume(eventCode, courseId, snapshot.startedAtMs)
        }
        val session = sessionStore.matching(eventCode, courseId) ?: return

        val bestBlock = findBlock(root, "BEST") ?: return
        val previousBlock = findBlock(root, "PREVIOUS") ?: return
        val currentBlock = findBlock(root, "CURRENT") ?: return

        installHistoryButton(activity, root, courseId, eventCode)

        val sessionRuns = sessionRuns(store.completed(), eventCode, courseId, session.startedAtMs)
        val usable = sessionRuns.filter { it.status != "INVALID" }
        val currentFinishedIndex = sessionRuns.indexOfFirst { it.runId == snapshot.runId }

        val best = usable.minByOrNull { it.elapsedMs }
        val bestLapNo = best?.let { target -> sessionRuns.indexOfFirst { it.runId == target.runId } + 1 }?.takeIf { it > 0 }

        val previous = when {
            currentFinishedIndex > 0 -> sessionRuns[currentFinishedIndex - 1]
            currentFinishedIndex == 0 -> null
            else -> sessionRuns.lastOrNull()
        }
        val previousLapNo = previous?.let { target -> sessionRuns.indexOfFirst { it.runId == target.runId } + 1 }?.takeIf { it > 0 }

        val currentLapNo = when {
            currentFinishedIndex >= 0 -> currentFinishedIndex + 1
            else -> sessionRuns.size + 1
        }.coerceAtLeast(1)

        setBlock(bestBlock, bestLapNo, best?.elapsedMs)
        setBlock(previousBlock, previousLapNo, previous?.elapsedMs)
        setBlock(currentBlock, currentLapNo, currentElapsed(snapshot))
    }

    private fun updateLeaderStrip(activity: RaceActivity, root: ViewGroup, snapshot: RaceDataStore.Snapshot, eventCode: String) {
        val row = ensureLeaderStrip(activity, root) ?: return
        if (eventCode == "PRACTICE") {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        RaceLiveLeaderStatus.refreshIfDue(activity, snapshot)
        val live = RaceLiveLeaderStatus.cached(eventCode)
        val durable = snapshot.takeIf { it.leaderElapsedMs != null || it.estimatedRank != null }

        val leaderName = live?.leaderName?.takeIf { it.isNotBlank() } ?: durable?.leaderName.orEmpty()
        val leaderMs = live?.leaderElapsedMs ?: durable?.leaderElapsedMs
        val gapMs = live?.leaderDeltaMs ?: durable?.leaderDeltaMs
        val rank = live?.estimatedRank ?: durable?.estimatedRank
        val ranked = live?.rankedCount ?: durable?.rankedCount ?: 0
        val participants = live?.participantCount ?: durable?.participantCount ?: 0

        val leaderView = row.findViewWithTag<TextView>(TAG_LEADER)
        val gapView = row.findViewWithTag<TextView>(TAG_LEADER_GAP)
        val rankView = row.findViewWithTag<TextView>(TAG_RANK)

        leaderView?.text = if (leaderMs == null) {
            "LEADER  —"
        } else {
            buildString {
                append("LEADER  ").append(formatTime(leaderMs))
                if (leaderName.isNotBlank()) append(" · ").append(leaderName)
            }
        }

        if (gapMs == null) {
            gapView?.text = "GAP  —"
            gapView?.setTextColor(Color.LTGRAY)
        } else when {
            gapMs < 0L -> {
                gapView?.text = "GAP  +%.1f".format(abs(gapMs) / 1000.0)
                gapView?.setTextColor(Color.rgb(70, 150, 255))
            }
            gapMs > 0L -> {
                gapView?.text = "GAP  −%.1f".format(abs(gapMs) / 1000.0)
                gapView?.setTextColor(Color.rgb(255, 65, 75))
            }
            else -> {
                gapView?.text = "GAP  0.0"
                gapView?.setTextColor(Color.WHITE)
            }
        }

        rankView?.text = buildString {
            append(if (rank != null) "P$rank" else "P—")
            when {
                participants > 0 -> append(" · 참가 ").append(participants)
                ranked > 0 -> append(" / ").append(ranked)
            }
        }
    }

    private fun ensureLeaderStrip(activity: RaceActivity, root: ViewGroup): LinearLayout? {
        root.findViewWithTag<LinearLayout>(TAG_LEADER_ROW)?.let { return it }
        val deltaLabel = findText(root, "DELTA · PREVIOUS") ?: return null
        val deltaPanel = deltaLabel.parent as? LinearLayout ?: return null
        val parent = deltaPanel.parent as? LinearLayout ?: return null
        val row = LinearLayout(activity).apply {
            tag = TAG_LEADER_ROW
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 9), 0, dp(activity, 9), 0)
            setBackgroundColor(Color.rgb(28, 28, 28))
        }
        row.addView(TextView(activity).apply {
            tag = TAG_LEADER
            text = "LEADER  —"
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
        }, LinearLayout.LayoutParams(0, -1, 1.45f))
        row.addView(TextView(activity).apply {
            tag = TAG_LEADER_GAP
            text = "GAP  —"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, -1, 0.75f))
        row.addView(TextView(activity).apply {
            tag = TAG_RANK
            text = "P—"
            textSize = 11f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }, LinearLayout.LayoutParams(0, -1, 0.8f))
        val index = (parent.indexOfChild(deltaPanel) + 1).coerceAtMost(parent.childCount)
        parent.addView(row, index, LinearLayout.LayoutParams(-1, dp(activity, 42)))
        return row
    }

    private fun installHistoryButton(activity: RaceActivity, root: ViewGroup, courseId: String, eventCode: String) {
        root.findViewWithTag<Button>(TAG_HISTORY)?.apply {
            setOnClickListener { openHistory(activity, courseId, eventCode) }
            return
        }
        val back = findButton(root) { it.text?.toString()?.contains("Live", ignoreCase = true) == true } ?: return
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
        row.addView(button, 1.coerceAtMost(row.childCount), LinearLayout.LayoutParams(dp(activity, 78), dp(activity, 42)).apply { marginEnd = dp(activity, 6) })
    }

    private fun openHistory(activity: RaceActivity, courseId: String, eventCode: String) {
        activity.startActivity(Intent(activity, RaceLapHistoryActivity::class.java).apply {
            putExtra(RaceLapHistoryActivity.EXTRA_COURSE_ID, courseId)
            putExtra(RaceLapHistoryActivity.EXTRA_EVENT_CODE, eventCode)
        })
    }

    private fun sessionRuns(all: List<RaceRunSummary>, eventCode: String, courseId: String, sessionStartMs: Long): List<RaceRunSummary> =
        all.asSequence()
            .filter { it.eventCode.equals(eventCode, ignoreCase = true) }
            .filter { it.courseId == courseId }
            .filter { it.finishedAtMs >= sessionStartMs }
            .sortedBy { it.finishedAtMs }
            .toList()

    private fun currentElapsed(s: RaceDataStore.Snapshot): Long? = when {
        s.state == "RUNNING" && s.startedAtMs > 0L -> (System.currentTimeMillis() - s.startedAtMs).coerceAtLeast(0L)
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

    private fun formatTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val minute = safe / 60_000
        val seconds = (safe % 60_000) / 1000
        val tenth = (safe % 1000) / 100
        return if (minute > 0) "%d:%02d.%d".format(minute, seconds, tenth) else "%d.%d".format(seconds, tenth)
    }

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
