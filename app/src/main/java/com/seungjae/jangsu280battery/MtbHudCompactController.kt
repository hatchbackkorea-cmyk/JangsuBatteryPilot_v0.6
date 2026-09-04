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
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.ViewFlipper
import java.util.WeakHashMap

/**
 * MTB HUD presentation controller.
 *
 * v0.33.8:
 * - keep only GPS Hz in the upper-right corner of the riding map
 * - hide MapLibre's compact info/attribution control and replace it with a tiny attribution label
 * - enlarge the rider arrow
 * - add an OSM / CyclOSM map selector in the lower-right corner
 * - remove the redundant mobile-release pager page; deployment remains in Admin Center
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
        private val pager = root.findViewById<ViewFlipper?>(R.id.pagerFlipper)
        private val pagerIndicator = root.findViewById<TextView?>(R.id.tvPagerIndicator)
        private val mobileReleaseButton = root.findViewById<View?>(R.id.btnPageMobileRelease)

        private val topRow: ViewGroup? = hero?.getChildAt(0) as? ViewGroup
        private val neutralBackground: Drawable? = context.getDrawable(R.drawable.panel_bg)?.mutate()
        private val idleBackground: Drawable? = context.getDrawable(R.drawable.assist_mode_idle_bg)?.mutate()
        private val ecoBackground: Drawable? = context.getDrawable(R.drawable.assist_mode_eco_bg)?.mutate()
        private val autoBackground: Drawable? = context.getDrawable(R.drawable.assist_mode_auto_bg)?.mutate()
        private val trailBackground: Drawable? = context.getDrawable(R.drawable.assist_mode_trail_bg)?.mutate()
        private val turboBackground: Drawable? = context.getDrawable(R.drawable.assist_mode_turbo_bg)?.mutate()
        private val mapPrefs = context.getSharedPreferences("mtb_map_ui", Context.MODE_PRIVATE)

        private var lastCurrentSoc: Int? = null
        private var lastRenderedSummary = ""
        private var navCardsSwapped = false
        private var mobileReleasePageRemoved = false
        private var lastPagerChild = 0
        private var mapPolishAttempts = 0
        private var attributionView: TextView? = null
        private var selectorView: TextView? = null

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
            removeMobileReleasePage()
            root.postDelayed({ removeMobileReleasePage() }, 350L)
            hero?.viewTreeObserver?.addOnPreDrawListener(this)
            applyPresentation()
        }

        private fun installSmoothMap() {
            val frame = root.findViewById<FrameLayout?>(R.id.layoutRideMapPreview) ?: return
            var smooth = frame.findViewWithTag<View>(RideSmoothMapWebView.TAG_SMOOTH_MAP) as? RideSmoothMapWebView
            if (smooth == null) {
                frame.findViewWithTag<View>(RideLiveMapWebView.TAG_LIVE_MAP)?.let { frame.removeView(it) }
                frame.findViewWithTag<View>(RideSmartMapWebView.TAG_SMART_MAP)?.let { frame.removeView(it) }

                mapStatus?.visibility = View.GONE
                val insertAt = mapStatus?.let { frame.indexOfChild(it) }?.takeIf { it >= 0 } ?: frame.childCount
                smooth = RideSmoothMapWebView(root.context)
                frame.addView(
                    smooth,
                    insertAt,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }

            installMapSelector(frame, smooth)
            scheduleMapPolish(smooth)
        }

        private fun installMapSelector(frame: FrameLayout, map: RideSmoothMapWebView) {
            if (selectorView == null) {
                val selector = TextView(root.context).apply {
                    text = "▦"
                    textSize = 20f
                    gravity = Gravity.CENTER
                    setTextColor(context.getColor(R.color.text_primary))
                    background = context.getDrawable(R.drawable.panel_bg)?.mutate()
                    elevation = dp(5f).toFloat()
                    contentDescription = "지도 종류 선택"
                    setOnClickListener { showMapSelector(this, map) }
                }
                frame.addView(
                    selector,
                    FrameLayout.LayoutParams(dp(42f), dp(42f), Gravity.END or Gravity.BOTTOM).apply {
                        marginEnd = dp(8f)
                        bottomMargin = dp(8f)
                    }
                )
                selectorView = selector
            }

            if (attributionView == null) {
                val attribution = TextView(root.context).apply {
                    textSize = 7f
                    setTextColor(context.getColor(R.color.text_secondary))
                    setPadding(dp(3f), dp(1f), dp(3f), dp(1f))
                    background = context.getDrawable(R.drawable.panel_bg)?.mutate()
                    alpha = 0.72f
                }
                frame.addView(
                    attribution,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.START or Gravity.BOTTOM
                    ).apply {
                        marginStart = dp(4f)
                        bottomMargin = dp(4f)
                    }
                )
                attributionView = attribution
            }
            updateAttribution(currentMapStyle())
        }

        private fun showMapSelector(anchor: View, map: RideSmoothMapWebView) {
            PopupMenu(root.context, anchor).apply {
                menu.add(0, MAP_OSM, 0, "기본 지도 · OSM")
                menu.add(0, MAP_CYCLOSM, 1, "자전거 지도 · CyclOSM")
                setOnMenuItemClickListener { item ->
                    val style = when (item.itemId) {
                        MAP_CYCLOSM -> "cyclosm"
                        else -> "osm"
                    }
                    mapPrefs.edit().putString(KEY_MAP_STYLE, style).apply()
                    applyMapStyle(map, style)
                    updateAttribution(style)
                    true
                }
                show()
            }
        }

        private fun currentMapStyle(): String =
            mapPrefs.getString(KEY_MAP_STYLE, "osm")?.takeIf { it == "cyclosm" } ?: "osm"

        private fun updateAttribution(style: String) {
            attributionView?.text = if (style == "cyclosm") {
                "© OpenStreetMap · CyclOSM"
            } else {
                "© OpenStreetMap"
            }
        }

        private fun applyMapStyle(map: RideSmoothMapWebView, style: String) {
            val tile = if (style == "cyclosm") {
                "https://a.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png"
            } else {
                "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
            }
            val js = """
                (function(){
                  try {
                    if (typeof map !== 'undefined' && map) {
                      var src = map.getSource('osm');
                      if (src && typeof src.setTiles === 'function') src.setTiles(['$tile']);
                    }
                  } catch(e) {}
                })();
            """.trimIndent()
            runCatching { map.evaluateJavascript(js, null) }
        }

        private fun scheduleMapPolish(map: RideSmoothMapWebView) {
            mapPolishAttempts = 0
            fun attempt() {
                if (!map.isAttachedToWindow || mapPolishAttempts >= 12) return
                mapPolishAttempts++
                val js = """
                    (function(){
                      try {
                        var style = document.getElementById('rc-clean-style');
                        if (!style) {
                          style = document.createElement('style');
                          style.id='rc-clean-style';
                          style.textContent='.maplibregl-ctrl-bottom-right,.maplibregl-ctrl-attrib{display:none!important}.rider-triangle{width:45px!important;height:51px!important}.nav-chip{display:none!important}#fps{right:8px!important;top:8px!important;font-size:11px!important;padding:5px 9px!important}';
                          document.head.appendChild(style);
                        }
                        var chip=document.getElementById('chip'); if(chip) chip.style.display='none';
                        var fps=document.getElementById('fps');
                        if(fps){
                          var old=fps.textContent||'';
                          var m=old.match(/GPS\\s+([0-9.]+Hz|대기)/i);
                          fps.textContent=m?'GPS '+m[1]:'GPS 대기';
                        }
                        window.rcSetGpsRate=function(actual){
                          var t=(actual>0)?actual.toFixed(1)+'Hz':'대기';
                          var f=document.getElementById('fps'); if(f)f.textContent='GPS '+t;
                        };
                        return true;
                      } catch(e) { return false; }
                    })();
                """.trimIndent()
                runCatching {
                    map.evaluateJavascript(js) { raw ->
                        if (raw != "true") root.postDelayed({ attempt() }, 350L)
                        else applyMapStyle(map, currentMapStyle())
                    }
                }.onFailure { root.postDelayed({ attempt() }, 350L) }
            }
            root.postDelayed({ attempt() }, 250L)
        }

        private fun removeMobileReleasePage() {
            if (mobileReleasePageRemoved) return
            val flipper = pager ?: return
            val anchor = mobileReleaseButton ?: return
            var direct: View = anchor
            while (direct.parent is ViewGroup && direct.parent !== flipper) {
                direct = direct.parent as View
            }
            if (direct.parent === flipper) {
                flipper.removeView(direct)
                mobileReleasePageRemoved = true
                lastPagerChild = flipper.displayedChild.coerceIn(0, (flipper.childCount - 1).coerceAtLeast(0))
                updateCompactPagerIndicator()
            }
        }

        private fun updateCompactPagerIndicator() {
            val flipper = pager ?: return
            val indicator = pagerIndicator ?: return
            val count = flipper.childCount
            if (count <= 0) return
            val lastIndex = count - 1
            var current = flipper.displayedChild.coerceIn(0, lastIndex)

            // MainActivity v0.33.7 still has a legacy maximum index of 6. After removing the
            // deployment page there are six pages (0..5). Prevent a swipe past the last page
            // from wrapping to page 0.
            if (mobileReleasePageRemoved && lastPagerChild == lastIndex && current == 0) {
                flipper.displayedChild = lastIndex
                current = lastIndex
            }
            lastPagerChild = current

            val labels = arrayOf("주행", "코스", "설정", "학습", "피드백", "배터리")
            val dots = (0 until count).joinToString("  ") { if (it == current) "●" else "○" }
            val locked = indicator.text?.toString()?.contains("🔒") == true
            val prefix = if (locked) "🔒 터치잠금   " else ""
            val label = labels.getOrElse(current) { "" }
            indicator.text = "$prefix$dots   $label"
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

            nextClimb?.visibility = View.GONE

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
            updateCompactPagerIndicator()
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
            text = CLIMB_DISTANCE_AFTER_REGEX.replace(text) { m -> "${m.groupValues[1]} km" }
            text = CLIMB_GAIN_REGEX.replace(text) { m ->
                "평균 ${m.groupValues[1]}%\n획고 ${m.groupValues[2]}m"
            }
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

    private const val KEY_MAP_STYLE = "map_style"
    private const val MAP_OSM = 101
    private const val MAP_CYCLOSM = 102

    private val PERCENT_REGEX = Regex("(\\d{1,3})%")
    private val ARRIVAL_REGEX = Regex("예상\\s*(\\d{1,3})%")
    private val CLIMB_DISTANCE_AFTER_REGEX = Regex("([0-9.]+)\\s*km\\s*후", RegexOption.IGNORE_CASE)
    private val CLIMB_GAIN_REGEX = Regex("평균\\s*([0-9.]+)%\\s*·\\s*\\+?([0-9]+)m")
    private val CLIMB_GAIN_KOREAN_UNIT_REGEX = Regex("획고\\s*([0-9]+)미터")
}
