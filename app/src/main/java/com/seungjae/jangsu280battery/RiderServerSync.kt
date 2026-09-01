package com.seungjae.jangsu280battery

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

/**
 * v0.31.6 mobile -> Rider Control Center sync.
 * Local data is always the source of truth while offline. Failed operations remain in a durable queue.
 */
class RiderServerSync(context: Context) {
    companion object {
        private const val PREF = "rider_server_sync_v1"
        private const val KEY_URL = "server_url"
        private const val KEY_AUTO = "auto_sync"
        private const val KEY_NAME = "rider_name"
        private const val KEY_WEIGHT = "weight_kg"
        private const val KEY_FTP = "ftp_w"
        private const val KEY_QUEUE = "queue_json"
        private const val KEY_LAST_OK = "last_ok_ms"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_REMOTE_PLANS = "remote_plans"
    }

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private val secure = RiderServerSecureStore(app)
    private val lock = Any()

    data class Result(val ok: Boolean, val message: String, val uploaded: Int = 0, val pending: Int = 0)

    fun serverUrl(): String = prefs.getString(KEY_URL, "").orEmpty().trim().trimEnd('/')
    fun token(): String = secure.token().orEmpty()
    fun autoEnabled(): Boolean = prefs.getBoolean(KEY_AUTO, true)
    fun riderName(): String = prefs.getString(KEY_NAME, "").orEmpty()
    fun weightKg(): Double = prefs.getString(KEY_WEIGHT, "75")?.toDoubleOrNull()?.coerceIn(30.0, 200.0) ?: 75.0
    fun ftpW(): Double = prefs.getString(KEY_FTP, "200")?.toDoubleOrNull()?.coerceIn(50.0, 600.0) ?: 200.0
    fun configured(): Boolean = serverUrl().startsWith("http") && token().isNotBlank()
    fun pendingCount(): Int = synchronized(lock) { readQueue().length() }
    fun remotePlans(): JSONArray = runCatching { JSONArray(prefs.getString(KEY_REMOTE_PLANS, "[]")) }.getOrDefault(JSONArray())

    fun configure(url: String, deviceToken: String, name: String, weightKg: Double, ftpW: Double, auto: Boolean) {
        prefs.edit()
            .putString(KEY_URL, url.trim().trimEnd('/'))
            .putString(KEY_NAME, name.trim())
            .putString(KEY_WEIGHT, String.format(Locale.US, "%.2f", weightKg.coerceIn(30.0, 200.0)))
            .putString(KEY_FTP, String.format(Locale.US, "%.1f", ftpW.coerceIn(50.0, 600.0)))
            .putBoolean(KEY_AUTO, auto)
            .apply()
        if (deviceToken.isNotBlank()) secure.saveToken(deviceToken)
    }

