package com.seungjae.jangsu280battery

import java.util.Locale

/**
 * Kakao Map official URL-scheme builder for bicycle route handoff.
 * The app scheme opens Kakao Map directly when installed; the mobile-web
 * scheme is kept as a deterministic fallback when no app handler exists.
 */
object KakaoBicycleNavigator {
    fun appRouteUri(startLat: Double, startLon: Double, endLat: Double, endLon: Double): String =
        "kakaomap://route?sp=${coord(startLat)},${coord(startLon)}&ep=${coord(endLat)},${coord(endLon)}&by=bicycle"

    fun mobileWebRouteUri(startLat: Double, startLon: Double, endLat: Double, endLon: Double): String =
        "https://m.map.kakao.com/scheme/route?sp=${coord(startLat)},${coord(startLon)}&ep=${coord(endLat)},${coord(endLon)}&by=bicycle"

    private fun coord(v: Double): String = String.format(Locale.US, "%.7f", v)
}
