package com.seungjae.jangsu280battery

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.random.Random

class RoadGranfondoActivity : Activity(), LocationListener {
    companion object {
        private const val REQ_GPX = 301
        private const val REQ_FIT = 302
        private const val REQ_LOCATION = 303
        private const val PREFS = "road_granfondo_ui_v1"
        private const val KEY_COURSE_ID = "road_course_id"
        private const val KEY_TARGET = "target_time"
        private const val KEY_RELAY = "group_relay"
        private const val KEY_ROOM = "group_room"
        private const val KEY_NICK = "group_nick"
        private const val KEY_RIDER_ID = "group_rider_id"
    }

    private lateinit var courseRepo: CourseRepository
    private lateinit var profileStore: RoadProfileStore
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private lateinit var locationManager: LocationManager

    private lateinit var tvCourse: TextView
    private lateinit var tvProfile: TextView
    private lateinit var etTarget: EditText
    private lateinit var tvPlan: TextView
    private lateinit var tvSchedule: TextView
    private lateinit var btnRide: Button
    private lateinit var tvLive: TextView
    private lateinit var etRelay: EditText
    private lateinit var etRoom: EditText
    private lateinit var etNick: EditText
    private lateinit var btnGroup: Button
    private lateinit var tvGroup: TextView

    private var course: CourseData? = null
    private var plan: RoadPlan? = null
    private var matcher: RouteMatcher? = null
    private var riding = false
    private var rideStartMs = 0L
    private var lastRouteKm = 0.0
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var lastSpeedKph = 0.0
    private var groupEnabled = false
    private var lastGroupSyncMs = 0L
    private var groupSyncBusy = false
    private var pendingStravaAnalysis = false
    private var stravaAnalysisBusy = false
    private val riderId: String by lazy {
        prefs.getString(KEY_RIDER_ID, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_RIDER_ID, it).apply() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_road_granfondo)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        courseRepo = CourseRepository(this)
        profileStore = RoadProfileStore(this)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        tvCourse = findViewById(R.id.tvRoadCourse)
        tvProfile = findViewById(R.id.tvRoadProfile)
        etTarget = findViewById(R.id.etRoadTargetTime)
        tvPlan = findViewById(R.id.tvRoadPlan)
        tvSchedule = findViewById(R.id.tvRoadSchedule)
        btnRide = findViewById(R.id.btnRoadRideToggle)
        tvLive = findViewById(R.id.tvRoadLive)
        etRelay = findViewById(R.id.etRoadGroupRelay)
        etRoom = findViewById(R.id.etRoadGroupRoom)
        etNick = findViewById(R.id.etRoadGroupNick)
        btnGroup = findViewById(R.id.btnRoadGroupToggle)
        tvGroup = findViewById(R.id.tvRoadGroup)

        etTarget.setText(prefs.getString(KEY_TARGET, "06:00"))
        etRelay.setText(prefs.getString(KEY_RELAY, ""))
        etRoom.setText(prefs.getString(KEY_ROOM, ""))
        etNick.setText(prefs.getString(KEY_NICK, "승재"))

