package com.seungjae.jangsu280battery

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent lap-session boundary.
 *
 * A session begins the first time timing is armed for an event/course on a local calendar day.
 * Leaving the race room, closing/reopening RaceActivity, or rejoining the same room does not reset it.
 * A different event, a different course, or a new local day starts a new session.
 */
class RaceLapSessionStore(context: Context) {
    data class Session(
        val eventCode: String,
        val courseId: String,
        val dayKey: String,
        val startedAtMs: Long
    )

    private val prefs = context.applicationContext.getSharedPreferences("race_lap_session_v1", Context.MODE_PRIVATE)

    @Synchronized
    fun beginOrResume(eventCode: String, courseId: String, now: Long = System.currentTimeMillis()): Session {
        val event = eventCode.trim().uppercase().ifBlank { "PRACTICE" }
        val course = courseId.trim()
        val day = dayKey(now)
        val existing = current()
        if (existing != null && existing.eventCode == event && existing.courseId == course && existing.dayKey == day) {
            return existing
        }
        val created = Session(event, course, day, now)
        prefs.edit()
            .putString("event_code", created.eventCode)
            .putString("course_id", created.courseId)
            .putString("day_key", created.dayKey)
            .putLong("started_at_ms", created.startedAtMs)
            .apply()
        return created
    }

    fun current(): Session? {
        val event = prefs.getString("event_code", "").orEmpty()
        val course = prefs.getString("course_id", "").orEmpty()
        val day = prefs.getString("day_key", "").orEmpty()
        val start = prefs.getLong("started_at_ms", 0L)
        if (event.isBlank() || course.isBlank() || day.isBlank() || start <= 0L) return null
        return Session(event, course, day, start)
    }

    fun matching(eventCode: String, courseId: String, now: Long = System.currentTimeMillis()): Session? {
        val s = current() ?: return null
        val event = eventCode.trim().uppercase().ifBlank { "PRACTICE" }
        return s.takeIf { it.eventCode == event && it.courseId == courseId.trim() && it.dayKey == dayKey(now) }
    }

    private fun dayKey(timeMs: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timeMs))
}
