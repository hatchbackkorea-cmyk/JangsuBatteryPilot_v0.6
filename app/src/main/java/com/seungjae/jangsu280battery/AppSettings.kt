package com.seungjae.jangsu280battery

import android.content.Context

/**
 * 앱 전역 설정. v0.8부터 메인 화면에서 설정 UI를 분리하고 이 키를 공통 사용한다.
 */
object AppSettings {
    const val PREFS = "ride_state"
    const val KEY_LAST_KM = "last_km"
    const val KEY_VOICE = "voice_enabled"
    const val KEY_VOICE_VOLUME_BOOST = "voice_volume_boost_enabled"
    const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    const val KEY_FINISH_TARGET = "finish_target"
    const val KEY_ANNOUNCE_DISTANCE_KM = "announce_distance_km"
    const val KEY_ANNOUNCE_TIME_MIN = "announce_time_min"
    const val KEY_TEST_MODE = "test_mode"
    const val KEY_TEST_KM = "test_km"
    const val KEY_BETA_UPDATES = "beta_updates"
    const val KEY_CHARGE_ALERT_ENABLED = "charge_alert_enabled"
    const val KEY_CHARGE_ALERT_TARGET = "charge_alert_target"
    const val KEY_HARD_RESERVE = "hard_reserve_pct"

    const val DEFAULT_FINISH_TARGET = 15
    const val DEFAULT_DISTANCE_INTERVAL_KM = 5
    const val DEFAULT_TIME_INTERVAL_MIN = 0
    const val DEFAULT_CHARGE_ALERT_TARGET = 100
    const val DEFAULT_HARD_RESERVE = 7

    fun finishTarget(context: Context): Double = prefs(context)
        .getIntCompat(KEY_FINISH_TARGET, DEFAULT_FINISH_TARGET)
        .coerceIn(1, 99)
        .toDouble()

    fun voiceEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_VOICE, true)
    fun voiceVolumeBoostEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_VOICE_VOLUME_BOOST, true)
    fun keepScreenOn(context: Context): Boolean = prefs(context).getBoolean(KEY_KEEP_SCREEN_ON, false)
    fun distanceIntervalKm(context: Context): Int = prefs(context)
        .getIntCompat(KEY_ANNOUNCE_DISTANCE_KM, DEFAULT_DISTANCE_INTERVAL_KM)
        .coerceIn(0, 50)
    fun timeIntervalMin(context: Context): Int = prefs(context)
        .getIntCompat(KEY_ANNOUNCE_TIME_MIN, DEFAULT_TIME_INTERVAL_MIN)
        .coerceIn(0, 120)
    fun testMode(context: Context): Boolean = prefs(context).getBoolean(KEY_TEST_MODE, false)
    fun testKm(context: Context): Double = prefs(context).getFloat(KEY_TEST_KM, 0f).toDouble().coerceAtLeast(0.0)
    fun betaUpdates(context: Context): Boolean = prefs(context).getBoolean(KEY_BETA_UPDATES, false)
    fun chargeAlertEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_CHARGE_ALERT_ENABLED, true)
    fun chargeAlertTarget(context: Context): Int = prefs(context)
        .getIntCompat(KEY_CHARGE_ALERT_TARGET, DEFAULT_CHARGE_ALERT_TARGET)
        .coerceIn(50, 100)

    fun hardReserve(context: Context): Int = prefs(context)
        .getIntCompat(KEY_HARD_RESERVE, DEFAULT_HARD_RESERVE)
        .coerceIn(5, 15)

    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** v0.7의 float 저장값과 v0.8의 int 저장값을 모두 읽기 위한 호환 처리. */
    private fun android.content.SharedPreferences.getIntCompat(key: String, defaultValue: Int): Int {
        return try {
            getInt(key, defaultValue)
        } catch (_: ClassCastException) {
            getFloat(key, defaultValue.toFloat()).toInt()
        }
    }
}
