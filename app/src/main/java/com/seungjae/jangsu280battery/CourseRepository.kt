package com.seungjae.jangsu280battery

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID


data class CourseMeta(
    val id: String,
    val name: String,
    val fileName: String,
    val totalKm: Double,
    val totalAscentM: Double,
    val totalDescentM: Double,
    val hasElevation: Boolean,
    val importedAtMs: Long,
    val builtIn: Boolean = false
)

class CourseRepository(context: Context) {
    companion object {
        private const val PREFS = "course_repository"
        private const val KEY_INDEX = "course_index"
        private const val KEY_ACTIVE = "active_course_id"
        const val BUILTIN_ID = "builtin_jangsu_stage1"
    }

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val dir = File(app.filesDir, "courses").apply { mkdirs() }

    init { ensureBuiltIn() }

    fun listCourses(): List<CourseMeta> = readIndex().sortedWith(
        compareByDescending<CourseMeta> { it.builtIn }.thenByDescending { it.importedAtMs }
    )

    fun activeMeta(): CourseMeta {
        val all = listCourses()
        val activeId = prefs.getString(KEY_ACTIVE, null)
        return all.firstOrNull { it.id == activeId } ?: all.first().also { setActive(it.id) }
    }

    fun setActive(id: String) {
        require(listCourses().any { it.id == id }) { "코스를 찾을 수 없습니다." }
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    fun loadActiveCourse(): CourseData = loadCourse(activeMeta().id)

    fun sourceFile(id: String): File? {
        val meta = listCourses().firstOrNull { it.id == id } ?: return null
        return File(dir, meta.fileName).takeIf { it.exists() }
    }

    fun loadCourse(id: String): CourseData {
        val meta = listCourses().firstOrNull { it.id == id } ?: error("코스를 찾을 수 없습니다.")
        val file = File(dir, meta.fileName)
        require(file.exists()) { "GPX 파일이 없습니다: ${meta.name}" }
        val base = file.inputStream().use { CourseData.parse(it, meta.name) }
        val extras = customPois(id)
        return if (extras.isEmpty()) base else base.withAdditionalPois(extras)
    }

    fun importGpx(uri: Uri, displayName: String?): CourseMeta {
        val id = "course_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val fileName = "$id.gpx"
        val target = File(dir, fileName)
        app.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        } ?: error("선택한 GPX 파일을 열 수 없습니다.")

        try {
            val parsed = target.inputStream().use { CourseData.parse(it, displayName ?: "가져온 GPX") }
            val meta = CourseMeta(
                id = id,
                name = parsed.name.ifBlank { displayName?.substringBeforeLast('.') ?: "가져온 GPX" },
                fileName = fileName,
                totalKm = parsed.totalKm,
                totalAscentM = parsed.totalAscentM,
                totalDescentM = parsed.totalDescentM,
                hasElevation = parsed.hasElevation,
                importedAtMs = System.currentTimeMillis(),
                builtIn = false
            )
            val list = readIndex().toMutableList().apply { add(meta) }
            writeIndex(list)
            setActive(meta.id)
            RiderServerSync(app).enqueueCourse(meta, target)
            return meta
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    fun deleteCourse(id: String): Boolean {
        val meta = listCourses().firstOrNull { it.id == id } ?: return false
        if (meta.builtIn) return false
        File(dir, meta.fileName).delete()
        prefs.edit().remove(customPoiKey(id)).apply()
        ChargingStationStore(app).clear(id)
        AvinoxReferenceStore(app).clear(id)
        val remaining = readIndex().filterNot { it.id == id }
        writeIndex(remaining)
        if (prefs.getString(KEY_ACTIVE, null) == id) {
            prefs.edit().putString(KEY_ACTIVE, remaining.firstOrNull()?.id ?: BUILTIN_ID).apply()
        }
        return true
    }

    fun addCustomSupplyPoint(id: String, routeKm: Double): RoutePoi {
        val course = loadCourse(id)
        val point = course.pointAtKm(routeKm)
        val current = customPois(id).toMutableList()
        val number = current.count { it.userAdded && it.name.startsWith("사용자 보급소") } + 1
        val poi = RoutePoi(
            name = "사용자 보급소 $number",
            routeKm = routeKm.coerceIn(0.0, course.totalKm),
            lat = point.lat,
            lon = point.lon,
            desc = "라이딩 중 사용자가 등록한 보급/충전 지점",
            type = "supply",
            userAdded = true
        )
        current += poi
        writeCustomPois(id, current)
        return poi
    }

    fun customPois(id: String): List<RoutePoi> {
        val raw = prefs.getString(customPoiKey(id), null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RoutePoi(
                    name = o.optString("name", "사용자 보급소"),
                    routeKm = o.optDouble("routeKm", 0.0),
                    lat = o.optDouble("lat", 0.0),
                    lon = o.optDouble("lon", 0.0),
                    desc = o.optString("desc", ""),
                    type = o.optString("type", "supply"),
                    userAdded = true
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun writeCustomPois(id: String, items: List<RoutePoi>) {
        val arr = JSONArray()
        items.forEach { p -> arr.put(JSONObject().apply {
            put("name", p.name); put("routeKm", p.routeKm); put("lat", p.lat); put("lon", p.lon)
            put("desc", p.desc); put("type", p.type)
        }) }
        prefs.edit().putString(customPoiKey(id), arr.toString()).apply()
    }

    private fun customPoiKey(id: String) = "custom_pois_$id"

    private fun ensureBuiltIn() {
        val current = readIndex().toMutableList()
        val existing = current.firstOrNull { it.id == BUILTIN_ID }
        val file = File(dir, "jangsu_stage1_builtin.gpx")
        if (!file.exists()) {
            app.resources.openRawResource(R.raw.jangsu_stage1_battery).use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
        }
        if (existing == null) {
            val parsed = file.inputStream().use { CourseData.parse(it, "장수280 Stage1") }
            current += CourseMeta(
                id = BUILTIN_ID,
                name = "장수280 Stage1",
                fileName = file.name,
                totalKm = parsed.totalKm,
                totalAscentM = parsed.totalAscentM,
                totalDescentM = parsed.totalDescentM,
                hasElevation = parsed.hasElevation,
                importedAtMs = 0L,
                builtIn = true
            )
            writeIndex(current)
        }
        if (prefs.getString(KEY_ACTIVE, null).isNullOrBlank()) prefs.edit().putString(KEY_ACTIVE, BUILTIN_ID).apply()
    }

    private fun readIndex(): List<CourseMeta> {
        val raw = prefs.getString(KEY_INDEX, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CourseMeta(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    fileName = o.getString("fileName"),
                    totalKm = o.optDouble("totalKm", 0.0),
                    totalAscentM = o.optDouble("totalAscentM", 0.0),
                    totalDescentM = o.optDouble("totalDescentM", 0.0),
                    hasElevation = o.optBoolean("hasElevation", false),
                    importedAtMs = o.optLong("importedAtMs", 0L),
                    builtIn = o.optBoolean("builtIn", false)
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun writeIndex(items: List<CourseMeta>) {
        val arr = JSONArray()
        items.forEach { m -> arr.put(JSONObject().apply {
            put("id", m.id); put("name", m.name); put("fileName", m.fileName)
            put("totalKm", m.totalKm); put("totalAscentM", m.totalAscentM); put("totalDescentM", m.totalDescentM)
            put("hasElevation", m.hasElevation); put("importedAtMs", m.importedAtMs); put("builtIn", m.builtIn)
        }) }
        prefs.edit().putString(KEY_INDEX, arr.toString()).apply()
    }
}
