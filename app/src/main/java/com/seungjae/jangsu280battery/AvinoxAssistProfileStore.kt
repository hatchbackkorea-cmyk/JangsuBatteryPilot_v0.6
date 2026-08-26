package com.seungjae.jangsu280battery

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

enum class AvinoxAssistMode(val label: String) {
    MIN("MIN"),
    ECO("Eco"),
    AUTO("Auto"),
    TRAIL("Trail"),
    TURBO("Turbo")
}

data class AvinoxAssistProfile(
    val mode: AvinoxAssistMode,
    val assistMin: Int? = null,
    val assistMax: Int? = null,
    val maxTorqueNm: Int? = null,
    val maxPowerW: Int? = null,
    /** Relative slider position because Avinox does not expose a numeric unit in the shown UI. 0=min, 4=max. */
    val motorOverrunStep: Int? = null,
    val startAssistStep: Int? = null,
    val continuousAssistStep: Int? = null,
    val sourceNote: String = "사용자 설정",
    val savedAtMs: Long = System.currentTimeMillis()
) {
    val profileId: String get() = "${mode.name}_${fingerprint()}"

    fun compactText(): String {
        val assist = when {
            assistMin != null && assistMax != null && assistMin != assistMax -> "Assist $assistMin~$assistMax"
            assistMin != null -> "Assist $assistMin"
            else -> "Assist ?"
        }
        return listOfNotNull(
            assist,
            maxTorqueNm?.let { "${it}Nm" },
            maxPowerW?.let { "${it}W" },
            motorOverrunStep?.let { "오버런$it" },
            startAssistStep?.let { "시작$it" },
            continuousAssistStep?.let { "연속$it" }
        ).joinToString(" · ")
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("profileId", profileId)
        put("mode", mode.name)
        assistMin?.let { put("assistMin", it) }
        assistMax?.let { put("assistMax", it) }
        maxTorqueNm?.let { put("maxTorqueNm", it) }
        maxPowerW?.let { put("maxPowerW", it) }
        motorOverrunStep?.let { put("motorOverrunStep", it) }
        startAssistStep?.let { put("startAssistStep", it) }
        continuousAssistStep?.let { put("continuousAssistStep", it) }
        put("sourceNote", sourceNote)
        put("savedAtMs", savedAtMs)
    }

    private fun fingerprint(): String {
        val raw = listOf(
            mode.name,
            assistMin?.toString().orEmpty(), assistMax?.toString().orEmpty(),
            maxTorqueNm?.toString().orEmpty(), maxPowerW?.toString().orEmpty(),
            motorOverrunStep?.toString().orEmpty(), startAssistStep?.toString().orEmpty(), continuousAssistStep?.toString().orEmpty()
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.take(5).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

class AvinoxAssistProfileStore(context: Context) {
    companion object {
        private const val PREFS = "avinox_assist_profiles"
        private const val PREF_MODE = "preferred_mode"
        private const val PREF_MODE_EXPLICIT = "preferred_mode_explicit"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun preferredMode(): AvinoxAssistMode = runCatching {
        AvinoxAssistMode.valueOf(prefs.getString(PREF_MODE, AvinoxAssistMode.ECO.name) ?: AvinoxAssistMode.ECO.name)
    }.getOrDefault(AvinoxAssistMode.ECO)

    fun hasPreferredMode(): Boolean = prefs.getBoolean(PREF_MODE_EXPLICIT, false)

    fun setPreferredMode(mode: AvinoxAssistMode) {
        prefs.edit().putString(PREF_MODE, mode.name).putBoolean(PREF_MODE_EXPLICIT, true).apply()
    }

    fun get(mode: AvinoxAssistMode): AvinoxAssistProfile {
        val raw = prefs.getString(key(mode), null) ?: return defaultProfile(mode)
        return runCatching { fromJson(JSONObject(raw), mode) }.getOrElse { defaultProfile(mode) }
    }

    fun save(profile: AvinoxAssistProfile) {
        prefs.edit().putString(key(profile.mode), profile.toJson().toString()).apply()
    }

    fun reset(mode: AvinoxAssistMode) {
        prefs.edit().remove(key(mode)).apply()
    }

    fun all(): List<AvinoxAssistProfile> = AvinoxAssistMode.values().map(::get)

    private fun key(mode: AvinoxAssistMode) = "profile_${mode.name.lowercase()}"

    private fun fromJson(o: JSONObject, mode: AvinoxAssistMode): AvinoxAssistProfile = AvinoxAssistProfile(
        mode = mode,
        assistMin = o.optIntOrNull("assistMin"),
        assistMax = o.optIntOrNull("assistMax"),
        maxTorqueNm = o.optIntOrNull("maxTorqueNm"),
        maxPowerW = o.optIntOrNull("maxPowerW"),
        motorOverrunStep = o.optIntOrNull("motorOverrunStep"),
        startAssistStep = o.optIntOrNull("startAssistStep"),
        continuousAssistStep = o.optIntOrNull("continuousAssistStep"),
        sourceNote = o.optString("sourceNote", "사용자 설정"),
        savedAtMs = o.optLong("savedAtMs", System.currentTimeMillis())
    )

    /**
     * Initial values are transcribed from the user's 2026-08-26 Avinox screenshots.
     * The three unlabeled response sliders are stored as relative 0..4 positions only.
     */
    fun defaultProfile(mode: AvinoxAssistMode): AvinoxAssistProfile = when (mode) {
        AvinoxAssistMode.MIN -> AvinoxAssistProfile(mode, 5, 5, 40, 150, 0, 1, 2, sourceNote = "2026-08-26 사진 기준 초기값")
        AvinoxAssistMode.ECO -> AvinoxAssistProfile(mode, 5, 5, 40, 100, 0, 2, 2, sourceNote = "2026-08-26 사진 기준 초기값")
        AvinoxAssistMode.AUTO -> AvinoxAssistProfile(mode, 3, 8, 105, 900, 0, 4, 2, sourceNote = "2026-08-26 사진 기준 초기값")
        AvinoxAssistMode.TRAIL -> AvinoxAssistProfile(mode, 9, 11, 105, 850, 1, 4, 2, sourceNote = "2026-08-26 사진 기준 초기값")
        AvinoxAssistMode.TURBO -> AvinoxAssistProfile(mode, 13, 13, 105, 850, 2, 4, 2, sourceNote = "2026-08-26 사진 기준 초기값")
    }
}

private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
