package com.seungjae.jangsu280battery

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar
import java.util.WeakHashMap

/** Keeps BEST / PREVIOUS / CURRENT lap numbers scoped to today's laps on the active course. */
object RaceLiveLapDisplayInstaller {
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

        val today = todaysUsableRuns(store.completed(), courseId)
        val currentRun = today.firstOrNull { it.runId == snapshot.runId }
        val prior = if (currentRun != null) today.takeWhile { it.runId != currentRun.runId } else today
        val best = prior.minByOrNull { it.elapsedMs }
        val previous = prior.lastOrNull()

        val currentLap = when {
            currentRun != null -> today.indexOfFirst { it.runId == currentRun.runId } + 1
            else -> today.size + 1
        }.coerceAtLeast(1)

        setBlock(bestBlock, best?.let { today.indexOfFirst { r -> r.runId == it.runId } + 1 }, best?.elapsedMs)
        setBlock(previousBlock, previous?.let { today.indexOfFirst { r -> r.runId == it.runId } + 1 }, previous?.elapsedMs)
        setBlock(currentBlock, currentLap, currentElapsed(snapshot))
    }

    private fun todaysUsableRuns(all: List<RaceRunSummary>, courseId: String): List<RaceRunSummary> {
        val (start, end) = todayBounds()
        return all.asSequence()
            .filter { it.courseId == courseId }
            .filter { it.status != "INVALID" }
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
}