    fun statusText(): String = buildString {
        append(if (configured()) "Rider Server 연결 설정됨" else "Rider Server 미연결")
        append(" · 자동동기화 ").append(if (autoEnabled()) "ON" else "OFF")
        append(" · 대기 ").append(pendingCount()).append("건")
        val ok = prefs.getLong(KEY_LAST_OK, 0L)
        if (ok > 0) append("\n마지막 성공 · ").append(java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA).format(java.util.Date(ok)))
        prefs.getString(KEY_LAST_ERROR, null)?.takeIf { it.isNotBlank() }?.let { append("\n최근 오류 · ").append(it) }
        if (serverUrl().startsWith("http://127.0.0.1") || serverUrl().startsWith("http://localhost")) {
            append("\n※ 휴대폰에서는 127.0.0.1 대신 PC의 Wi-Fi/LAN IP를 사용하세요.")
        }
    }

    fun enqueueCourse(meta: CourseMeta, file: File) {
        if (!file.exists()) return
        enqueue(JSONObject().apply {
            put("type", "COURSE"); put("key", meta.id); put("path", file.absolutePath)
            put("name", meta.name)
            put("metadata", JSONObject().apply {
                put("distance_km", meta.totalKm); put("ascent_m", meta.totalAscentM); put("descent_m", meta.totalDescentM)
                put("has_elevation", meta.hasElevation); put("imported_at_ms", meta.importedAtMs)
            })
        })
        triggerIfAuto()
    }

    fun enqueueRide(archive: RideArchive) {
        if (!archive.zipFile.exists()) return
        val summary = runCatching { JSONObject(archive.jsonFile.readText()) }.getOrDefault(JSONObject())
        val meta = JSONObject().apply {
            put("start_time_ms", archive.startMs); put("distance_km", archive.maxRouteKm)
            put("elapsed_time_s", ((archive.endMs - archive.startMs).coerceAtLeast(0L) / 1000.0))
            if (summary.has("courseAscentM")) put("ascent_m", summary.optDouble("courseAscentM"))
            else if (summary.has("gpsAscentM")) put("ascent_m", summary.optDouble("gpsAscentM"))
            put("avg_speed_kph", archive.avgSpeedKmh)
            put("ride_mode", archive.rideMode.name)
            put("has_motor_power", summary.optJSONArray("assistModeStats")?.length()?.let { it > 0 } ?: false)
        }
        enqueue(JSONObject().apply {
            put("type", "RIDE"); put("key", archive.sessionId); put("path", archive.zipFile.absolutePath)
            put("activity_type", if (archive.rideMode == RideMode.FREE || ((summary.optJSONArray("assistModeStats")?.length() ?: 0) > 0)) "EMTB" else "UNKNOWN")
            put("metadata", meta)
        })
        triggerIfAuto()
    }

    fun enqueuePlan(courseClientKey: String, plan: RoadPlan, name: String, basis: String, stops: List<RoadAidSelection>) {
        if (courseClientKey.isBlank()) return
        val key = "${courseClientKey}_${basis}_${plan.ridingTargetSec.toLong()}"
        val payload = JSONObject().apply {
            put("client_key", key); put("course_client_key", courseClientKey); put("name", name)
            put("target_time_s", plan.ridingTargetSec)
            // Current ROAD phone planner is time/grade-distribution based, not FTP-zone based. Keep neutral defaults for server Race Lab.
            put("flat_pct_ftp", 0.70); put("climb_pct_ftp", 0.82); put("steep_pct_ftp", 0.95); put("downhill_pct_ftp", 0.20)
            put("stops", JSONArray().apply { stops.forEach { s -> put(JSONObject().apply { put("name",s.name); put("km",s.km); put("seconds",s.stopSec) }) } })
            put("notes", "Android 자동동기화 · $basis · 계획완주 ${plan.totalSec.toLong()}초")
        }
        enqueue(JSONObject().apply { put("type", "PLAN"); put("key", key); put("payload", payload) })
        triggerIfAuto()
    }

    fun syncAllAsync(callback: ((Result) -> Unit)? = null) {
        Thread {
            val result = runCatching { syncAllBlocking() }.getOrElse { e ->
                prefs.edit().putString(KEY_LAST_ERROR, e.message ?: e.javaClass.simpleName).apply()
                Result(false, "동기화 실패 · ${e.message ?: "네트워크 확인"}", pending = pendingCount())
            }
            callback?.invoke(result)
        }.start()
    }

    private fun triggerIfAuto() { if (autoEnabled() && configured()) syncAllAsync() }

    private fun syncAllBlocking(): Result {
        if (!configured()) return Result(false, "서버 주소/연결 토큰을 먼저 설정하세요.", pending = pendingCount())
        putJson("/api/mobile/profile", profileJson())
        postJson("/api/mobile/snapshot", snapshotJson())
        var uploaded = 0
        var i = 0
        while (true) {
            val item = synchronized(lock) { readQueue().optJSONObject(0) } ?: break
            try {
                when (item.optString("type")) {
                    "COURSE" -> uploadCourse(item)
                    "RIDE" -> uploadRide(item)
                    "PLAN" -> postJson("/api/mobile/plans/upsert", item.getJSONObject("payload"))
                    else -> Unit
                }
                synchronized(lock) { val q=readQueue(); val n=JSONArray(); for (x in 1 until q.length()) n.put(q.get(x)); writeQueue(n) }
                uploaded++; i++
                if (i > 200) break
            } catch (e: HttpFailure) {
                if (e.code == 409 && item.optString("type") == "PLAN") {
                    // Course upload may be queued later. Rotate the plan behind the remaining queue once.
                    synchronized(lock) { rotateFirst() }
                    i++; if (i > pendingCount() + 2) break
                } else throw e
            }
        }
        val boot = getJson("/api/mobile/bootstrap")
        prefs.edit().putString(KEY_REMOTE_PLANS, boot.optJSONArray("pending_plans")?.toString() ?: "[]")
            .putLong(KEY_LAST_OK, System.currentTimeMillis()).remove(KEY_LAST_ERROR).apply()
        return Result(true, "자동동기화 완료 · 업로드 ${uploaded}건 · 대기 ${pendingCount()}건", uploaded, pendingCount())
    }

    private fun profileJson(): JSONObject {
        val curve = roadPowerCurve()
        return JSONObject().apply {
            if (riderName().isNotBlank()) put("name", riderName())
            put("weight_kg", weightKg()); put("ftp_w", ftpW()); put("power_curve", curve)
        }
    }

    private fun roadPowerCurve(): JSONObject {
        val active = StravaReviewStore(app).loadActive()
        val c = active?.power
        if (c != null) return JSONObject().apply {
            listOf("15s" to c.p15s,"1m" to c.p1m,"2m" to c.p2m,"5m" to c.p5m,"10m" to c.p10m,"20m" to c.p20m,"40m" to c.p40m,"1h" to c.p1h,"2h" to c.p2h,"4h" to c.p4h).forEach { (k,v) -> if (v != null) put(k,v) }
        }
        val p = RideInsightStore(app).allTimePeaks()
        fun near(sec:Int)=p[sec]
        return JSONObject().apply {
            near(15)?.let { put("15s",it) }; near(60)?.let { put("1m",it) }; near(300)?.let { put("5m",it) }
            near(600)?.let { put("10m",it) }; near(1200)?.let { put("20m",it) }; near(2400)?.let { put("40m",it) }; near(3600)?.let { put("1h",it) }
        }
    }

    private fun snapshotJson(): JSONObject {
        val strava = StravaReviewStore(app).loadActive()
        val courses = CourseRepository(app).listCourses()
        val snap = JSONObject().apply {
            put("pending_queue", pendingCount())
            put("settings", JSONObject().apply {
                put("finish_target_pct", AppSettings.finishTarget(app)); put("charge_alert_enabled", AppSettings.chargeAlertEnabled(app)); put("charge_alert_target_pct", AppSettings.chargeAlertTarget(app))
                put("distance_announce_km", AppSettings.distanceIntervalKm(app)); put("time_announce_min", AppSettings.timeIntervalMin(app))
            })
            put("strava", JSONObject().apply {
                put("linked", strava != null); if (strava != null) { put("athlete",strava.athleteName ?: ""); put("year",strava.resolvedYear()); put("road_rides",strava.selectedRides.size); put("endurance_rides",strava.enduranceRides.size); put("power_curve",roadPowerCurve()) }
            })
            put("ride_insights", JSONObject().apply { put("count",RideInsightStore(app).records().size); put("summary",RideInsightStore(app).summaryText()) })
            put("courses", JSONArray().apply { courses.forEach { c -> put(JSONObject().apply { put("client_key",c.id);put("name",c.name);put("distance_km",c.totalKm);put("ascent_m",c.totalAscentM);put("built_in",c.builtIn) }) } })
        }
        val version=runCatching { app.packageManager.getPackageInfo(app.packageName,0).versionName }.getOrNull() ?: ""
        return JSONObject().apply { put("app_version",version); put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}"); put("snapshot",snap) }
    }

    private fun enqueue(item: JSONObject) = synchronized(lock) {
        val q=readQueue(); val n=JSONArray(); var replaced=false
        for (i in 0 until q.length()) {
            val old=q.optJSONObject(i) ?: continue
            if (old.optString("type")==item.optString("type") && old.optString("key")==item.optString("key")) { n.put(item); replaced=true } else n.put(old)
        }
        if (!replaced) n.put(item); writeQueue(n)
    }
    private fun readQueue(): JSONArray = runCatching { JSONArray(prefs.getString(KEY_QUEUE,"[]")) }.getOrDefault(JSONArray())
    private fun writeQueue(q: JSONArray) { prefs.edit().putString(KEY_QUEUE,q.toString()).apply() }
    private fun rotateFirst() = synchronized(lock) { val q=readQueue(); if(q.length()<2)return@synchronized; val first=q.get(0); val n=JSONArray(); for(i in 1 until q.length())n.put(q.get(i));n.put(first);writeQueue(n) }

    private fun uploadCourse(item: JSONObject) {
        val f=File(item.getString("path")); if(!f.exists()) return
        multipart("/api/mobile/courses/upload", mapOf("client_key" to item.getString("key"),"name" to item.optString("name",f.nameWithoutExtension),"metadata_json" to item.optJSONObject("metadata")?.toString().orEmpty()), f, "file")
    }
    private fun uploadRide(item: JSONObject) {
        val f=File(item.getString("path")); if(!f.exists()) return
        multipart("/api/mobile/rides/upload-v2", mapOf("client_key" to item.getString("key"),"activity_type" to item.optString("activity_type","UNKNOWN"),"metadata_json" to item.optJSONObject("metadata")?.toString().orEmpty()), f, "file")
    }

    private class HttpFailure(val code:Int, message:String): RuntimeException(message)
    private fun conn(path:String, method:String): HttpURLConnection {
        val c=URL(serverUrl()+path).openConnection() as HttpURLConnection
        c.requestMethod=method; c.connectTimeout=8000; c.readTimeout=30000; c.setRequestProperty("Authorization","Bearer ${token()}"); c.setRequestProperty("Accept","application/json")
        return c
    }
    private fun putJson(path:String, body:JSONObject)=jsonRequest(path,"PUT",body)
    private fun postJson(path:String, body:JSONObject)=jsonRequest(path,"POST",body)
    private fun getJson(path:String):JSONObject {
        val c=conn(path,"GET"); return readResponse(c)
    }
    private fun jsonRequest(path:String, method:String, body:JSONObject):JSONObject {
        val c=conn(path,method); c.doOutput=true; c.setRequestProperty("Content-Type","application/json; charset=utf-8")
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        return readResponse(c)
    }
    private fun multipart(path:String, fields:Map<String,String>, file:File, fieldName:String):JSONObject {
        val boundary="----RCC${UUID.randomUUID()}"; val c=conn(path,"POST"); c.doOutput=true; c.setRequestProperty("Content-Type","multipart/form-data; boundary=$boundary")
        DataOutputStream(c.outputStream).use { out ->
            fun text(s:String){ out.write(s.toByteArray(Charsets.UTF_8)) }
            fields.forEach { (k,v) -> text("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n") }
            text("--$boundary\r\nContent-Disposition: form-data; name=\"$fieldName\"; filename=\"${file.name.replace("\"","")}\"\r\nContent-Type: application/octet-stream\r\n\r\n")
            file.inputStream().use { it.copyTo(out) }; text("\r\n--$boundary--\r\n")
        }
        return readResponse(c)
    }
    private fun readResponse(c:HttpURLConnection):JSONObject {
        val code=c.responseCode; val stream=if(code in 200..299)c.inputStream else c.errorStream
        val text=stream?.let { BufferedReader(InputStreamReader(it,Charsets.UTF_8)).use { r->r.readText() } }.orEmpty()
        if(code !in 200..299) throw HttpFailure(code,"HTTP $code · ${text.take(180)}")
        return if(text.isBlank()) JSONObject() else JSONObject(text)
    }
}
