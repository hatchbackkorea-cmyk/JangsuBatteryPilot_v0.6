package com.seungjae.jangsu280battery

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

object RideFormatter {
    fun one(v: Double): String = String.format(Locale.US, "%.1f", v)

    /**
     * v0.26.5: 주행시간에 계획된 대기/충전시간을 더한 실제 시각 ETA.
     * distance가 0이면 속도가 없어도 extraMinutes만으로 예상 출발시각을 계산할 수 있다.
     */
    fun etaClock(distanceKm: Double, speedKmh: Double, extraMinutes: Double = 0.0): String {
        val distance = distanceKm.coerceAtLeast(0.0)
        val extra = extraMinutes.coerceAtLeast(0.0)
        if (distance <= 0.05 && extra <= 0.4) return "도착"
        if (distance > 0.05 && speedKmh < 3.0) return "ETA 계산 중"
        val travelMillis = if (distance <= 0.05) 0L else (distance / speedKmh * 3_600_000.0).roundToLong()
        val extraMillis = (extra * 60_000.0).roundToLong()
        return SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(System.currentTimeMillis() + travelMillis + extraMillis))
    }

    fun duration(distanceKm: Double, speedKmh: Double): String {
        if (speedKmh < 3.0) return "-"
        val totalMin = (distanceKm / speedKmh * 60.0).roundToLong()
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "${h}시간 ${m}분" else "${m}분"
    }
}
