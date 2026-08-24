package com.seungjae.jangsu280battery

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

object RideFormatter {
    fun one(v: Double): String = String.format(Locale.US, "%.1f", v)

    fun etaClock(distanceKm: Double, speedKmh: Double): String {
        if (distanceKm <= 0.05) return "도착"
        if (speedKmh < 3.0) return "ETA 계산 중"
        val hours = distanceKm / speedKmh
        val millis = (hours * 3_600_000.0).roundToLong()
        return SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(System.currentTimeMillis() + millis))
    }

    fun duration(distanceKm: Double, speedKmh: Double): String {
        if (speedKmh < 3.0) return "-"
        val totalMin = (distanceKm / speedKmh * 60.0).roundToLong()
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "${h}시간 ${m}분" else "${m}분"
    }
}
