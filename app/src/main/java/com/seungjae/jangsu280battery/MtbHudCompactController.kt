package com.seungjae.jangsu280battery

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.WeakHashMap

/**
 * MTB HUD presentation controller.
 *
 * v0.33.7:
 * - CURRENT / ARRIVAL battery lines use exactly the same text size
 * - remove the redundant "다음 업힐" / "다음 지점" labels
 * - compact climb wording (10.8 km, 획고 203m)
 * - make finish/checkpoint typography match the climb card
 * - keep map debug/status text hidden from the riding HUD
 */
object MtbHudCompactController {
    private val installed = WeakHashMap<View, Controller>()

    fun install(root: View, context: Context) {
        if (installed.containsKey(root)) return
        Controller(root, context.applicationContext).also {
            installed[root] = it
            it.attach()
        }
    }

    private class Controller(
        private val root: View,
        private val context: Context
    ) : ViewTreeObserver.OnPreDrawListener {

        private val hero = root.findViewById<LinearLayout?>(R.id.layoutRideHero)
        private val mode = root.findViewById<TextView?>(R.id.tvAssistModeCurrent)
        private val modeBanner = root.findViewById<LinearLayout?>(R.id.layoutAssistModeBanner)
        private val battery = root.findViewById<TextView?>(R.id.tvBattery)
        private val batteryLabel = root.findViewById<TextView?>(R.id.tvBatteryLabel)
        private val manualBattery = root.findViewById<Button?>(R.id.btnManualBattery)
        private val reachMargins = root.findViewById<LinearLayout?>(R.id.layoutRideReachMargins)
        private val riskStatus = root.findViewById<TextView?>(R.id.tvRiskStatus)
        private val riskDetail = root.findViewById<TextView?>(R.id.tvRiskDetail)
        private val routeScale = root.findViewById<TextView?>(R.id.tvRideRouteScale)
        private val mapStatus = root.findViewById<TextView?>(R.id.tvRideMapPreviewStatus)
        private val nextCheckpoint = root.findViewById<TextView?>(R.id.tvNextCheckpoint)
        private val nextCheckpointDetail = root.findViewById<TextView?>(R.id.tvNextCheckpointDetail)
        private val nextClimb = root.findViewById<TextView?>(R.id.tvNextClimb)
        private val nextClimbDetail = root.findViewById<TextView?>(R.id.tvNextClimbDetail)

        private val topRow: ViewGroup? = hero?.getChildAt(0) as? ViewGroup
        private val neutralBackground: Drawable? = context.getDrawable(R.drawable.panel_bg)?.mutate()
        private val idleBackground: Drawable? = context.getDrawable(R.drawable.assist_mode_idle_bg)?.mutate()
        private val ecoBackground: Drawable? = context.getDrawable(R.drawable.assist_mode_eco_bg)?.mutate()
        private val autoBackground: Drawable? = context.getDrawable(R.drawable.assist_mode_auto_bg)?.mutate()
        private val trailBackground: Drawable? = context.getDrawable(R.drawable.assist_mode_trail_bg)?.mutate()
        private val turboBackground: Drawable? = context.getDrawable(R.drawable.assist_mode_turbo_bg)?.mutate()

        private var lastCurrentSoc: Int? = null
        private var lastRenderedSummary = ""
        private var navCardsSwapped = false

        fun attach() {
            batteryLabel?.visibility = View.GONE
            manualBattery?.visibility = View.GONE
            reachMargins?.visibility = View.GONE
            mapStatus?.visibility = View.GONE
            hideRiskSummaryRow()

            mode?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 42f)
            modeBanner?.layoutParams?.let { lp ->
                val h = dp(64f)
                if (lp.height != h) {
                    lp.height = h
                    modeBanner.layoutParams = lp
                }
            }

            configureNavigationCards()
            installSmoothMap()
            hero?.viewTreeObserver?.addOnPreDrawListener(this)
            applyPresentation()
        }

