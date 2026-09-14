package com.seungjae.jangsu280battery

import android.content.Context
import android.net.Uri
import java.util.Locale

/** Stores a temporary official timing-device assignment created from an operator QR. */
object TimingOperatorStore {
    data class Assignment(
        val eventCode: String,
        val role: String,
        val token: String,
        val expiresAtMs: Long
    ) {
        fun isValid(nowMs: Long = System.currentTimeMillis()): Boolean =
            eventCode.isNotBlank() && role in ROLES && token.length >= 32 && expiresAtMs > nowMs
    }

    private const val PREFS = "timegate_timing_operator_v1"
    private const val KEY_EVENT = "event_code"
    private const val KEY_ROLE = "role"
    private const val KEY_TOKEN = "operator_token"
    private const val KEY_EXPIRES = "expires_at_ms"

    private const val CAMERA_PREFS = "camera_gate_test"
    private const val CAMERA_ROLE_KEY = "gate_role_v16"

    val ROLES = listOf("START", "CP1", "CP2", "CP3", "CP4", "CP5", "FINISH")

    fun current(context: Context, nowMs: Long = System.currentTimeMillis()): Assignment? {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val item = Assignment(
            eventCode = p.getString(KEY_EVENT, "").orEmpty(),
            role = p.getString(KEY_ROLE, "").orEmpty().uppercase(Locale.US),
            token = p.getString(KEY_TOKEN, "").orEmpty(),
            expiresAtMs = p.getLong(KEY_EXPIRES, 0L)
        )
        if (!item.isValid(nowMs)) {
            if (item.eventCode.isNotBlank() || item.token.isNotBlank()) clear(context)
            return null
        }
        return item
    }

    fun save(context: Context, assignment: Assignment) {
        require(assignment.isValid()) { "유효하지 않은 계측기 배정입니다." }
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_EVENT, assignment.eventCode.trim().uppercase(Locale.US))
            .putString(KEY_ROLE, assignment.role.uppercase(Locale.US))
            .putString(KEY_TOKEN, assignment.token)
            .putLong(KEY_EXPIRES, assignment.expiresAtMs)
            .apply()

        // Reuse the proven Camera Gate v16 role pipeline. QR assignment overrides AUTO locally.
        app.getSharedPreferences(CAMERA_PREFS, Context.MODE_PRIVATE).edit()
            .putString(CAMERA_ROLE_KEY, assignment.role.uppercase(Locale.US))
            .apply()
    }

    fun clear(context: Context) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        app.getSharedPreferences(CAMERA_PREFS, Context.MODE_PRIVATE).edit()
            .putString(CAMERA_ROLE_KEY, "AUTO")
            .apply()
    }

    fun buildLink(assignment: Assignment): String {
        require(assignment.isValid()) { "유효하지 않은 계측기 배정입니다." }
        return Uri.Builder()
            .scheme("jangsubatterypilot")
            .authority("timing")
            .appendPath("enroll")
            .appendQueryParameter("event", assignment.eventCode.trim().uppercase(Locale.US))
            .appendQueryParameter("role", assignment.role.uppercase(Locale.US))
            .appendQueryParameter("token", assignment.token)
            .appendQueryParameter("exp", assignment.expiresAtMs.toString())
            .build()
            .toString()
    }

    fun parse(uri: Uri?, nowMs: Long = System.currentTimeMillis()): Assignment? {
        if (uri == null || uri.scheme != "jangsubatterypilot" || uri.host != "timing") return null
        if (uri.pathSegments.firstOrNull() != "enroll") return null
        val event = uri.getQueryParameter("event").orEmpty().trim().uppercase(Locale.US)
        val role = uri.getQueryParameter("role").orEmpty().trim().uppercase(Locale.US)
        val token = uri.getQueryParameter("token").orEmpty().trim()
        val expires = uri.getQueryParameter("exp")?.toLongOrNull() ?: return null
        val item = Assignment(event, role, token, expires)
        if (!item.isValid(nowMs)) return null
        // QR registrations are deliberately short-lived. Reject copied/stale far-future links.
        if (expires - nowMs > 48L * 60L * 60L * 1000L) return null
        return item
    }
}
