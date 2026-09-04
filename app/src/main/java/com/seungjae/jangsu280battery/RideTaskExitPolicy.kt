package com.seungjae.jangsu280battery

import android.content.Context
import java.io.File

/**
 * Explicit task-exit policy for v0.33.5.
 *
 * The user chose "close means completely stop" rather than automatic ride resume.
 * This helper is intentionally small and idempotent so the root activity can call it while
 * Android is removing the task. It removes only active/in-progress state; completed archives,
 * learning data, server settings and update settings are preserved.
 */
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

        // Stop both foreground ride tracking and any release/deploy foreground work owned by app.
        runCatching { app.stopService(android.content.Intent(app, RideService::class.java)) }
        runCatching { app.stopService(android.content.Intent(app, ReleaseDeployService::class.java)) }

        val prefs = app.getSharedPreferences(RIDE_PREFS, Context.MODE_PRIVATE)
        val activeId = prefs.getString("active_id", null)
        if (!activeId.isNullOrBlank()) {
            // The user explicitly discarded resume. Remove only the unfinished session directory;
            // completed exported rides live elsewhere and are untouched.
            runCatching { File(app.filesDir, "ride_sessions/$activeId").deleteRecursively() }
        }
        prefs.edit().also { edit -> activeKeys.forEach(edit::remove) }.apply()

        app.getSharedPreferences(CHARGE_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        AppSettings.prefs(app).edit().putFloat(AppSettings.KEY_LAST_KM, 0f).apply()
    }
}
