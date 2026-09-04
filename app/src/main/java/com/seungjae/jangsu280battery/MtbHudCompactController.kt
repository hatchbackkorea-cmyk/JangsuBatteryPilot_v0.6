package com.seungjae.jangsu280battery

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.WeakHashMap

/**
 * v0.33.2 MTB HUD cleanup.
 *
 * The existing MainActivity still owns all battery / reserve calculations. This controller
 * only reorganises their presentation on the ride page so there is one compact information
 * strip above the profile and map, without duplicating the energy model.
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

        private val topRow: ViewGroup? = hero?.getChildAt(0) as? ViewGroup
        private val neutralBackground: Drawable? = context.getDrawable(R.drawable.panel_bg)?.mutate()
        private val idleBackground: Drawable? = context.getDrawable(R.drawable.assist_mode_idle_bg)?.mutate()
        private val ecoBackground: Drawable? = context.getDrawable(R.drawable.assist_mode_eco_bg)?.mutate()
        private val autoBackground: Drawable? = context.getDrawable(R.drawable.assist_mode_auto_bg)?.mutate()
        private val trailBackground: Drawable? = context.getDrawable(R.drawable.assist_mode_trail_bg)?.mutate()
        private val turboBackground: Drawable? = context.getDrawable(R.drawable.assist_mode_turbo_bg)?.mutate()

        private var lastCurrentSoc: Int? = null
        private var lastRenderedSummary = ""

        fun attach() {
            // These are now intentionally redundant: the top-right battery block contains
            // current SOC + destination/next-charge arrival SOC.
            batteryLabel?.visibility = View.GONE
            manualBattery?.visibility = View.GONE
            reachMargins?.visibility = View.GONE
            hideRiskSummaryRow()

            mode?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 42f)
            modeBanner?.layoutParams?.let { lp ->
                val h = dp(64f)
                if (lp.height != h) {
                    lp.height = h
                    modeBanner.layoutParams = lp
                }
            }

            // Keep profile/map sizing exactly as v0.33.1; only presentation changes here.
            hero?.viewTreeObserver?.addOnPreDrawListener(this)
            applyPresentation()
        }

        private fun hideRiskSummaryRow() {
            // tvRiskStatus -> inner row -> risk card -> outer row. The second card in this row
            // is already legacy/hidden, so removing the whole row leaves no blank spacer.
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
            // MainActivity historically colours the whole hero (profile + map included).
            // Override only that outer background and move the mode colour to the first/top row.
            hero?.let { h ->
                if (neutralBackground != null && h.background !== neutralBackground) {
                    h.background = neutralBackground
                }
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
            hideRiskSummaryRow()
            renderCombinedBattery()
        }

        private fun renderCombinedBattery() {
            val batteryView = battery ?: return

            // MainActivity writes the current SOC as e.g. "85%". After we replace it with the
            // combined string, the LAST percentage remains the current SOC, so the next pass can
            // still recover it until MainActivity supplies a newer value.
            val percents = PERCENT_REGEX.findAll(batteryView.text?.toString().orEmpty())
                .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                .toList()
            if (percents.isNotEmpty()) lastCurrentSoc = percents.last().coerceIn(0, 100)

            // MainActivity already selects the correct target: next planned charging CP when one
            // exists, otherwise the finish. Reuse its hidden reserve text instead of maintaining
            // a second forecast implementation here.
            val arrival = ARRIVAL_REGEX.find(riskDetail?.text?.toString().orEmpty())
                ?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100)

            val arrivalText = arrival?.let { "$it%" } ?: "--"
            val currentText = lastCurrentSoc?.let { "$it%" } ?: "—"
            val summary = "도착 $arrivalText / $currentText"
            if (summary == lastRenderedSummary && batteryView.text.toString() == summary) return

            val span = SpannableString(summary)
            val slash = summary.indexOf('/')
            if (slash > 0) {
                span.setSpan(RelativeSizeSpan(0.82f), 0, slash + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                span.setSpan(RelativeSizeSpan(1.28f), slash + 1, summary.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            batteryView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f)
            batteryView.maxLines = 1
            batteryView.text = span
            lastRenderedSummary = summary
        }

        private fun dp(value: Float): Int = (value * context.resources.displayMetrics.density).toInt()
    }

    private val PERCENT_REGEX = Regex("(\\d{1,3})%")
    private val ARRIVAL_REGEX = Regex("예상\\s*(\\d{1,3})%")
}
