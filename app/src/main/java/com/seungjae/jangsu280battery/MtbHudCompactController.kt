package com.seungjae.jangsu280battery

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
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
 * v0.33.6:
 * - battery summary becomes a two-line CURRENT / ARRIVAL block
 * - map debug/status strip is hidden from the normal riding HUD
 * - next climb card moves to the left and next point card to the right
 * - climb text is enlarged and gained elevation is shown on its own fourth line
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

            // Debug/status text is intentionally gone in the riding UI.
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

            // 19sp x 1.5 = 28.5sp, detail 13sp x 2 = 26sp.
            nextClimb?.apply {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 28.5f)
                includeFontPadding = false
            }
            nextClimbDetail?.apply {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 26f)
                maxLines = 4
                includeFontPadding = false
                setLineSpacing(0f, 0.94f)
            }

            // Give the enlarged 4-line climb block enough room; keep both cards aligned.
            val climbCard = nextClimb?.parent as? View
            val cpCard = nextCheckpoint?.parent as? View
            climbCard?.minimumHeight = dp(178f)
            cpCard?.minimumHeight = dp(178f)
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
            renderCombinedBattery()
        }

        private fun normalizeClimbDetail() {
            val view = nextClimbDetail ?: return
            val raw = view.text?.toString().orEmpty()
            if (raw.isBlank()) return

            // MainActivity currently renders line 3 as "평균 6.0% · +203m".
            // Split the gained elevation onto the requested fourth line.
            val match = CLIMB_GAIN_REGEX.find(raw)
            if (match != null) {
                val grade = match.groupValues[1]
                val gain = match.groupValues[2]
                val replacement = "평균 ${grade}%\n획고 ${gain}미터"
                val rewritten = raw.replaceRange(match.range, replacement)
                if (rewritten != raw) view.text = rewritten
            }
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

            val span = SpannableString(summary)
            val lineBreak = summary.indexOf('\n')
            if (lineBreak > 0) {
                span.setSpan(RelativeSizeSpan(1.08f), 0, lineBreak, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                span.setSpan(RelativeSizeSpan(0.92f), lineBreak + 1, summary.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            batteryView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 19f)
            batteryView.maxLines = 2
            batteryView.gravity = Gravity.END
            batteryView.includeFontPadding = false
            batteryView.setLineSpacing(0f, 0.96f)
            batteryView.text = span
            lastRenderedSummary = summary
        }

        private fun dp(value: Float): Int = (value * context.resources.displayMetrics.density).toInt()
    }

    private val PERCENT_REGEX = Regex("(\\d{1,3})%")
    private val ARRIVAL_REGEX = Regex("예상\\s*(\\d{1,3})%")
    private val CLIMB_GAIN_REGEX = Regex("평균\\s*([0-9.]+)%\\s*·\\s*\\+?([0-9]+)m")
}
