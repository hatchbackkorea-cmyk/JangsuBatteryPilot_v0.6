package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.database.Cursor
import android.location.Geocoder
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

class CourseActivity : Activity() {
    companion object {
        private const val REQ_GPX = 2001
        private const val REQ_EXPORT = 2002
    }

    private lateinit var repo: CourseRepository
    private lateinit var logManager: RideLogManager
    private lateinit var chargingStore: ChargingStationStore
    private lateinit var learningStore: BatteryLearningStore
    private lateinit var container: LinearLayout
    private lateinit var chargingContainer: LinearLayout
    private lateinit var tvActive: TextView
    private lateinit var tvChargingSummary: TextView
    private lateinit var btnImport: Button
    private lateinit var btnExportLast: Button
    private lateinit var btnWaypointCharge: Button
    private lateinit var btnAddCharge: Button
    private lateinit var btnAutoCharge: Button
    private var pendingExportFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_course)
        repo = CourseRepository(this)
        logManager = RideLogManager(this)
        chargingStore = ChargingStationStore(this)
        learningStore = BatteryLearningStore(this)
        container = findViewById(R.id.courseListContainer)
        chargingContainer = findViewById(R.id.chargingStationContainer)
        tvActive = findViewById(R.id.tvCourseMenuActive)
        tvChargingSummary = findViewById(R.id.tvChargingSummary)
        btnImport = findViewById(R.id.btnCourseImport)
        btnExportLast = findViewById(R.id.btnCourseExportLast)
        btnWaypointCharge = findViewById(R.id.btnWaypointCharge)
        btnAddCharge = findViewById(R.id.btnAddCharge)
        btnAutoCharge = findViewById(R.id.btnAutoCharge)

        findViewById<Button>(R.id.btnCourseBack).setOnClickListener { finish() }
        btnImport.setOnClickListener { importGpx() }
        btnExportLast.setOnClickListener { exportLastLog() }
        btnWaypointCharge.setOnClickListener { showWaypointPicker(repo.activeMeta()) }
        btnAddCharge.setOnClickListener { showAddStationOptions(repo.activeMeta()) }
        btnAutoCharge.setOnClickListener { addRecommendedStation(repo.activeMeta()) }
        renderCourses()
    }

    override fun onResume() {
        super.onResume()
        renderCourses()
    }

    private fun renderCourses() {
        val active = repo.activeMeta()
        val activeElev = if (active.hasElevation) "▲${active.totalAscentM.roundToInt()}m · ▼${active.totalDescentM.roundToInt()}m" else "고도 데이터 없음"
        tvActive.text = "현재 선택 · ${active.name}\n${RideFormatter.one(active.totalKm)} km · $activeElev"

        val riding = logManager.isActive()
        btnImport.isEnabled = !riding
        btnExportLast.isEnabled = logManager.lastZipFile() != null
        btnWaypointCharge.isEnabled = !riding
        btnAddCharge.isEnabled = !riding
        btnAutoCharge.isEnabled = !riding
        findViewById<TextView>(R.id.tvCourseMenuHint).text = if (riding) {
            "주행 중에는 코스와 충전 계획을 변경할 수 없습니다."
        } else {
            "GPX 코스를 선택하고 충전 지점을 설정하세요."
        }

        renderChargingPlan(active, riding)
        container.removeAllViews()
        repo.listCourses().forEach { meta -> container.addView(buildCourseRow(meta, active.id, riding)) }
    }

    private fun renderChargingPlan(meta: CourseMeta, riding: Boolean) {
        val course = runCatching { repo.loadCourse(meta.id) }.getOrNull() ?: return
        val stations = chargingStore.list(meta.id).filter { it.routeKm < course.totalKm }.sortedBy { it.routeKm }
        val waypointCount = course.pois.count { !it.userAdded }
        btnWaypointCharge.text = "웨이포인트 선택 ($waypointCount)"

        val recommended = if (stations.isEmpty()) BatteryPlan(course, learningStore).recommendedChargeKm(AppSettings.finishTarget(this)) else null
        tvChargingSummary.text = when {
            stations.isNotEmpty() -> "충전소 ${stations.size}개 · 배터리 판단은 다음 충전소 우선"
            recommended != null -> "충전소 없음 · 현재는 종점 기준 · 권장 검토 ${RideFormatter.one(recommended)}km 부근"
            else -> "충전소 없음 · 현재는 종점 기준"
        }
        btnAutoCharge.text = if (recommended != null) "⚡ 권장 ${RideFormatter.one(recommended)}km 추가" else "⚡ 자동 분석"

        chargingContainer.removeAllViews()
        if (stations.isEmpty()) {
            chargingContainer.addView(TextView(this).apply {
                text = if (waypointCount > 0) "GPX 웨이포인트에서 충전소를 선택하거나 직접 추가하세요." else "GPX 웨이포인트가 없습니다. 주소·거리·고도 프로필로 충전소를 추가할 수 있습니다."
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, dp(6), 0, dp(3))
            })
            return
        }
        stations.forEachIndexed { index, station -> chargingContainer.addView(buildStationRow(meta, station, index + 1, riding)) }
    }

    private fun buildStationRow(meta: CourseMeta, station: ChargingStation, number: Int, riding: Boolean): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), dp(9), dp(11), dp(9))
            setBackgroundResource(R.drawable.panel_bg)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) }
        }
        outer.addView(TextView(this).apply {
            text = "$number. ${station.name} · ${RideFormatter.one(station.routeKm)}km"
            textSize = 15f
            setTextColor(getColor(R.color.text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        outer.addView(TextView(this).apply {
            val off = if (station.distanceFromRouteM >= 80.0) " · 코스 ${station.distanceFromRouteM.roundToInt()}m" else ""
            val detour = if (station.detourKm > 0.01) " · 추가 ${RideFormatter.one(station.detourKm)}km" else ""
            text = "${station.sourceLabel()} · 충전 후 ${station.chargeToPct.roundToInt()}%$off$detour"
            textSize = 11f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(3), 0, dp(5))
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = "수정"
            isEnabled = !riding
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
            setOnClickListener { showStationEditor(meta, station) }
        })
        actions.addView(Button(this).apply {
            text = "삭제"
            isEnabled = !riding
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 0.75f).apply { marginStart = dp(7) }
            setOnClickListener {
                chargingStore.remove(meta.id, station.id)
                renderCourses()
            }
        })
        outer.addView(actions)
        return outer
    }

    private fun showWaypointPicker(meta: CourseMeta) {
        if (logManager.isActive()) return
        val course = repo.loadCourse(meta.id)
        val waypoints = course.pois.filter { !it.userAdded }.sortedBy { it.routeKm }
        if (waypoints.isEmpty()) {
            Toast.makeText(this, "이 GPX에는 선택할 웨이포인트가 없습니다.", Toast.LENGTH_LONG).show()
            return
        }
        val existing = chargingStore.list(meta.id)
        val existingById = existing.associateBy { it.id }
        val checked = BooleanArray(waypoints.size) { i -> existingById.containsKey(ChargingStation.waypointId(waypoints[i])) }
        val labels = waypoints.map { p -> "${RideFormatter.one(p.routeKm)}km · ${p.name}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("GPX 웨이포인트 → 충전소")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("저장") { _, _ ->
                val keep = existing.filter { it.source != ChargingStation.SOURCE_WAYPOINT }.toMutableList()
                waypoints.forEachIndexed { i, poi ->
                    if (checked[i]) {
                        val id = ChargingStation.waypointId(poi)
                        keep += existingById[id] ?: ChargingStation.fromPoi(poi).copy(
                            distanceFromRouteM = course.nearestRouteLocation(poi.lat, poi.lon).distanceM
                        )
                    }
                }
                chargingStore.replace(meta.id, keep)
                renderCourses()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showAddStationOptions(meta: CourseMeta) {
        if (logManager.isActive()) return
        val items = arrayOf("주소로 검색", "코스 km 직접 입력", "고도 프로필에서 선택", "현재 진행 위치 등록")
        AlertDialog.Builder(this)
            .setTitle("충전소 추가")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showAddressSearch(meta)
                    1 -> showKmInput(meta)
                    2 -> showProfilePicker(meta)
                    3 -> addCurrentProgressStation(meta)
                }
            }
            .show()
    }

    private fun showKmInput(meta: CourseMeta) {
        val course = repo.loadCourse(meta.id)
        val input = EditText(this).apply {
            hint = "예: 63.5"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            textSize = 18f
        }
        AlertDialog.Builder(this)
            .setTitle("코스 km 직접 입력")
            .setMessage("충전소가 있는 GPX 진행거리를 입력하세요. (0 ~ ${RideFormatter.one(course.totalKm)}km)")
            .setView(input)
            .setPositiveButton("다음") { _, _ ->
                val km = input.text.toString().toDoubleOrNull()
                if (km == null || km <= 0.0 || km >= course.totalKm) {
                    Toast.makeText(this, "코스 범위 안의 km를 입력해주세요.", Toast.LENGTH_LONG).show()
                } else {
                    val p = course.pointAtKm(km)
                    showStationEditor(meta, ChargingStation(
                        id = ChargingStation.newId(),
                        name = "직접 지정 충전소",
                        routeKm = km,
                        lat = p.lat,
                        lon = p.lon,
                        source = ChargingStation.SOURCE_KM
                    ), isNew = true)
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showProfilePicker(meta: CourseMeta) {
        val course = repo.loadCourse(meta.id)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
        }
        val label = TextView(this).apply {
            textSize = 16f
            setTextColor(getColor(R.color.text_primary))
        }
        val profile = ElevationProfileView(this).apply {
            setCourse(course)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(150))
        }
        val seek = SeekBar(this).apply { max = 1000 }
        root.addView(label)
        root.addView(profile)
        root.addView(seek)
        fun update(progress: Int) {
            val km = course.totalKm * progress / 1000.0
            val p = course.pointAtKm(km)
            label.text = if (course.hasElevation) "${RideFormatter.one(km)}km · 고도 ${p.ele.roundToInt()}m" else "${RideFormatter.one(km)}km"
            profile.setCurrentKm(km)
        }
        seek.progress = 500
        update(seek.progress)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = update(progress)
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        val dialog = AlertDialog.Builder(this)
            .setTitle("고도 프로필에서 충전 위치 선택")
            .setView(root)
            .setPositiveButton("다음", null)
            .setNegativeButton("취소", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val km = course.totalKm * seek.progress / 1000.0
                if (km <= 0.1 || km >= course.totalKm - 0.1) return@setOnClickListener
                val p = course.pointAtKm(km)
                dialog.dismiss()
                showStationEditor(meta, ChargingStation(
                    id = ChargingStation.newId(),
                    name = "프로필 지정 충전소",
                    routeKm = km,
                    lat = p.lat,
                    lon = p.lon,
                    source = ChargingStation.SOURCE_PROFILE
                ), isNew = true)
            }
        }
        dialog.show()
    }

    private fun addCurrentProgressStation(meta: CourseMeta) {
        val course = repo.loadCourse(meta.id)
        val km = AppSettings.prefs(this).getFloat(AppSettings.KEY_LAST_KM, 0f).toDouble().coerceIn(0.0, course.totalKm)
        if (km <= 0.05) {
            Toast.makeText(this, "현재 진행 위치가 아직 없습니다. 주행 또는 테스트 위치를 먼저 이동하세요.", Toast.LENGTH_LONG).show()
            return
        }
        val p = course.pointAtKm(km)
        showStationEditor(meta, ChargingStation(
            id = ChargingStation.newId(),
            name = "현재 위치 충전소",
            routeKm = km,
            lat = p.lat,
            lon = p.lon,
            source = ChargingStation.SOURCE_CURRENT
        ), isNew = true)
    }

    private fun showAddressSearch(meta: CourseMeta) {
        if (!Geocoder.isPresent()) {
            Toast.makeText(this, "이 휴대폰에서는 주소 검색 서비스를 사용할 수 없습니다. km 직접 입력을 이용해주세요.", Toast.LENGTH_LONG).show()
            return
        }
        val input = EditText(this).apply {
            hint = "주소 또는 장소명"
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 17f
        }
        AlertDialog.Builder(this)
            .setTitle("주소로 충전소 찾기")
            .setMessage("인터넷 연결이 필요할 수 있습니다. 검색 결과를 GPX 코스의 가장 가까운 지점에 연결합니다.")
            .setView(input)
            .setPositiveButton("검색") { _, _ ->
                val query = input.text.toString().trim()
                if (query.isNotBlank()) geocodeAddress(meta, query)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun geocodeAddress(meta: CourseMeta, query: String) {
        Toast.makeText(this, "주소 검색 중…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val geocoder = Geocoder(this, Locale.KOREA)
                val addresses = geocoder.getFromLocationName(query, 5).orEmpty()
                val course = repo.loadCourse(meta.id)
                val candidates = addresses.filter { it.hasLatitude() && it.hasLongitude() }.map { address ->
                    val match = course.nearestRouteLocation(address.latitude, address.longitude)
                    AddressCandidate(
                        title = address.getAddressLine(0) ?: address.featureName ?: query,
                        lat = address.latitude,
                        lon = address.longitude,
                        match = match
                    )
                }
                runOnUiThread {
                    if (candidates.isEmpty()) Toast.makeText(this, "검색 결과가 없습니다. km 직접 입력을 이용해보세요.", Toast.LENGTH_LONG).show()
                    else showAddressResults(meta, candidates)
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "주소 검색 실패: ${e.message ?: "네트워크/주소 서비스를 확인하세요."}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun showAddressResults(meta: CourseMeta, candidates: List<AddressCandidate>) {
        val labels = candidates.map { c ->
            val off = if (c.match.distanceM >= 1000) "${String.format(Locale.US, "%.1f", c.match.distanceM / 1000.0)}km" else "${c.match.distanceM.roundToInt()}m"
            "${c.title}\n→ 코스 ${RideFormatter.one(c.match.routeKm)}km · 코스에서 $off"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("주소 검색 결과")
            .setItems(labels) { _, which ->
                val c = candidates[which]
                val station = ChargingStation(
                    id = ChargingStation.newId(),
                    name = c.title.substringBefore(',').take(40).ifBlank { "주소 충전소" },
                    routeKm = c.match.routeKm,
                    lat = c.lat,
                    lon = c.lon,
                    source = ChargingStation.SOURCE_ADDRESS,
                    distanceFromRouteM = c.match.distanceM,
                    address = c.title
                )
                if (c.match.distanceM > 800.0) {
                    AlertDialog.Builder(this)
                        .setTitle("코스에서 떨어진 위치")
                        .setMessage("이 장소는 GPX 코스에서 약 ${c.match.distanceM.roundToInt()}m 떨어져 있습니다.\n\n실제 도로 우회거리는 다음 화면의 ‘추가 주행거리’에 입력해주세요.")
                        .setPositiveButton("계속") { _, _ -> showStationEditor(meta, station, isNew = true) }
                        .setNegativeButton("취소", null)
                        .show()
                } else showStationEditor(meta, station, isNew = true)
            }
            .show()
    }

    private fun showStationEditor(meta: CourseMeta, station: ChargingStation, isNew: Boolean = false) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
        }
        root.addView(TextView(this).apply {
            val off = if (station.distanceFromRouteM > 20.0) " · 코스에서 ${station.distanceFromRouteM.roundToInt()}m" else ""
            text = "코스 ${RideFormatter.one(station.routeKm)}km$off\n${station.sourceLabel()}"
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, 0, 0, dp(8))
        })
        val nameInput = EditText(this).apply {
            hint = "충전소 이름"
            setText(station.name)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val chargeInput = EditText(this).apply {
            hint = "충전 후 목표 % (1~100)"
            setText(station.chargeToPct.roundToInt().toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val detourInput = EditText(this).apply {
            hint = "코스 외 추가 주행거리 km (선택)"
            setText(if (station.detourKm > 0.0) RideFormatter.one(station.detourKm) else "0")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        root.addView(nameInput)
        root.addView(chargeInput)
        root.addView(detourInput)
        if (station.address.isNotBlank()) root.addView(TextView(this).apply {
            text = station.address
            textSize = 11f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(6), 0, 0)
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isNew) "충전소 등록" else "충전소 수정")
            .setView(root)
            .setPositiveButton("저장", null)
            .setNegativeButton("취소", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim().ifBlank { "충전소" }
                val target = chargeInput.text.toString().toIntOrNull()
                val detour = detourInput.text.toString().toDoubleOrNull() ?: 0.0
                if (target == null || target !in 1..100 || detour < 0.0 || detour > 200.0) {
                    Toast.makeText(this, "충전 목표는 1~100%, 추가 거리는 0~200km로 입력해주세요.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                chargingStore.upsert(meta.id, station.copy(name = name, chargeToPct = target.toDouble(), detourKm = detour))
                dialog.dismiss()
                renderCourses()
            }
        }
        dialog.show()
    }

    private fun addRecommendedStation(meta: CourseMeta) {
        if (logManager.isActive()) return
        val course = repo.loadCourse(meta.id)
        val currentStations = chargingStore.list(meta.id)
        if (currentStations.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("자동 충전 분석")
                .setMessage("이미 충전소 ${currentStations.size}개가 등록되어 있습니다. 배터리 판단은 이 계획을 사용합니다. 자동 권장은 충전소가 없을 때 제공됩니다.")
                .setPositiveButton("확인", null)
                .show()
            return
        }
        val km = BatteryPlan(course, learningStore).recommendedChargeKm(AppSettings.finishTarget(this))
        if (km == null) {
            AlertDialog.Builder(this)
                .setTitle("자동 충전 분석")
                .setMessage("현재 배터리 모델과 목표잔량 기준으로는 필수 충전 지점을 특정하지 않았습니다. 필요하면 웨이포인트·주소·km로 직접 등록하세요.")
                .setPositiveButton("확인", null)
                .show()
            return
        }
        val p = course.pointAtKm(km)
        val station = ChargingStation(
            id = ChargingStation.newId(),
            name = "권장 충전 지점",
            routeKm = km,
            lat = p.lat,
            lon = p.lon,
            source = ChargingStation.SOURCE_RECOMMENDED
        )
        AlertDialog.Builder(this)
            .setTitle("권장 충전 구간")
            .setMessage("현재 예측상 ${RideFormatter.one(km)}km 부근에서 충전 계획을 검토하는 것이 좋습니다.\n\n실제 충전 가능한 장소를 앱이 알고 있는 것은 아닙니다. 주소나 대회 안내를 확인한 뒤 위치를 조정하세요.")
            .setPositiveButton("임시 등록") { _, _ -> showStationEditor(meta, station, isNew = true) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun buildCourseRow(meta: CourseMeta, activeId: String, riding: Boolean): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundResource(if (meta.id == activeId) R.drawable.panel_accent_bg else R.drawable.panel_bg)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        outer.addView(TextView(this).apply {
            text = buildString {
                append(if (meta.id == activeId) "✓ " else "")
                append(meta.name)
                if (meta.builtIn) append(" · 기본")
            }
            textSize = 18f
            setTextColor(getColor(R.color.text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        outer.addView(TextView(this).apply {
            val elev = if (meta.hasElevation) "▲${meta.totalAscentM.roundToInt()}m · ▼${meta.totalDescentM.roundToInt()}m" else "고도 데이터 없음"
            val chargeCount = chargingStore.list(meta.id).size
            text = "${RideFormatter.one(meta.totalKm)} km · $elev · 충전소 ${chargeCount}개"
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(4), 0, dp(8))
        })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actions.addView(Button(this).apply {
            text = if (meta.id == activeId) "선택됨" else "이 코스 사용"
            isEnabled = !riding && meta.id != activeId
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f)
            setOnClickListener { selectCourse(meta) }
        })
        if (!meta.builtIn) {
            actions.addView(Button(this).apply {
                text = "삭제"
                isEnabled = !riding
                layoutParams = LinearLayout.LayoutParams(0, dp(46), 0.55f).apply { marginStart = dp(8) }
                setOnClickListener { confirmDelete(meta) }
            })
        }
        outer.addView(actions)
        return outer
    }

    private fun selectCourse(meta: CourseMeta) {
        if (logManager.isActive()) return
        repo.setActive(meta.id)
        BatteryActualStore(this).clear()
        AppSettings.prefs(this).edit().putFloat(AppSettings.KEY_LAST_KM, 0f).putFloat(AppSettings.KEY_TEST_KM, 0f).apply()
        renderCourses()
        Toast.makeText(this, "${meta.name} 코스를 선택했습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun confirmDelete(meta: CourseMeta) {
        if (meta.builtIn || logManager.isActive()) return
        AlertDialog.Builder(this)
            .setTitle("코스 삭제")
            .setMessage("${meta.name} GPX 코스를 앱에서 삭제할까요? 과거 주행 로그는 삭제하지 않습니다.")
            .setPositiveButton("삭제") { _, _ ->
                repo.deleteCourse(meta.id)
                BatteryActualStore(this).clear()
                AppSettings.prefs(this).edit().putFloat(AppSettings.KEY_LAST_KM, 0f).putFloat(AppSettings.KEY_TEST_KM, 0f).apply()
                renderCourses()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun exportLastLog() {
        val file = logManager.lastZipFile() ?: return Toast.makeText(this, "내보낼 주행 로그가 없습니다.", Toast.LENGTH_SHORT).show()
        pendingExportFile = file
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, file.name)
        }
        try { startActivityForResult(intent, REQ_EXPORT) }
        catch (_: ActivityNotFoundException) { Toast.makeText(this, "파일 저장 위치를 열 수 없습니다.", Toast.LENGTH_LONG).show() }
    }

    private fun importGpx() {
        if (logManager.isActive()) return Toast.makeText(this, "주행 종료 후 GPX를 불러오세요.", Toast.LENGTH_LONG).show()
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream"))
        }
        try { startActivityForResult(intent, REQ_GPX) }
        catch (_: ActivityNotFoundException) { Toast.makeText(this, "파일 선택기를 열 수 없습니다.", Toast.LENGTH_LONG).show() }
    }

    @Deprecated("Deprecated in Android, retained for minSdk 26 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_GPX -> {
                if (resultCode != RESULT_OK) return
                val uri = data?.data ?: return
                try {
                    val meta = repo.importGpx(uri, displayName(uri))
                    BatteryActualStore(this).clear()
                    AppSettings.prefs(this).edit().putFloat(AppSettings.KEY_LAST_KM, 0f).putFloat(AppSettings.KEY_TEST_KM, 0f).apply()
                    renderCourses()
                    val course = repo.loadCourse(meta.id)
                    val wptCount = course.pois.count { !it.userAdded }
                    if (wptCount > 0) {
                        AlertDialog.Builder(this)
                            .setTitle("웨이포인트 $wptCount개 발견")
                            .setMessage("GPX 안의 웨이포인트에서 충전소를 선택하시겠습니까?")
                            .setPositiveButton("선택하기") { _, _ -> showWaypointPicker(meta) }
                            .setNegativeButton("나중에", null)
                            .show()
                    } else {
                        val recommended = BatteryPlan(course, learningStore).recommendedChargeKm(AppSettings.finishTarget(this))
                        AlertDialog.Builder(this)
                            .setTitle("GPX 불러오기 완료")
                            .setMessage(if (recommended != null) "웨이포인트가 없습니다.\n예측상 ${RideFormatter.one(recommended)}km 부근에서 충전 계획을 검토할 수 있습니다.\n주소·km·고도 프로필로 충전소를 추가하세요." else "웨이포인트가 없습니다. 주소·km·고도 프로필로 충전소를 추가할 수 있습니다.")
                            .setPositiveButton("확인", null)
                            .show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "GPX 불러오기 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            REQ_EXPORT -> {
                if (resultCode == RESULT_OK) {
                    val uri = data?.data
                    val source = pendingExportFile
                    if (uri != null && source != null) {
                        try {
                            contentResolver.openOutputStream(uri)?.use { out -> source.inputStream().use { input -> input.copyTo(out) } }
                            Toast.makeText(this, "최근 주행 로그 ZIP을 저장했습니다.", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(this, "내보내기 실패: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                pendingExportFile = null
            }
        }
    }

    private fun displayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) cursor.getString(0) else null
        } catch (_: Exception) { null } finally { cursor?.close() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private data class AddressCandidate(
        val title: String,
        val lat: Double,
        val lon: Double,
        val match: RouteLocationMatch
    )
}
