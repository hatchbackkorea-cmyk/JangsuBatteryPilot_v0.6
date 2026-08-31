package com.seungjae.jangsu280battery

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.AdapterView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.view.View
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
        private const val REQ_LOCATION = 303
        private const val REQ_PDF = 304
        private const val PREFS = "road_granfondo_ui_v1"
        private const val KEY_COURSE_ID = "road_course_id"
        private const val KEY_TARGET_HOUR = "target_hour"
        private const val KEY_TARGET_MINUTE = "target_minute"
        private const val KEY_TARGET_SPEED_INDEX = "target_speed_index"
        private const val KEY_PLAN_BASIS = "plan_basis"
        private const val KEY_START_HOUR = "start_hour"
        private const val KEY_START_MINUTE = "start_minute"
        private const val KEY_RELAY = "group_relay"
        private const val KEY_ROOM = "group_room"
        private const val KEY_NICK = "group_nick"
        private const val KEY_RIDER_ID = "group_rider_id"
        private val STOP_MINUTES = (0..60).toList()
        private val TARGET_HOURS = (0..20).toList()
        private val CLOCK_HOURS = (0..23).toList()
        private val MINUTES = (0..59).toList()
        private val TARGET_SPEEDS = (100..500 step 5).map { it / 10.0 }
    }

    private data class AidRow(val poi: RoutePoi, val check: CheckBox, val spinner: Spinner)

    private lateinit var courseRepo: CourseRepository
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private lateinit var locationManager: LocationManager

    private lateinit var tvCourse: TextView
    private lateinit var rgBasis: RadioGroup
    private lateinit var rbTargetTime: RadioButton
    private lateinit var rbTargetSpeed: RadioButton
    private lateinit var llTargetTimeInput: LinearLayout
    private lateinit var llTargetSpeedInput: LinearLayout
    private lateinit var spTargetHour: Spinner
    private lateinit var spTargetMinute: Spinner
    private lateinit var spTargetSpeed: Spinner
    private lateinit var spStartHour: Spinner
    private lateinit var spStartMinute: Spinner
    private lateinit var tvBasisPreview: TextView
    private lateinit var aidContainer: LinearLayout
    private lateinit var tvPlan: TextView
    private lateinit var tvSchedule: TextView
    private lateinit var btnRide: Button
    private lateinit var tvLive: TextView
    private lateinit var etRelay: EditText
    private lateinit var etRoom: EditText
    private lateinit var etNick: EditText
    private lateinit var btnGroup: Button
    private lateinit var tvGroup: TextView

    private val aidRows = mutableListOf<AidRow>()
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
    private var syncingInputs = false
    private var pendingPdfPlan: RoadPlan? = null
    private val riderId: String by lazy {
        prefs.getString(KEY_RIDER_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_RIDER_ID, it).apply()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_road_granfondo)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        courseRepo = CourseRepository(this)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        tvCourse = findViewById(R.id.tvRoadCourse)
        rgBasis = findViewById(R.id.rgRoadPlanBasis)
        rbTargetTime = findViewById(R.id.rbRoadTargetTime)
        rbTargetSpeed = findViewById(R.id.rbRoadTargetSpeed)
        llTargetTimeInput = findViewById(R.id.llRoadTargetTimeInput)
        llTargetSpeedInput = findViewById(R.id.llRoadTargetSpeedInput)
        spTargetHour = findViewById(R.id.spRoadTargetHour)
        spTargetMinute = findViewById(R.id.spRoadTargetMinute)
        spTargetSpeed = findViewById(R.id.spRoadTargetSpeed)
        spStartHour = findViewById(R.id.spRoadStartHour)
        spStartMinute = findViewById(R.id.spRoadStartMinute)
        tvBasisPreview = findViewById(R.id.tvRoadBasisPreview)
        aidContainer = findViewById(R.id.llRoadAidStations)
        tvPlan = findViewById(R.id.tvRoadPlan)
        tvSchedule = findViewById(R.id.tvRoadSchedule)
        btnRide = findViewById(R.id.btnRoadRideToggle)
        tvLive = findViewById(R.id.tvRoadLive)
        etRelay = findViewById(R.id.etRoadGroupRelay)
        etRoom = findViewById(R.id.etRoadGroupRoom)
        etNick = findViewById(R.id.etRoadGroupNick)
        btnGroup = findViewById(R.id.btnRoadGroupToggle)
        tvGroup = findViewById(R.id.tvRoadGroup)

        setupPlanInputSpinners()
        etRelay.setText(prefs.getString(KEY_RELAY, ""))
        etRoom.setText(prefs.getString(KEY_ROOM, ""))
        etNick.setText(prefs.getString(KEY_NICK, "승재"))

        findViewById<Button>(R.id.btnRoadBackMode).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnRoadImportGpx).setOnClickListener { pickGpx() }
        findViewById<Button>(R.id.btnRoadBuildPlan).setOnClickListener { buildTargetPlan(showToast = true) }
        findViewById<Button>(R.id.btnRoadSavePdf).setOnClickListener { savePlanPdf() }
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
    }

    override fun onPause() {
        if (!riding) runCatching { locationManager.removeUpdates(this) }
        super.onPause()
    }

    private fun pickGpx() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream"))
        }
        startActivityForResult(i, REQ_GPX)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PDF) {
            if (resultCode == RESULT_OK) data?.data?.let { writePendingPdf(it) }
            return
        }
        if (requestCode != REQ_GPX || resultCode != RESULT_OK || data == null) return
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
                    course = it
                    matcher = RouteMatcher(it)
                    plan = null
                    refreshCourse()
                    renderAidStationRows()
                    tvPlan.text = "목표 주행시간 또는 평속과 보급소 정차시간을 설정해 주세요."
                    tvSchedule.text = ""
                    Toast.makeText(this, "로드 GPX를 불러왔습니다.", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(this, "GPX 오류: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun loadRoadCourse() {
        val id = prefs.getString(KEY_COURSE_ID, null)
        course = id?.let { runCatching { courseRepo.loadCourse(it) }.getOrNull() }
        matcher = course?.let { RouteMatcher(it) }
        refreshCourse()
        renderAidStationRows()
    }

    private fun refreshCourse() {
        val c = course
        tvCourse.text = if (c == null) {
            "로드 코스 없음 · GPX를 선택해 주세요."
        } else buildString {
            append(c.name)
            append("\n거리 ${one(c.totalKm)} km · 획득고도 ${c.totalAscentM.toInt()} m")
            append(" · 보급/급수 ${c.supplyPois.size}곳")
            append("\n코스키 ${RoadGranfondoEngine.courseKey(c)} · 그룹원은 같은 GPX를 사용해야 앞/뒤 비교가 정확합니다.")
        }
        updateBasisPreview()
    }

    private fun renderAidStationRows() {
        aidContainer.removeAllViews()
        aidRows.clear()
        val c = course
        if (c == null) {
            aidContainer.addView(hintText("GPX를 선택하면 보급소 목록이 표시됩니다."))
            return
        }
        val aids = c.supplyPois.sortedBy { it.routeKm }
        if (aids.isEmpty()) {
            aidContainer.addView(hintText("GPX에서 보급/급수 포인트를 찾지 못했습니다. 정차 없이 목표시간 계획을 만들 수 있습니다."))
            return
        }
        val courseKey = RoadGranfondoEngine.courseKey(c)
        aids.forEach { poi ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 4, 0, 4)
            }
            val suffix = aidPrefSuffix(courseKey, poi)
            val checked = prefs.getBoolean("aid_${suffix}_checked", false)
            val savedMin = prefs.getInt("aid_${suffix}_min", 5).coerceIn(0, 60)
            val check = CheckBox(this).apply {
                text = "${one(poi.routeKm)}km  ${poi.name.ifBlank { "보급소" }}"
                isChecked = checked
                setTextColor(getColor(R.color.text_primary))
                textSize = 12f
            }
            val spinner = Spinner(this)
            val labels = STOP_MINUTES.map { "${it}분" }
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
            spinner.setSelection(savedMin)
            spinner.isEnabled = checked
            check.setOnCheckedChangeListener { _, isChecked -> spinner.isEnabled = isChecked }
            row.addView(check, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(spinner, LinearLayout.LayoutParams(dp(104), LinearLayout.LayoutParams.WRAP_CONTENT))
            aidContainer.addView(row)
            aidRows += AidRow(poi, check, spinner)
        }
    }

    private fun selectedAids(): List<RoadAidSelection> {
        val c = course ?: return emptyList()
        val courseKey = RoadGranfondoEngine.courseKey(c)
        return aidRows.mapNotNull { row ->
            val min = STOP_MINUTES.getOrElse(row.spinner.selectedItemPosition) { 0 }
            val suffix = aidPrefSuffix(courseKey, row.poi)
            prefs.edit()
                .putBoolean("aid_${suffix}_checked", row.check.isChecked)
                .putInt("aid_${suffix}_min", min)
                .apply()
            if (!row.check.isChecked || min <= 0) null
            else RoadAidSelection(row.poi.name.ifBlank { "보급소" }, row.poi.routeKm, min * 60.0)
        }
    }

    private fun buildTargetPlan(showToast: Boolean): Boolean {
        val c = course ?: run {
            if (showToast) Toast.makeText(this, "먼저 GPX 코스를 넣어 주세요.", Toast.LENGTH_SHORT).show()
            return false
        }
        val ridingTargetSec = selectedRidingTargetSeconds(c) ?: run {
            if (showToast) Toast.makeText(this, "목표 주행시간 또는 목표 평속을 확인해 주세요.", Toast.LENGTH_SHORT).show()
            return false
        }
        savePlanInputPrefs()
        val aids = selectedAids()
        val built = runCatching { RoadGranfondoEngine.buildTargetPlan(c, ridingTargetSec, aids) }.getOrElse {
            if (showToast) Toast.makeText(this, "목표 일정 생성 실패: ${it.message}", Toast.LENGTH_LONG).show()
            return false
        }
        plan = built
        val stopSec = built.totalStopSec
        val ridingAvg = c.totalKm / (built.ridingTargetSec / 3600.0)
        val overallAvg = c.totalKm / (built.totalSec / 3600.0)
        val startMin = selectedStartMinuteOfDay()
        val basis = if (rbTargetSpeed.isChecked) "목표 평속 ${one(ridingAvg)} km/h" else "목표 주행시간 ${duration(built.ridingTargetSec)}"
        tvPlan.text = buildString {
            append("🎯 $basis")
            append(" · 출발 ${clockOfDay(startMin, 0.0)}")
            append("\n순수 주행 ${duration(built.ridingTargetSec)} · 주행평속 ${one(ridingAvg)} km/h")
            if (built.aidStops.isEmpty()) append(" · 보급 정차 없음")
            else append("\n보급 ${built.aidStops.size}곳 · 총 정차 ${duration(stopSec)}")
            append("\n🏁 계획 완주 ${duration(built.totalSec)} · ${clockOfDay(startMin, built.totalSec)} 도착")
            append(" · 정차포함 평균 ${one(overallAvg)} km/h")
            append("\n보급시간은 목표 주행시간과 별도로 더해 계산했습니다.")
        }
        renderSchedule(built, startMin)
        updateBasisPreview()
        if (showToast) Toast.makeText(this, "목표 페이스 일정을 만들었습니다.", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun renderSchedule(roadPlan: RoadPlan, startMinuteOfDay: Int) {
        tvSchedule.text = roadPlan.checkpoints.joinToString("\n") { cp ->
            val arrival = clockOfDay(startMinuteOfDay, cp.targetElapsedSec)
            if (cp.stopSec > 0.0) {
                val departure = clockOfDay(startMinuteOfDay, cp.targetElapsedSec + cp.stopSec)
                String.format(Locale.US, "%6.1f km  %s 도착 → %s 출발  %s", cp.km, arrival, departure, cp.name)
            } else {
                String.format(Locale.US, "%6.1f km  %s  %s  (+%s)", cp.km, arrival, cp.name, duration(cp.targetElapsedSec))
            }
        }
    }

    private fun startRide() {
        // 입력값이나 보급소 선택이 바뀌었을 수 있으므로 시작 직전에 항상 다시 만든다.
        if (!buildTargetPlan(true)) return
        if (!hasLocationPermission()) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
            return
        }
        riding = true
        rideStartMs = selectedStartEpochMs()
        lastRouteKm = 0.0
        matcher = course?.let { RouteMatcher(it) }
        btnRide.text = "■ 주행 종료"
        plan?.let { renderSchedule(it, selectedStartMinuteOfDay()) }
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
        val actualSec = (now - rideStartMs).coerceAtLeast(0L) / 1000.0
        val plannedStop = p.aidStops.firstOrNull {
            abs(it.km - lastRouteKm) <= 0.15 && actualSec in it.arrivalElapsedSec..it.departureElapsedSec
        }
        val expectedSec = p.expectedElapsedSec(lastRouteKm)
        val delta = actualSec - expectedSec
        val next = p.nextCheckpoint(lastRouteKm)
        val predictedFinishMs = rideStartMs + ((p.totalSec + if (plannedStop == null) delta else 0.0) * 1000.0).toLong()
        tvLive.text = buildString {
            append("현재 ${one(lastRouteKm)} km · ${one(lastSpeedKph)} km/h")
            if (match.gpsHeld) append(" · GPS 진행거리 보류")
            append("\n")
            if (plannedStop != null) {
                append("${plannedStop.name} 계획 정차 · 출발까지 ${duration((plannedStop.departureElapsedSec - actualSec).coerceAtLeast(0.0))}")
            } else {
                when {
                    abs(delta) < 15 -> append("목표 페이스 정확")
                    delta < 0 -> append("목표보다 ${duration(-delta)} 빠름")
                    else -> append("목표보다 ${duration(delta)} 늦음")
                }
            }
            append(" · FINISH ${clock(predictedFinishMs)}")
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

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun setupPlanInputSpinners() {
        fun <T> bind(spinner: Spinner, values: List<T>, label: (T) -> String) {
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values.map(label))
        }
        bind(spTargetHour, TARGET_HOURS) { it.toString() }
        bind(spTargetMinute, MINUTES) { String.format(Locale.US, "%02d", it) }
        bind(spStartHour, CLOCK_HOURS) { String.format(Locale.US, "%02d", it) }
        bind(spStartMinute, MINUTES) { String.format(Locale.US, "%02d", it) }
        bind(spTargetSpeed, TARGET_SPEEDS) { String.format(Locale.US, "%.1f", it) }

        val oldTarget = prefs.getString("target_time", null)?.split(":")
        val savedH = prefs.getInt(KEY_TARGET_HOUR, oldTarget?.getOrNull(0)?.toIntOrNull() ?: 5).coerceIn(0, 20)
        val savedM = prefs.getInt(KEY_TARGET_MINUTE, oldTarget?.getOrNull(1)?.toIntOrNull() ?: 0).coerceIn(0, 59)
        spTargetHour.setSelection(savedH)
        spTargetMinute.setSelection(savedM)
        spTargetSpeed.setSelection(prefs.getInt(KEY_TARGET_SPEED_INDEX, TARGET_SPEEDS.indexOfFirst { it >= 25.0 }).coerceIn(TARGET_SPEEDS.indices))
        val now = java.util.Calendar.getInstance()
        spStartHour.setSelection(prefs.getInt(KEY_START_HOUR, now.get(java.util.Calendar.HOUR_OF_DAY)).coerceIn(0, 23))
        spStartMinute.setSelection(prefs.getInt(KEY_START_MINUTE, now.get(java.util.Calendar.MINUTE)).coerceIn(0, 59))
        val basis = prefs.getString(KEY_PLAN_BASIS, "time") ?: "time"
        if (basis == "speed") rbTargetSpeed.isChecked = true else rbTargetTime.isChecked = true
        updateBasisVisibility()

        rgBasis.setOnCheckedChangeListener { _, _ ->
            if (!syncingInputs) { updateBasisVisibility(); updateBasisPreview() }
        }
        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!syncingInputs) updateBasisPreview()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        listOf(spTargetHour, spTargetMinute, spTargetSpeed, spStartHour, spStartMinute).forEach { it.onItemSelectedListener = listener }
    }

    private fun updateBasisVisibility() {
        llTargetTimeInput.visibility = if (rbTargetTime.isChecked) View.VISIBLE else View.GONE
        llTargetSpeedInput.visibility = if (rbTargetSpeed.isChecked) View.VISIBLE else View.GONE
    }

    private fun updateBasisPreview() {
        val c = course
        if (c == null || c.totalKm <= 0.1) {
            tvBasisPreview.text = "코스를 선택하면 목표시간과 평속을 서로 환산합니다."
            return
        }
        val sec = selectedRidingTargetSeconds(c) ?: return
        val avg = c.totalKm / (sec / 3600.0)
        if (rbTargetTime.isChecked) {
            syncingInputs = true
            val idx = TARGET_SPEEDS.indices.minByOrNull { kotlin.math.abs(TARGET_SPEEDS[it] - avg) } ?: 0
            spTargetSpeed.setSelection(idx)
            syncingInputs = false
            tvBasisPreview.text = "순수 주행 ${duration(sec)} → 목표 평속 약 ${one(avg)} km/h · 보급시간 별도"
        } else {
            val h = (sec / 3600.0).toInt().coerceIn(0, 20)
            val m = ((sec.toLong() % 3600L) / 60L).toInt().coerceIn(0, 59)
            syncingInputs = true
            spTargetHour.setSelection(h)
            spTargetMinute.setSelection(m)
            syncingInputs = false
            tvBasisPreview.text = "목표 평속 ${one(avg)} km/h → 순수 주행 약 ${duration(sec)} · 보급시간 별도"
        }
    }

    private fun selectedRidingTargetSeconds(c: CourseData): Double? {
        return if (rbTargetSpeed.isChecked) {
            val speed = TARGET_SPEEDS.getOrNull(spTargetSpeed.selectedItemPosition) ?: return null
            if (speed <= 1.0) null else (c.totalKm / speed * 3600.0).takeIf { it >= 600.0 }
        } else {
            val h = TARGET_HOURS.getOrNull(spTargetHour.selectedItemPosition) ?: 0
            val m = MINUTES.getOrNull(spTargetMinute.selectedItemPosition) ?: 0
            (h * 3600.0 + m * 60.0).takeIf { it >= 600.0 }
        }
    }

    private fun selectedStartMinuteOfDay(): Int {
        val h = CLOCK_HOURS.getOrNull(spStartHour.selectedItemPosition) ?: 0
        val m = MINUTES.getOrNull(spStartMinute.selectedItemPosition) ?: 0
        return h * 60 + m
    }

    private fun selectedStartEpochMs(): Long {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, selectedStartMinuteOfDay() / 60)
            set(java.util.Calendar.MINUTE, selectedStartMinuteOfDay() % 60)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private fun savePlanInputPrefs() {
        prefs.edit()
            .putInt(KEY_TARGET_HOUR, spTargetHour.selectedItemPosition)
            .putInt(KEY_TARGET_MINUTE, spTargetMinute.selectedItemPosition)
            .putInt(KEY_TARGET_SPEED_INDEX, spTargetSpeed.selectedItemPosition)
            .putString(KEY_PLAN_BASIS, if (rbTargetSpeed.isChecked) "speed" else "time")
            .putInt(KEY_START_HOUR, spStartHour.selectedItemPosition)
            .putInt(KEY_START_MINUTE, spStartMinute.selectedItemPosition)
            .apply()
    }

    private fun savePlanPdf() {
        if (!buildTargetPlan(false)) {
            Toast.makeText(this, "먼저 목표 페이스 일정을 만들어 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        pendingPdfPlan = plan
        val c = course ?: return
        val safe = c.name.replace(Regex("[^0-9A-Za-z가-힣._-]+"), "_").take(45).ifBlank { "ROAD_계획" }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, "${safe}_목표페이스계획.pdf")
        }
        startActivityForResult(intent, REQ_PDF)
    }

    private fun writePendingPdf(uri: Uri) {
        val c = course ?: return
        val p = pendingPdfPlan ?: plan ?: return
        val basis = if (rbTargetSpeed.isChecked) {
            "목표 평속 ${one(c.totalKm / (p.ridingTargetSec / 3600.0))} km/h"
        } else {
            "목표 주행시간 ${duration(p.ridingTargetSec)}"
        }
        val startMinute = selectedStartMinuteOfDay()
        Thread {
            val result = runCatching { RoadPlanPdfExporter.write(this, uri, c, p, startMinute, basis) }
            runOnUiThread {
                result.onSuccess { Toast.makeText(this, "고도그래프 포함 계획표 PDF를 저장했습니다.", Toast.LENGTH_LONG).show() }
                    .onFailure { Toast.makeText(this, "PDF 저장 실패: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun clockOfDay(startMinuteOfDay: Int, elapsedSec: Double): String {
        val total = startMinuteOfDay * 60L + elapsedSec.toLong()
        val day = ((total % 86400L) + 86400L) % 86400L
        val h = day / 3600L
        val m = (day % 3600L) / 60L
        val sec = day % 60L
        return if (sec == 0L) String.format(Locale.US, "%02d:%02d", h, m)
        else String.format(Locale.US, "%02d:%02d:%02d", h, m, sec)
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
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun hintText(textValue: String) = TextView(this).apply {
        text = textValue
        setTextColor(getColor(R.color.text_secondary))
        textSize = 11f
        setPadding(0, dp(5), 0, dp(5))
    }
    private fun aidPrefSuffix(courseKey: String, poi: RoutePoi): String =
        "${courseKey}_${(poi.routeKm * 1000).toInt()}"

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
