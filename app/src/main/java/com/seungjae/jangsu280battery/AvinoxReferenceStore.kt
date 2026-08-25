package com.seungjae.jangsu280battery

import android.content.Context
import org.json.JSONObject

/**
 * DJI Avinox 앱이 특정 GPX 코스에 대해 보여주는 모드별 예상 소비율을
 * 개인 학습 데이터와 분리해서 보존한다.
 *
 * 이 값은 실제 주행 정답이나 학습 샘플이 아니라 외부 기준(benchmark)이다.
 * BatteryPlan은 사용자가 선택한 모드의 값이 있을 때만 제한된 가중치로
 * 전체 코스 소비량의 prior로 사용한다.
 */
enum class AvinoxRideMode(val label: String) {
    ECO("ECO"),
    AUTO("AUTO"),
    TRAIL("TRAIL"),
    TURBO("TURBO")
}

data class AvinoxCourseReference(
    val courseId: String,
    val ecoPct: Double? = null,
    val autoPct: Double? = null,
    val trailPct: Double? = null,
    val turboPct: Double? = null,
    val selectedMode: AvinoxRideMode? = null,
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    fun value(mode: AvinoxRideMode): Double? = when (mode) {
        AvinoxRideMode.ECO -> ecoPct
        AvinoxRideMode.AUTO -> autoPct
        AvinoxRideMode.TRAIL -> trailPct
        AvinoxRideMode.TURBO -> turboPct
    }?.takeIf { it in 0.1..100.0 }

    fun selectedValue(): Double? = selectedMode?.let(::value)

    fun hasAny(): Boolean = AvinoxRideMode.values().any { value(it) != null }

    fun compactValues(): String = AvinoxRideMode.values().mapNotNull { mode ->
        value(mode)?.let { "${mode.label} ${formatPct(it)}" }
    }.joinToString(" · ")

    companion object {
        private fun formatPct(value: Double): String {
            val rounded = kotlin.math.round(value)
            return if (kotlin.math.abs(value - rounded) < 0.05) "${rounded.toInt()}%"
            else String.format(java.util.Locale.US, "%.1f%%", value)
        }
    }
}

class AvinoxReferenceStore(context: Context) {
    companion object {
        private const val PREFS = "avinox_course_reference"
        private const val KEY_PREFIX = "course_"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(courseId: String): AvinoxCourseReference? {
        if (courseId.isBlank()) return null
        val raw = prefs.getString(key(courseId), null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            AvinoxCourseReference(
                courseId = courseId,
                ecoPct = nullablePct(o, "ecoPct"),
                autoPct = nullablePct(o, "autoPct"),
                trailPct = nullablePct(o, "trailPct"),
                turboPct = nullablePct(o, "turboPct"),
                selectedMode = o.optString("selectedMode", "").takeIf { it.isNotBlank() }?.let {
                    runCatching { AvinoxRideMode.valueOf(it) }.getOrNull()
                },
                updatedAtMs = o.optLong("updatedAtMs", 0L)
            )
        }.getOrNull()?.takeIf { it.hasAny() }
    }

    fun save(
        courseId: String,
        ecoPct: Double?,
        autoPct: Double?,
        trailPct: Double?,
        turboPct: Double?,
        selectedMode: AvinoxRideMode?
    ): AvinoxCourseReference? {
        require(courseId.isNotBlank()) { "코스 ID가 없습니다." }
        val ref = AvinoxCourseReference(
            courseId = courseId,
            ecoPct = clean(ecoPct),
            autoPct = clean(autoPct),
            trailPct = clean(trailPct),
            turboPct = clean(turboPct),
            selectedMode = selectedMode,
            updatedAtMs = System.currentTimeMillis()
        )
        if (!ref.hasAny()) {
            clear(courseId)
            return null
        }
        val validSelected = ref.selectedMode?.takeIf { ref.value(it) != null }
        val normalized = ref.copy(selectedMode = validSelected)
        val o = JSONObject().apply {
            putNullable("ecoPct", normalized.ecoPct)
            putNullable("autoPct", normalized.autoPct)
            putNullable("trailPct", normalized.trailPct)
            putNullable("turboPct", normalized.turboPct)
            put("selectedMode", normalized.selectedMode?.name ?: "")
            put("updatedAtMs", normalized.updatedAtMs)
        }
        prefs.edit().putString(key(courseId), o.toString()).apply()
        return normalized
    }

    fun setSelectedMode(courseId: String, mode: AvinoxRideMode): AvinoxCourseReference? {
        val current = get(courseId) ?: return null
        if (current.value(mode) == null) return current
        return save(courseId, current.ecoPct, current.autoPct, current.trailPct, current.turboPct, mode)
    }

    fun clear(courseId: String) {
        prefs.edit().remove(key(courseId)).apply()
    }

    private fun key(courseId: String) = KEY_PREFIX + courseId

    private fun clean(v: Double?): Double? = v?.takeIf { it.isFinite() && it in 0.1..100.0 }

    private fun nullablePct(o: JSONObject, name: String): Double? =
        if (o.has(name) && !o.isNull(name)) clean(o.optDouble(name)) else null

    private fun JSONObject.putNullable(name: String, value: Double?) {
        if (value == null) put(name, JSONObject.NULL) else put(name, value)
    }

}