        private fun installSmoothMap() {
            val frame = root.findViewById<FrameLayout?>(R.id.layoutRideMapPreview) ?: return
            if (frame.findViewWithTag<View>(RideSmoothMapWebView.TAG_SMOOTH_MAP) != null) return

            frame.findViewWithTag<View>(RideLiveMapWebView.TAG_LIVE_MAP)?.let { frame.removeView(it) }
            frame.findViewWithTag<View>(RideSmartMapWebView.TAG_SMART_MAP)?.let { frame.removeView(it) }

            mapStatus?.visibility = View.GONE
            val insertAt = mapStatus?.let { frame.indexOfChild(it) }?.takeIf { it >= 0 } ?: frame.childCount
            frame.addView(
                RideSmoothMapWebView(root.context),
                insertAt,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        private fun configureNavigationCards() {
            if (!navCardsSwapped) {
                val cpCard = nextCheckpoint?.parent as? View
                val climbCard = nextClimb?.parent as? View
                val row = cpCard?.parent as? LinearLayout
                if (row != null && climbCard?.parent === row) {
                    val cpIndex = row.indexOfChild(cpCard)
                    val climbIndex = row.indexOfChild(climbCard)
                    if (cpIndex >= 0 && climbIndex >= 0 && climbIndex > cpIndex) {
                        row.removeView(climbCard)
                        row.removeView(cpCard)
                        row.addView(climbCard, cpIndex)
                        row.addView(cpCard, climbIndex)
                    }
                    (climbCard.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                        lp.marginStart = 0
                        climbCard.layoutParams = lp
                    }
                    (cpCard.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                        lp.marginStart = dp(6f)
                        cpCard.layoutParams = lp
                    }
                    navCardsSwapped = true
                }
            }

            // The climb header itself is redundant; the first information line is the distance.
            nextClimb?.visibility = View.GONE

            // Remove the static "다음 지점" label while keeping the dynamic destination name.
            (nextCheckpoint?.parent as? ViewGroup)?.let { card ->
                for (i in 0 until card.childCount) {
                    val child = card.getChildAt(i)
                    if (child is TextView && child.id == View.NO_ID && child.text?.toString()?.trim() == "다음 지점") {
                        child.visibility = View.GONE
                    }
                }
            }

            nextClimbDetail?.apply {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 26f)
                maxLines = 4
                includeFontPadding = false
                setLineSpacing(0f, 0.92f)
            }
            nextCheckpoint?.apply {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 26f)
                includeFontPadding = false
                maxLines = 1
            }
            nextCheckpointDetail?.apply {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 26f)
                includeFontPadding = false
                maxLines = 4
                setLineSpacing(0f, 0.92f)
            }

