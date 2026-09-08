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
import java.util.Calendar
import java.util.WeakHashMap

/**
 * Keeps the live RACE screen limited to three timing blocks while making their lap number meaningful.
 * BEST/PREVIOUS/CURRENT are scoped to today's completed laps on the active local course.
 */
object RaceLiveLapDisplayInstaller {
    private const val TAG_HISTORY = "timegate_lap_history_v03443"
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
        val bestBlock = findBlock(root, "BEST") ?: return
        val previousBlock = findBlock(root, "PREVIOUS") ?: return
        val currentBlock = findBlock(root, "CURRENT") ?: return

        val store = RaceDataStore(activity)
        val snapshot = store.snapshot()
        val courseId = snapshot.courseId.ifBlank { store.activeConfig()?.second.orEmpty() }
        if (courseId.isBlank()) return

        installHistoryButton(activity, root, courseId)

        val today = todaysRuns(store.completed(), courseId)
        val usable = today.filter { it.status != "INVALID" }
        val currentFinishedIndex = today.indexOfFirst { it.runId == snapshot.runId }

        // BEST = fastest usable finished lap today on this exact course.
        val best = usable.minByOrNull { it.elapsedMs }
        val bestLapNo = best?.let { target -> today.indexOfFirst { it.runId == target.runId } + 1 }?.takeIf { it > 0 }

        // PREVIOUS = literally the immediately preceding finished lap in today's course session.
        val previous = when {
            currentFinishedIndex > 0 -> today[currentFinishedIndex - 1]
            currentFinishedIndex == 0 -> null
            else -> today.lastOrNull()
        }
        val previousLapNo = previous?.let { target -> today.indexOfFirst { it.runId == target.runId } + 1 }?.takeIf { it > 0 }

        // CURRENT = today's next lap number, or the just-finished lap while FINISH is still displayed.
        val currentLapNo = when {
            currentFinishedIndex >= 0 -> currentFinishedIndex + 1
            else -> today.size + 1
        }.coerceAtLeast(1)

        setBlock(bestBlock, bestLapNo, best?.elapsedMs)
        setBlock(previousBlock, previousLapNo, previous?.elapsedMs)
        setBlock(currentBlock, currentLapNo, currentElapsed(snapshot))
    }

    private fun installHistoryButton(activity: RaceActivity, root: ViewGroup, courseId: String) {
        root.findViewWithTag<Button>(TAG_HISTORY)?.apply {
            setOnClickListener { openHistory(activity, courseId) }
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
            setOnClickListener { openHistory(activity, courseId) }
        }
        row.addView(button, 1.coerceAtMost(row.childCount), LinearLayout.LayoutParams(dp(activity, 78), dp(activity, 42)).apply { marginEnd = dp(activity, 6) })
    }

    private fun openHistory(activity: RaceActivity, courseId: String) {
        activity.startActivity(Intent(activity, RaceLapHistoryActivity::class.java).apply {
            putExtra(RaceLapHistoryActivity.EXTRA_COURSE_ID, courseId)
        })
    }

    private fun todaysRuns(all: List<RaceRunSummary>, courseId: String): List<RaceRunSummary> {
        val (start, end) = todayBounds()
        return all.asSequence()
            .filter { it.courseId == courseId }
            .filter { it.finishedAtMs in start until end }
            .sortedBy { it.finishedAtMs }
            .toList()
    }

    private fun todayBounds(now: Long = System.currentTimeMillis()): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, 1)
        return start to cal.timeInMillis
    }

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
