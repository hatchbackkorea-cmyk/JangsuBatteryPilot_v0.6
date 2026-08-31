package com.seungjae.jangsu280battery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

object RoadPlanPdfExporter {
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 38f

    fun write(
        context: Context,
        uri: Uri,
        course: CourseData,
        plan: RoadPlan,
        startMinuteOfDay: Int,
        basisLabel: String
    ) {
        val doc = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 34, 40)
            typeface = Typeface.create("sans", Typeface.NORMAL)
        }
        val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 24, 30)
            typeface = Typeface.create("sans", Typeface.BOLD)
        }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(105, 120, 140)
            style = Paint.Style.STROKE
            strokeWidth = 1.4f
        }

        var pageNo = 0
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = 0f

        fun newPage(includeHeader: Boolean = true) {
            page?.let { doc.finishPage(it) }
            pageNo += 1
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            canvas = page!!.canvas
            canvas!!.drawColor(Color.WHITE)
            y = MARGIN
            if (includeHeader) {
                bold.textSize = 19f
                canvas!!.drawText("ROAD 그란폰도 목표 페이스 계획표", MARGIN, y + 18f, bold)
                y += 34f
            }
        }

        fun text(s: String, size: Float = 10.5f, strong: Boolean = false, gapAfter: Float = 5f) {
            val p = if (strong) bold else paint
            p.textSize = size
            val maxWidth = PAGE_W - MARGIN * 2
            val lines = wrapText(s, p, maxWidth)
            for (ln in lines) {
                if (y > PAGE_H - 55f) newPage()
                canvas!!.drawText(ln, MARGIN, y + size, p)
                y += size + 3.5f
            }
            y += gapAfter
        }

        newPage()
        text(course.name, 15f, true, 7f)
        text("거리 ${one(course.totalKm)} km  ·  획득고도 ${course.totalAscentM.toInt()} m  ·  하강 ${course.totalDescentM.toInt()} m", 10.5f)
        text("계획 기준: $basisLabel", 10.5f)
        text("출발 ${clock(startMinuteOfDay, 0.0)}  ·  순수 주행 ${duration(plan.ridingTargetSec)}  ·  보급 ${duration(plan.totalStopSec)}  ·  계획 완주 ${clock(startMinuteOfDay, plan.totalSec)}", 11.5f, true, 9f)
        text("주행 평속 ${one(course.totalKm / (plan.ridingTargetSec / 3600.0))} km/h  ·  정차 포함 전체평균 ${one(course.totalKm / (plan.totalSec / 3600.0))} km/h", 10.5f, false, 6f)
        if (plan.cutoffs.isNotEmpty()) {
            val controlling = plan.cutoffs.minByOrNull { it.marginSec }
            text("컷오프 ${plan.cutoffs.size}곳  ·  자동 계산 기준 ${controlling?.name ?: "-"} ${controlling?.let { one(it.km) } ?: "-"} km", 10.5f, true, 10f)
        } else {
            y += 4f
        }

        drawElevation(canvas!!, course, MARGIN, y, PAGE_W - MARGIN, y + 155f, line, paint, plan)
        y += 173f

        text("구간별 목표 일정", 13f, true, 5f)
        val headerY = y
        bold.textSize = 9.5f
        canvas!!.drawText("거리", MARGIN, headerY + 10f, bold)
        canvas!!.drawText("도착/통과", MARGIN + 70f, headerY + 10f, bold)
        canvas!!.drawText("출발", MARGIN + 165f, headerY + 10f, bold)
        canvas!!.drawText("구간", MARGIN + 255f, headerY + 10f, bold)
        y += 18f
        canvas!!.drawLine(MARGIN, y, PAGE_W - MARGIN, y, line)
        y += 5f

        plan.checkpoints.forEach { cp ->
            if (y > PAGE_H - 60f) {
                newPage()
                text("구간별 목표 일정 · 계속", 13f, true, 5f)
            }
            paint.textSize = 9.5f
            val arrival = clock(startMinuteOfDay, cp.targetElapsedSec)
            val departure = if (cp.stopSec > 0) clock(startMinuteOfDay, cp.targetElapsedSec + cp.stopSec) else "-"
            canvas!!.drawText(String.format(Locale.US, "%.1f km", cp.km), MARGIN, y + 11f, paint)
            canvas!!.drawText(arrival, MARGIN + 70f, y + 11f, paint)
            canvas!!.drawText(departure, MARGIN + 165f, y + 11f, paint)
            canvas!!.drawText(cp.name.take(25), MARGIN + 255f, y + 11f, paint)
            y += 18f
            canvas!!.drawLine(MARGIN, y, PAGE_W - MARGIN, y, line)
            y += 3f
        }

        if (plan.aidStops.isNotEmpty()) {
            if (y > PAGE_H - 160f) newPage()
            y += 7f
            text("선택한 보급소", 13f, true, 4f)
            plan.aidStops.forEach { aid ->
                text("${one(aid.km)} km  ${aid.name}  ·  ${clock(startMinuteOfDay, aid.arrivalElapsedSec)} 도착 → ${clock(startMinuteOfDay, aid.departureElapsedSec)} 출발  (${duration(aid.stopSec)} 정차)", 9.5f, false, 2f)
            }
        }

        if (plan.cutoffs.isNotEmpty()) {
            if (y > PAGE_H - 170f) newPage()
            y += 7f
            text("컷오프 기준", 13f, true, 4f)
            plan.cutoffs.forEach { cutoff ->
                val margin = if (cutoff.marginSec >= 0.0) "+${duration(cutoff.marginSec)}" else "-${duration(-cutoff.marginSec)}"
                text("${one(cutoff.km)} km  ${cutoff.name}  ·  계획 ${clock(startMinuteOfDay, cutoff.plannedArrivalElapsedSec)} / 컷오프 ${clock(startMinuteOfDay, cutoff.deadlineElapsedSec)}  ·  여유 $margin", 9.5f, false, 2f)
            }
        }

        page?.let { doc.finishPage(it) }
        context.contentResolver.openOutputStream(uri, "w")?.use { out -> doc.writeTo(out) }
            ?: error("PDF 저장 위치를 열 수 없습니다.")
        doc.close()
    }

    private fun drawElevation(
        canvas: Canvas,
        course: CourseData,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        line: Paint,
        textPaint: Paint,
        plan: RoadPlan
    ) {
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(245, 247, 250); style = Paint.Style.FILL }
        canvas.drawRoundRect(left, top, right, bottom, 10f, 10f, bg)
        if (course.track.size < 2 || !course.hasElevation) {
            textPaint.textSize = 10f
            textPaint.color = Color.DKGRAY
            canvas.drawText("고도 데이터 없음", left + 12f, top + 25f, textPaint)
            return
        }
        val minEle = course.track.minOf { it.ele }
        val maxEle = course.track.maxOf { it.ele }
        val span = max(1.0, maxEle - minEle)
        val path = Path()
        line.color = Color.rgb(55, 105, 165)
        line.strokeWidth = 1.8f
        course.track.forEachIndexed { i, p ->
            val x = left + 8f + (p.routeKm / max(0.001, course.totalKm) * (right - left - 16f)).toFloat()
            val yy = bottom - 21f - ((p.ele - minEle) / span * (bottom - top - 42f)).toFloat()
            if (i == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
        }
        canvas.drawPath(path, line)

        val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 120, 45); style = Paint.Style.FILL }
        plan.aidStops.forEach { aid ->
            val p = course.pointAtKm(aid.km)
            val x = left + 8f + (aid.km / max(0.001, course.totalKm) * (right - left - 16f)).toFloat()
            val yy = bottom - 21f - ((p.ele - minEle) / span * (bottom - top - 42f)).toFloat()
            canvas.drawCircle(x, yy, 3.8f, marker)
        }
        val cutoffMarker = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(190, 55, 55); style = Paint.Style.FILL }
        plan.cutoffs.forEach { cutoff ->
            val p = course.pointAtKm(cutoff.km)
            val x = left + 8f + (cutoff.km / max(0.001, course.totalKm) * (right - left - 16f)).toFloat()
            val yy = bottom - 21f - ((p.ele - minEle) / span * (bottom - top - 42f)).toFloat()
            canvas.drawCircle(x, yy, 4.5f, cutoffMarker)
        }

        textPaint.color = Color.DKGRAY
        textPaint.textSize = 9f
        canvas.drawText("고도 ${minEle.toInt()}–${maxEle.toInt()} m", left + 10f, top + 14f, textPaint)
        canvas.drawText("0 km", left + 8f, bottom - 6f, textPaint)
        val end = "${one(course.totalKm)} km"
        canvas.drawText(end, right - 8f - textPaint.measureText(end), bottom - 6f, textPaint)
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (paint.measureText(text) <= maxWidth) return listOf(text)
        val out = mutableListOf<String>()
        var remaining = text
        while (remaining.isNotEmpty()) {
            var count = paint.breakText(remaining, true, maxWidth, null).coerceAtLeast(1)
            if (count < remaining.length) {
                val cut = remaining.substring(0, count).lastIndexOf(' ')
                if (cut > 5) count = cut
            }
            out += remaining.substring(0, count).trim()
            remaining = remaining.substring(min(count, remaining.length)).trimStart()
        }
        return out
    }

    private fun clock(startMinuteOfDay: Int, elapsedSec: Double): String {
        val totalSec = (startMinuteOfDay * 60L + elapsedSec.toLong()).coerceAtLeast(0L)
        val daySec = ((totalSec % 86400L) + 86400L) % 86400L
        val h = daySec / 3600L
        val m = (daySec % 3600L) / 60L
        val s = daySec % 60L
        return if (s == 0L) String.format(Locale.US, "%02d:%02d", h, m)
        else String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    private fun duration(secRaw: Double): String {
        val sec = secRaw.toLong().coerceAtLeast(0)
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (s == 0L) String.format(Locale.US, "%d:%02d", h, m)
        else String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    }

    private fun one(v: Double) = String.format(Locale.US, "%.1f", v)
}
