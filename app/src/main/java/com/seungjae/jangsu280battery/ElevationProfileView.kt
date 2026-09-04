package com.seungjae.jangsu280battery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ElevationProfileView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private var course: CourseData? = null
    private var currentKm: Double = 0.0
    private var compactMode: Boolean = false
    private var checkpointKmsOverride: List<Double>? = null

    // v0.27.4: ride HUD can zoom to the active charging segment while other pages keep full-course mode.
    private var windowStartKm: Double? = null
    private var windowEndKm: Double? = null
    // v0.27.5: blue = recommended reserve reach, red = hard-reserve absolute reach.
    private var recommendedReachKm: Double? = null
    private var hardReachKm: Double? = null

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        color = context.getColor(R.color.profile_line)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val gradePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3.2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.profile_fill)
    }
    private val currentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = context.getColor(R.color.accent)
    }
    private val currentDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.accent)
    }
    private val checkpointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = context.getColor(R.color.warn)
        alpha = 180
    }
    private val futurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.preview_band)
    }
    private val recommendedLimitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.4f)
        color = context.getColor(R.color.accent)
    }
    private val recommendedBeyondPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.accent)
        alpha = 16
    }
    private val dangerLimitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.8f)
        color = context.getColor(R.color.danger)
    }
    private val dangerBeyondPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.danger)
        alpha = 28
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_secondary)
        textSize = dp(10f)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // v0.32.9+ MTB HUD only: keep the existing order, but make the map the visual focus.
        // The original static Kakao ImageView remains below the live map as a fallback.
        if (id == R.id.rideMiniProfileView) {
            post { installMtbLiveMapLayout() }
        }
    }

    private fun installMtbLiveMapLayout() {
        if (!isAttachedToWindow || id != R.id.rideMiniProfileView) return

        layoutParams?.let { lp ->
            val target = dp(92f).toInt()
            if (lp.height != target) {
                lp.height = target
                layoutParams = lp
            }
        }

        val mapFrame = rootView.findViewById<FrameLayout?>(R.id.layoutRideMapPreview) ?: return
        mapFrame.layoutParams?.let { lp ->
            val target = dp(240f).toInt()
            if (lp.height != target) {
                lp.height = target
                mapFrame.layoutParams = lp
            }
        }

        if (mapFrame.findViewWithTag<View>(RideLiveMapWebView.TAG_LIVE_MAP) == null) {
            val liveMap = RideLiveMapWebView(context)
            val status = mapFrame.findViewById<View?>(R.id.tvRideMapPreviewStatus)
            val insertAt = status?.let { mapFrame.indexOfChild(it) }?.takeIf { it >= 0 } ?: mapFrame.childCount
            mapFrame.addView(
                liveMap,
                insertAt,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        MtbHudCompactController.install(rootView, context)
    }

    fun setCourse(value: CourseData) {
        course = value
        invalidate()
    }

    fun setCurrentKm(value: Double) {
        currentKm = value
        invalidate()
    }

    fun setCompactMode(value: Boolean) {
        compactMode = value
        invalidate()
    }

    fun setCheckpointKms(value: List<Double>?) {
        checkpointKmsOverride = value?.distinct()?.sorted()
        invalidate()
    }

    /** null/null means full-course view. */
    fun setWindow(startKm: Double?, endKm: Double?) {
        windowStartKm = startKm
        windowEndKm = endKm
        invalidate()
    }

    /**
     * Blue = normal/recommended reserve reach. Red = hard-reserve absolute reach.
     * Either may be null independently; the compact HUD normally keeps blue always on.
     */
    fun setReachLimits(recommendedKm: Double?, hardKm: Double?) {
        recommendedReachKm = recommendedKm
        hardReachKm = hardKm
        invalidate()
    }

    /** Backward-compatible helper used by older callers/tests. */
    fun setReachLimitKm(value: Double?) {
        hardReachKm = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val c = course ?: return
        if (c.track.size < 2 || width <= 0 || height <= 0 || c.totalKm <= 0.01) return

        val left = if (compactMode) dp(2f) else dp(8f)
        val right = width - if (compactMode) dp(2f) else dp(8f)
        val top = if (compactMode) dp(3f) else dp(10f)
        val bottom = height - if (compactMode) dp(3f) else dp(20f)
        val plotW = max(1f, right - left)
        val plotH = max(1f, bottom - top)

        var visibleStart = (windowStartKm ?: 0.0).coerceIn(0.0, c.totalKm)
        var visibleEnd = (windowEndKm ?: c.totalKm).coerceIn(0.0, c.totalKm)
        if (visibleEnd <= visibleStart + 0.10) {
            visibleStart = 0.0
            visibleEnd = c.totalKm
        }
        val visibleSpan = max(0.10, visibleEnd - visibleStart)
        fun x(km: Double): Float = left + ((km - visibleStart) / visibleSpan * plotW).toFloat()

        if (!c.hasElevation) {
            if (!compactMode) canvas.drawText("GPX 고도 데이터 없음 · 거리 기반 모드", left, height / 2f, textPaint)
            if (currentKm in visibleStart..visibleEnd) {
                val cx = x(currentKm)
                canvas.drawLine(cx, top, cx, bottom, currentPaint)
            }
            drawReachLimits(canvas, visibleStart, visibleEnd, left, right, top, bottom) { km -> x(km) }
            return
        }

        val visiblePoints = ArrayList<TrackPoint>()
        visiblePoints += c.pointAtKm(visibleStart)
        c.track.asSequence()
            .filter { it.routeKm > visibleStart + 0.001 && it.routeKm < visibleEnd - 0.001 }
            .forEach { visiblePoints += it }
        visiblePoints += c.pointAtKm(visibleEnd)

        var minEle = Double.MAX_VALUE
        var maxEle = -Double.MAX_VALUE
        visiblePoints.forEach {
            minEle = min(minEle, it.ele)
            maxEle = max(maxEle, it.ele)
        }
        val eleSpan = max(1.0, maxEle - minEle)
        fun y(ele: Double): Float = bottom - ((ele - minEle) / eleSpan * plotH).toFloat()

        // Keep a small "next 10 km" shade, clipped to the currently visible segment window.
        if (currentKm in visibleStart..visibleEnd) {
            val previewEnd = min(visibleEnd, currentKm + 10.0)
            if (previewEnd > currentKm + 0.01) {
                canvas.drawRect(x(currentKm), top, x(previewEnd), bottom, futurePaint)
            }
        }

        val sampled = ArrayList<TrackPoint>()
        val step = max(1, visiblePoints.size / 700)
        var i = 0
        while (i < visiblePoints.size) {
            sampled += visiblePoints[i]
            i += step
        }
        if (sampled.lastOrNull() !== visiblePoints.lastOrNull()) sampled += visiblePoints.last()

        val fillPath = Path()
        sampled.forEachIndexed { idx, p ->
            val px = x(p.routeKm)
            val py = y(p.ele)
            if (idx == 0) fillPath.moveTo(px, py) else fillPath.lineTo(px, py)
        }
        fillPath.lineTo(x(visibleEnd), bottom)
        fillPath.lineTo(left, bottom)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)

        // v0.33.0: colour each profile segment by cycling grade instead of one plain line.
        // Blue=descent, green=flat, yellow=gentle climb, orange=medium climb,
        // red=steep, purple=very steep.
        if (sampled.size >= 2) {
            for (j in 1 until sampled.size) {
                val a = sampled[j - 1]
                val b = sampled[j]
                val distanceM = (b.routeKm - a.routeKm) * 1000.0
                val gradePct = if (distanceM > 2.0) (b.ele - a.ele) / distanceM * 100.0 else 0.0
                gradePaint.color = gradeColor(gradePct)
                canvas.drawLine(x(a.routeKm), y(a.ele), x(b.routeKm), y(b.ele), gradePaint)
            }
        } else {
            canvas.drawPath(fillPath, linePaint)
        }

        val markerKms = buildList {
            addAll(checkpointKmsOverride ?: c.supplyPois.map { it.routeKm })
            add(c.totalKm)
        }.distinct().sorted()
        markerKms.forEach { km ->
            if (km >= visibleStart - 0.001 && km <= visibleEnd + 0.001) {
                val px = x(km)
                canvas.drawLine(px, top, px, bottom, checkpointPaint)
                if (!compactMode) {
                    val label = if (abs(km - c.totalKm) < 0.2) "FIN" else km.toInt().toString()
                    canvas.drawText(label, px + dp(2f), height - dp(5f), textPaint)
                }
            }
        }

        drawReachLimits(canvas, visibleStart, visibleEnd, left, right, top, bottom) { km -> x(km) }

        if (currentKm in visibleStart..visibleEnd) {
            val cx = x(currentKm)
            canvas.drawLine(cx, top, cx, bottom, currentPaint)
            canvas.drawCircle(cx, y(c.pointAtKm(currentKm).ele), dp(4f), currentDotPaint)
        }
    }

    private fun gradeColor(gradePct: Double): Int = when {
        gradePct < -3.0 -> Color.rgb(66, 165, 245)   // descent
        gradePct < 2.0 -> Color.rgb(76, 175, 80)     // flat / rolling
        gradePct < 5.0 -> Color.rgb(253, 216, 53)    // gentle climb
        gradePct < 8.0 -> Color.rgb(251, 140, 0)     // medium climb
        gradePct < 12.0 -> Color.rgb(229, 57, 53)    // steep climb
        else -> Color.rgb(142, 36, 170)               // very steep climb
    }

    private fun drawReachLimits(
        canvas: Canvas,
        visibleStart: Double,
        visibleEnd: Double,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        x: (Double) -> Float
    ) {
        val recommended = recommendedReachKm
        if (recommended != null && recommended >= visibleStart - 0.001 && recommended <= visibleEnd + 0.001) {
            val px = x(recommended).coerceIn(left, right)
            if (px < right - dp(1f)) canvas.drawRect(px, top, right, bottom, recommendedBeyondPaint)
            canvas.drawLine(px, top, px, bottom, recommendedLimitPaint)
        }

        val hard = hardReachKm
        if (hard != null && hard >= visibleStart - 0.001 && hard <= visibleEnd + 0.001) {
            val px = x(hard).coerceIn(left, right)
            if (px < right - dp(1f)) canvas.drawRect(px, top, right, bottom, dangerBeyondPaint)
            canvas.drawLine(px, top, px, bottom, dangerLimitPaint)
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