            // v0.33.6 used 178dp. Keep the large type but reclaim some vertical room.
            val climbCard = nextClimb?.parent as? View
            val cpCard = nextCheckpoint?.parent as? View
            climbCard?.minimumHeight = dp(160f)
            cpCard?.minimumHeight = dp(160f)
        }

        private fun hideRiskSummaryRow() {
            val inner = riskStatus?.parent as? View
            val card = inner?.parent as? View
            val row = card?.parent as? View
            row?.visibility = View.GONE
        }

        override fun onPreDraw(): Boolean {
            applyPresentation()
            return true
        }

        private fun applyPresentation() {
            hero?.let { h ->
                if (neutralBackground != null && h.background !== neutralBackground) h.background = neutralBackground
            }

            val modeKey = mode?.text?.toString()?.trim()?.uppercase().orEmpty()
            val desired = when {
                modeKey.contains("ECO") -> ecoBackground
                modeKey.contains("AUTO") -> autoBackground
                modeKey.contains("TRAIL") -> trailBackground
                modeKey.contains("TURBO") -> turboBackground
                else -> idleBackground
            }
            topRow?.let { row ->
                if (desired != null && row.background !== desired) row.background = desired
                row.setPadding(dp(6f), dp(3f), dp(6f), dp(3f))
            }

            mode?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 42f)
            routeScale?.setTextColor(context.getColor(R.color.text_secondary))
            batteryLabel?.visibility = View.GONE
            manualBattery?.visibility = View.GONE
            reachMargins?.visibility = View.GONE
            mapStatus?.visibility = View.GONE
            hideRiskSummaryRow()
            configureNavigationCards()
            normalizeClimbDetail()
            normalizeCheckpointDetail()
            renderCombinedBattery()
        }

        private fun normalizeClimbDetail() {
            val view = nextClimbDetail ?: return
            var text = view.text?.toString().orEmpty()
            if (text.isBlank()) return

            // "10.8 km 후" -> "10.8 km"
            text = CLIMB_DISTANCE_AFTER_REGEX.replace(text) { m -> "${m.groupValues[1]} km" }

            // "평균 6.0% · +203m" -> two compact lines.
            text = CLIMB_GAIN_REGEX.replace(text) { m ->
                "평균 ${m.groupValues[1]}%\n획고 ${m.groupValues[2]}m"
            }

            // Already-normalized v0.33.6 text: "획고 203미터" -> "획고 203m".
            text = CLIMB_GAIN_KOREAN_UNIT_REGEX.replace(text) { m -> "획고 ${m.groupValues[1]}m" }

            if (view.text?.toString() != text) view.text = text
        }

        private fun normalizeCheckpointDetail() {
            val title = nextCheckpoint?.text?.toString().orEmpty().trim()
            val view = nextCheckpointDetail ?: return
            val raw = view.text?.toString().orEmpty()
            if (raw.isBlank() || !title.contains("종점")) return

            val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
            val distance = lines.firstOrNull { line ->
                line.contains("남음") && (line.contains("km", ignoreCase = true) || line.contains("킬로미터"))
            }?.replace("킬로미터", "km")
            val arrival = lines.firstOrNull { it.contains("예상") && it.contains('%') }
                ?.replace("종점 도착 예상", "도착 예상")
                ?.replace("종점 예상", "도착 예상")

            val compact = listOfNotNull(distance, arrival).joinToString("\n")
            if (compact.isNotBlank() && compact != raw) view.text = compact
        }

        private fun renderCombinedBattery() {
            val batteryView = battery ?: return
            val raw = batteryView.text?.toString().orEmpty()
            val percents = PERCENT_REGEX.findAll(raw)
                .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                .toList()

            if (percents.isNotEmpty()) {
                lastCurrentSoc = if (raw.trimStart().startsWith("현재") && percents.size >= 2) {
                    percents.first().coerceIn(0, 100)
                } else {
                    percents.last().coerceIn(0, 100)
                }
            }

            val arrival = ARRIVAL_REGEX.find(riskDetail?.text?.toString().orEmpty())
                ?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100)

            val currentText = lastCurrentSoc?.let { "$it%" } ?: "—"
            val arrivalText = arrival?.let { "$it%" } ?: "--"
            val summary = "현재 $currentText\n도착 $arrivalText"
            if (summary == lastRenderedSummary && batteryView.text.toString() == summary) return

            // Both floors deliberately use the exact same point size.
            batteryView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
            batteryView.maxLines = 2
            batteryView.gravity = Gravity.END
            batteryView.includeFontPadding = false
            batteryView.setLineSpacing(0f, 0.94f)
            batteryView.text = summary
            lastRenderedSummary = summary
        }

        private fun dp(value: Float): Int = (value * context.resources.displayMetrics.density).toInt()
    }

    private val PERCENT_REGEX = Regex("(\\d{1,3})%")
    private val ARRIVAL_REGEX = Regex("예상\\s*(\\d{1,3})%")
    private val CLIMB_DISTANCE_AFTER_REGEX = Regex("([0-9.]+)\\s*km\\s*후", RegexOption.IGNORE_CASE)
    private val CLIMB_GAIN_REGEX = Regex("평균\\s*([0-9.]+)%\\s*·\\s*\\+?([0-9]+)m")
    private val CLIMB_GAIN_KOREAN_UNIT_REGEX = Regex("획고\\s*([0-9]+)미터")
}