        findViewById<Button>(R.id.btnRoadBackMode).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnRoadImportGpx).setOnClickListener { pickGpx() }
        findViewById<Button>(R.id.btnRoadStravaAnalyze).setOnClickListener { startStravaRoadAnalysis() }
        findViewById<Button>(R.id.btnRoadFtp).setOnClickListener { showFtpDialog() }
        findViewById<Button>(R.id.btnRoadBuildPlan).setOnClickListener { buildPlan(showToast = true) }
        findViewById<Button>(R.id.btnRoadSimulator).setOnClickListener {
            startActivity(Intent(this, RoadRaceSimulationActivity::class.java))
        }
        btnRide.setOnClickListener { if (riding) stopRide() else startRide() }
        findViewById<Button>(R.id.btnRoadGroupMakeRoom).setOnClickListener {
            val code = String.format(Locale.US, "%06d", Random.nextInt(0, 1_000_000))
            etRoom.setText(code)
            prefs.edit().putString(KEY_ROOM, code).apply()
            tvGroup.text = "방 코드 $code 생성 · 최대 20명 · 팀원에게 같은 코드를 알려주세요."
        }
        btnGroup.setOnClickListener { toggleGroup() }

        loadRoadCourse()
        refreshProfile()
        if (course != null) buildPlan(false)
    }

    override fun onPause() {
        if (!riding) runCatching { locationManager.removeUpdates(this) }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (pendingStravaAnalysis) {
            val store = StravaSecureStore(this)
            if (store.isConnected() && store.hasActivityRead()) {
                pendingStravaAnalysis = false
                runStravaRoadAnalysis()
            }
        }
    }

    private fun pickGpx() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream"))
        }
        startActivityForResult(i, REQ_GPX)
    }

    private fun pickFits() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(i, REQ_FIT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == REQ_GPX) {
            val uri = data.data ?: return
            val oldId = runCatching { courseRepo.activeMeta().id }.getOrNull()
            Thread {
                val result = runCatching {
                    val meta = courseRepo.importGpx(uri, displayName(uri))
                    if (oldId != null && oldId != meta.id) runCatching { courseRepo.setActive(oldId) }
                    prefs.edit().putString(KEY_COURSE_ID, meta.id).apply()
                    courseRepo.loadCourse(meta.id)
                }
                runOnUiThread {
                    result.onSuccess {
                        course = it; matcher = RouteMatcher(it); refreshCourse(); buildPlan(false)
                        Toast.makeText(this, "로드 GPX를 불러왔습니다.", Toast.LENGTH_SHORT).show()
                    }.onFailure { Toast.makeText(this, "GPX 오류: ${it.message}", Toast.LENGTH_LONG).show() }
                }
            }.start()
        } else if (requestCode == REQ_FIT) {
            val uris = mutableListOf<Uri>()
            data.data?.let(uris::add)
            data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
            if (uris.isEmpty()) return
            tvProfile.text = "FIT ${uris.size}개 분석 중…"
            Thread {
                var ok = 0
                val errors = mutableListOf<String>()
                uris.distinct().forEach { uri ->
                    runCatching { HistoricalRideImporter.analyze(this, uri, HistoricalSourceType.FIT) }
                        .onSuccess { profileStore.addFit(it); ok++ }
                        .onFailure { errors += (it.message ?: "분석 실패") }
                }
                runOnUiThread {
                    refreshProfile(); buildPlan(false)
                    Toast.makeText(this, "FIT ${ok}개 반영${if (errors.isNotEmpty()) " · 실패 ${errors.size}" else ""}", Toast.LENGTH_LONG).show()
                }
            }.start()
        }
    }

    private fun showFtpDialog() {
        val oldFtp = profileStore.load().power.sixtyMinuteW
        val input = EditText(this).apply {
            hint = "FTP W · 예: 210"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(oldFtp?.toInt()?.toString() ?: "")
            setPadding(42, 10, 42, 0)
        }
        AlertDialog.Builder(this)
            .setTitle("FTP 직접 입력")
            .setMessage("Strava를 사용하지 않는 경우 FTP 하나만 입력합니다. 시뮬레이터는 FTP + GPX 경사로 기본 능력 프로필을 만듭니다.")
            .setView(input)
            .setPositiveButton("저장") { _, _ ->
                val ftp = input.text.toString().trim().toDoubleOrNull()
                if (ftp == null || ftp !in 50.0..700.0) {
                    Toast.makeText(this, "FTP는 50~700W 범위로 입력해 주세요.", Toast.LENGTH_LONG).show()
                } else {
                    profileStore.saveFtpOnly(ftp)
                    refreshProfile(); buildPlan(false)
                    Toast.makeText(this, "FTP ${ftp.toInt()}W 저장", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun startStravaRoadAnalysis() {
        if (stravaAnalysisBusy) return
        val store = StravaSecureStore(this)
        if (!store.isConnected() || !store.hasActivityRead()) {
            pendingStravaAnalysis = true
            Toast.makeText(this, "Strava 연결 화면에서 ROAD 읽기 권한을 승인해 주세요. 승인 후 자동으로 분석을 시작합니다.", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, StravaActivity::class.java))
            return
        }
        runStravaRoadAnalysis()
    }

    private fun runStravaRoadAnalysis() {
        if (stravaAnalysisBusy) return
        stravaAnalysisBusy = true
        tvProfile.text = "Strava ROAD 기록 목록 불러오는 중…"
        Thread {
            val result = runCatching {
                val store = StravaSecureStore(this)
                val token = StravaClient.ensureAccessToken(store)
                StravaRoadAnalyzer.analyze(token) { done, total, name ->
                    runOnUiThread {
                        if (stravaAnalysisBusy) tvProfile.text = "Strava ROAD 분석 $done/$total · $name"
                    }
                }
            }
            runOnUiThread {
                stravaAnalysisBusy = false
                result.onSuccess { analyzed ->
                    profileStore.replaceWithStrava(analyzed)
                    refreshProfile(); buildPlan(false)
                    Toast.makeText(this, "Strava ROAD ${analyzed.activityCount}개 분석 완료", Toast.LENGTH_LONG).show()
                }.onFailure { e ->
                    refreshProfile()
                    val message = e.message ?: e.javaClass.simpleName
                    Toast.makeText(this, "Strava 분석 실패 · $message", Toast.LENGTH_LONG).show()
                    if (message.contains("401") || message.contains("403") || message.contains("권한")) {
                        pendingStravaAnalysis = true
                        startActivity(Intent(this, StravaActivity::class.java))
                    }
                }
            }
        }.start()
    }

    private fun loadRoadCourse() {
        val id = prefs.getString(KEY_COURSE_ID, null)
        course = id?.let { runCatching { courseRepo.loadCourse(it) }.getOrNull() }
        matcher = course?.let { RouteMatcher(it) }
        refreshCourse()
    }

    private fun refreshCourse() {
        val c = course
        tvCourse.text = if (c == null) "로드 코스 없음 · GPX를 선택해 주세요." else buildString {
            append(c.name)
            append("\n거리 ${one(c.totalKm)} km · 획득고도 ${c.totalAscentM.toInt()} m")
            append(" · 포인트 ${c.pois.size}개")
            append("\n코스키 ${RoadGranfondoEngine.courseKey(c)} · 그룹원은 같은 GPX를 사용해야 앞/뒤 비교가 정확합니다.")
        }
    }

    private fun refreshProfile() {
        val p = profileStore.load()
        tvProfile.text = buildString {
            when {
                p.stravaActivityCount > 0 -> append("Strava ROAD ${p.stravaActivityCount}개 분석")
                p.fitCount > 0 -> append("학습 FIT ${p.fitCount}개")
                p.powerSource == "ftp" && p.power.sixtyMinuteW != null -> append("FTP 직접 입력 ${p.power.sixtyMinuteW.toInt()}W")
                else -> append("Strava 분석 또는 FTP 입력")
            }
            p.overallSpeedKph()?.let { append(" · 로드 평속 ${one(it)} km/h") }
            if (p.powerSource == "strava") {
                val vals = listOfNotNull(
                    p.power.fiveSecondW?.let { "5초 ${it.toInt()}W" },
                    p.power.thirtySecondW?.let { "30초 ${it.toInt()}W" },
                    p.power.oneMinuteW?.let { "1분 ${it.toInt()}W" },
                    p.power.fiveMinuteW?.let { "5분 ${it.toInt()}W" },
                    p.power.twentyMinuteW?.let { "20분 ${it.toInt()}W" },
                    p.power.sixtyMinuteW?.let { "60분 ${it.toInt()}W" }
                )
                if (vals.isNotEmpty()) append("\nPR · ${vals.joinToString(" · ")}")
            } else if (p.power.sixtyMinuteW != null) {
                append("\nFTP 기준 지속파워 ${p.power.sixtyMinuteW.toInt()}W")
            }
            if (p.importedNames.isNotEmpty()) append("\n최근: ${p.importedNames.takeLast(2).joinToString(" · ")}")
        }
    }

    private fun buildPlan(showToast: Boolean): Boolean {
        val c = course ?: run { if (showToast) Toast.makeText(this, "먼저 GPX 코스를 넣어 주세요.", Toast.LENGTH_SHORT).show(); return false }
        val targetText = etTarget.text.toString().trim()
        val targetSec = parseTargetSeconds(targetText)
        if (targetText.isNotBlank() && targetSec == null) {
            if (showToast) Toast.makeText(this, "목표시간은 06:30처럼 입력해 주세요.", Toast.LENGTH_SHORT).show()
            return false
        }
        prefs.edit().putString(KEY_TARGET, targetText).apply()
        val p = profileStore.load()
        val built = runCatching { RoadGranfondoEngine.buildPlan(c, targetSec, p) }.getOrElse {
            if (showToast) Toast.makeText(this, "계획 생성 실패: ${it.message}", Toast.LENGTH_LONG).show(); return false
        }
        plan = built
        tvPlan.text = buildString {
            append(if (built.targetSpecified) "목표 완주 ${duration(built.totalSec)}" else "예상 완주 ${duration(built.totalSec)}")
            append(" · ${built.modelLabel}")
            append("\nGPX 고도와 내 기록으로 구간 난이도를 배분한 뒤${if (built.targetSpecified) " 전체 시간을 목표 완주시간에 맞춰 스케일" else " 예상시간 계산"}합니다.")
        }
        renderSchedule(built, null)
        if (showToast) Toast.makeText(this, "그란폰도 통과 계획을 만들었습니다.", Toast.LENGTH_SHORT).show()
        return true
    }


    private fun renderSchedule(roadPlan: RoadPlan, startMs: Long?) {
        tvSchedule.text = roadPlan.checkpoints.joinToString("\n") { cp ->
            if (startMs == null) {
                String.format(Locale.US, "%6.1f km  %s  %s", cp.km, duration(cp.targetElapsedSec), cp.name)
            } else {
                val at = startMs + (cp.targetElapsedSec * 1000.0).toLong()
                String.format(Locale.US, "%6.1f km  %s  %s  (+%s)", cp.km, clock(at), cp.name, duration(cp.targetElapsedSec))
            }
        }
    }

    private fun startRide() {
        if (plan == null && !buildPlan(true)) return
        if (!hasLocationPermission()) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
            return
        }
        riding = true
        rideStartMs = System.currentTimeMillis()
        lastRouteKm = 0.0
        matcher = course?.let { RouteMatcher(it) }
        btnRide.text = "■ 주행 종료"
        plan?.let { renderSchedule(it, rideStartMs) }
        tvLive.text = "GPS 대기 · 목표 시각표를 시작했습니다."
        requestLocation()
    }

    private fun stopRide() {
        riding = false
        groupEnabled = false
        runCatching { locationManager.removeUpdates(this) }
        btnRide.text = "▶ 주행 시작"
        btnGroup.text = "그룹 연결"
        tvLive.append("\n주행 종료")
    }

    private fun requestLocation() {
        if (!hasLocationPermission()) return
        runCatching { locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1500L, 3f, this) }
            .onFailure { Toast.makeText(this, "GPS 시작 실패: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    override fun onLocationChanged(location: Location) {
        if (!riding) return
        if (location.accuracy > 60f) return
        val m = matcher?.match(location.latitude, location.longitude, location.accuracy) ?: return
        if (!m.gpsHeld) lastRouteKm = m.routeKm
        lastLat = location.latitude
        lastLon = location.longitude
        lastSpeedKph = (location.speed * 3.6).coerceAtLeast(0.0)
        renderLive(location, m)
        maybeSyncGroup()
    }

    private fun renderLive(location: Location, match: MatchResult) {
        val p = plan ?: return
        val now = System.currentTimeMillis()
        val actualSec = ((now - rideStartMs).coerceAtLeast(0L) / 1000.0)
        val expectedSec = p.expectedElapsedSec(lastRouteKm)
        val delta = actualSec - expectedSec
        val next = p.nextCheckpoint(lastRouteKm)
        val predictedFinishMs = rideStartMs + ((p.totalSec + delta) * 1000.0).toLong()
        tvLive.text = buildString {
            append("현재 ${one(lastRouteKm)} km · ${one(lastSpeedKph)} km/h")
            if (match.gpsHeld) append(" · GPS 진행거리 보류")
            append("\n")
            when {
                abs(delta) < 15 -> append("목표 페이스 정확")
                delta < 0 -> append("목표보다 ${duration(-delta)} 빠름")
                else -> append("목표보다 ${duration(delta)} 늦음")
            }
            append(" · 예상 FINISH ${clock(predictedFinishMs)}")
            if (next != null) {
                val targetClock = rideStartMs + (next.targetElapsedSec * 1000).toLong()
                append("\n다음 ${next.name} ${one(next.km)} km · 통과목표 ${clock(targetClock)}")
                append(" · 남은 ${one((next.km - lastRouteKm).coerceAtLeast(0.0))} km")
            }
            append("\nGPS ±${location.accuracy.toInt()}m · 코스 이격 ${match.offCourseMeters.toInt()}m")
        }
    }

    private fun toggleGroup() {
        val relay = etRelay.text.toString().trim()
        val room = etRoom.text.toString().trim()
        val nick = etNick.text.toString().trim().ifBlank { "라이더" }
        if (!groupEnabled) {
            if (relay.isBlank() || room.isBlank()) {
                Toast.makeText(this, "그룹 릴레이 URL과 방 코드를 입력해 주세요.", Toast.LENGTH_LONG).show(); return
            }
            if (!riding) {
                Toast.makeText(this, "먼저 로드 주행을 시작해 주세요.", Toast.LENGTH_SHORT).show(); return
            }
            prefs.edit().putString(KEY_RELAY, relay).putString(KEY_ROOM, room).putString(KEY_NICK, nick).apply()
            groupEnabled = true
            btnGroup.text = "그룹 끄기"
            tvGroup.text = "그룹 연결 시작 · 약 10초 간격 위치 공유"
            maybeSyncGroup(force = true)
        } else {
            groupEnabled = false
            btnGroup.text = "그룹 연결"
            tvGroup.text = "그룹 위치 공유 중지"
        }
    }

    private fun maybeSyncGroup(force: Boolean = false) {
        if (!groupEnabled || groupSyncBusy) return
        val now = System.currentTimeMillis()
        if (!force && now - lastGroupSyncMs < 10_000L) return
        val c = course ?: return
        val relay = etRelay.text.toString().trim()
        val room = etRoom.text.toString().trim()
        val nick = etNick.text.toString().trim().ifBlank { "라이더" }
        if (relay.isBlank() || room.isBlank()) return
        groupSyncBusy = true
        lastGroupSyncMs = now
        val self = GroupRider(riderId, nick, RoadGranfondoEngine.courseKey(c), lastRouteKm, lastLat, lastLon, lastSpeedKph, now)
        Thread {
            val result = runCatching { GroupRideClient(relay).sync(room, self) }
            runOnUiThread {
                groupSyncBusy = false
                result.onSuccess { renderGroup(it, self) }.onFailure { tvGroup.text = "그룹 연결 오류: ${it.message}" }
            }
        }.start()
    }

    private fun renderGroup(riders: List<GroupRider>, self: GroupRider) {
        val freshCutoff = System.currentTimeMillis() - 60_000L
        val same = riders.filter { it.riderId != self.riderId && it.courseKey == self.courseKey && it.updatedMs >= freshCutoff }
            .sortedByDescending { it.routeKm }
        tvGroup.text = if (same.isEmpty()) "같은 코스의 최근 60초 팀원 위치 없음 · 방 최대 20명" else buildString {
            append("팀원 ${same.size}명 + 나 · 최대 20명 · 내 위치 ${one(self.routeKm)} km\n")
            same.forEachIndexed { i, r ->
                val d = r.routeKm - self.routeKm
                val pos = if (abs(d) < 0.05) "거의 같이" else if (d > 0) "앞 +${one(d)} km" else "뒤 ${one(abs(d))} km"
                append("${r.nickname}: $pos · ${one(r.routeKm)} km")
                if (i < same.lastIndex) append("\n")
            }
        }
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Suppress("DEPRECATION")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) startRide()
    }

    private fun hasLocationPermission(): Boolean = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun parseTargetSeconds(text: String): Double? {
        if (text.isBlank()) return null
        val parts = text.split(":")
        return when (parts.size) {
            1 -> parts[0].toDoubleOrNull()?.times(3600.0)
            2 -> {
                val h = parts[0].toIntOrNull() ?: return null
                val m = parts[1].toIntOrNull() ?: return null
                if (h < 0 || m !in 0..59) null else (h * 3600 + m * 60).toDouble()
            }
            else -> null
        }
    }

    private fun duration(secRaw: Double): String {
        val sec = secRaw.toLong().coerceAtLeast(0)
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun clock(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(ms))
    private fun one(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun EditText.num(): Double? = text.toString().trim().toDoubleOrNull()?.takeIf { it > 0.0 }
    private fun displayName(uri: Uri): String {
        var resolved: String? = null
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) resolved = c.getString(i)
            }
        }
        return resolved ?: uri.lastPathSegment?.substringAfterLast('/') ?: "로드 GPX"
    }
}
