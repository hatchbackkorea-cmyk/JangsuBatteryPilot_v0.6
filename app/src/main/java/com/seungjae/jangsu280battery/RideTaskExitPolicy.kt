package com.seungjae.jangsu280battery

import android.content.Context
import java.io.File

/** Explicit task-exit policy. */
object RideTaskExitPolicy {
    private const val RIDE_PREFS = "ride_log_manager"
    private const val CHARGE_PREFS = "charging_session_state"

    private val activeKeys = arrayOf(
        "active_id",
        "active_course_id",
        "active_course_name",
        "active_start",
        "active_mode",
        "active_ascent_m",
        "active_max_km",
        "active_speed_sum",
        "active_speed_count",
        "active_assist_mode",
        "active_assist_profile_id",
        "active_assist_profile_json",
        "active_assist_source",
        "active_assist_confidence",
        "active_assist_raw_code",
        "assist_probe_until"
    )

    fun stopEverything(context: Context) {
        val app = context.applicationContext
        runCatching { app.stopService(android.content.Intent(app, RideService::class.java)) }

        val prefs = app.getSharedPreferences(RIDE_PREFS, Context.MODE_PRIVATE)
        val activeId = prefs.getString("active_id", null)
        if (!activeId.isNullOrBlank()) {
            runCatching { File(app.filesDir, "ride_sessions/$activeId").deleteRecursively() }
        }
        val edit = prefs.edit()
        activeKeys.forEach { edit.remove(it) }
        edit.apply()

        app.getSharedPreferences(CHARGE_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        AppSettings.prefs(app).edit().putFloat(AppSettings.KEY_LAST_KM, 0f).apply()
    }
}
