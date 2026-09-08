package com.seungjae.jangsu280battery

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * TimeGate course builder.
 *
 * User flow is deliberately simple: START begins GPS recording and fixes START, CP adds CP1/CP2…,
 * and FINISH fixes FINISH and stops recording. Every gate initially points in the riding direction
 * and remains freely editable afterwards. Automatic CP detection only recommends likely segment
 * boundaries; it never creates a CP without the user's tap.
 */
class RaceTrackBuilderActivity : Activity() {
    companion object {
        private const val REQ_LOCATION = 8841
        private const val DEFAULT_GATE_WIDTH_M = 5.0
        private const val MIN_GATE_WIDTH_M = 1.0
        private const val MAX_GATE_WIDTH_M = 20.0
        private const val PREF_COURSE_TYPE = "race_track_builder_course_type_v1"
        private const val TYPE_OPEN = "OPEN"
        private const val TYPE_CLOSED = "CLOSED"
    }

    private lateinit var drafts: RaceTrackDraftStore
    private lateinit var repo: CourseRepository
    private lateinit var sync: RiderServerSync
    private lateinit var map: RaceTrackBuilderMapView
    private lateinit var status: TextView
    private lateinit var trapContainer: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var seekLabel: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnPause: Button
    private lateinit var btnFinish: Button
    private lateinit var btnAddTrap: Button
    private lateinit var btnSave: Button
    private lateinit var btnPublish: Button
    private lateinit var btnCourseType: Button
    private lateinit var btnMapEdit: Button

    private var draft: RaceTrackDraftStore.Draft? = null
    private val points = mutableListOf<RaceTrackDraftStore.Point>()
    private val gates = mutableListOf<RaceGate>()
    private var selectedRouteM: Double? = null
    private var savedMeta: CourseMeta? = null
    private var receiverRegistered = false
    private var courseType = TYPE_OPEN
    private var pendingStartRouteM: Double? = null
    private var suggestedCpRouteM: Double? = null
    private var lastSuggestedCpRouteM = -10_000.0

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != RaceTrackRecorderService.ACTION_UPDATE) return
            val active = draft ?: drafts.active() ?: return
            if (intent.getStringExtra(RaceTrackRecorderService.EXTRA_DRAFT_ID).orEmpty().let { it.isNotBlank() && it != active.id }) return
            val state = intent.getStringExtra(RaceTrackRecorderService.EXTRA_STATE)
            if (!state.isNullOrBlank()) draft = drafts.setState(active.id, state) ?: draft
            if (intent.hasExtra(RaceTrackRecorderService.EXTRA_LAT) && intent.hasExtra(RaceTrackRecorderService.EXTRA_LON)) {
                val p = RaceTrackDraftStore.Point(
                    lat = intent.getDoubleExtra(RaceTrackRecorderService.EXTRA_LAT, 0.0),
                    lon = intent.getDoubleExtra(RaceTrackRecorderService.EXTRA_LON, 0.0),
                    ele = intent.getDoubleExtra(RaceTrackRecorderService.EXTRA_ELE, 0.0),
                    timeMs = intent.getLongExtra(RaceTrackRecorderService.EXTRA_TIME, System.currentTimeMillis()),
                    accuracyM = intent.getDoubleExtra(RaceTrackRecorderService.EXTRA_ACC, 99.0),
                    bearingDeg = intent.getDoubleExtra(RaceTrackRecorderService.EXTRA_BEARING, Double.NaN),
                    routeM = intent.getDoubleExtra(RaceTrackRecorderService.EXTRA_ROUTE_M, points.lastOrNull()?.routeM ?: 0.0)
                )
                if (points.lastOrNull()?.timeMs != p.timeMs) points += p
                selectedRouteM = p.routeM

                if (pendingStartRouteM != null && points.size >= 2 && gates.none { it.type == "START" }) {
                    val startM = pendingStartRouteM!!.coerceIn(0.0, points.last().routeM)
                    pendingStartRouteM = null
                    addTrap("START", startM, refresh = false)
                }
                if (courseType == TYPE_CLOSED) syncClosedLoopFinish(persist = false)
                evaluateCpSuggestion()
            }
            refreshUi(follow = draft?.state == RaceTrackDraftStore.STATE_RECORDING)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        drafts = RaceTrackDraftStore(this); repo = CourseRepository(this); sync = RiderServerSync(this)
        buildUi(); restoreDraft(); ensureLocationPermission()
    }

    override fun onResume() { super.onResume(); registerUpdates(); restoreDraft() }
    override fun onPause() { unregisterUpdates(); super.onPause() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "코스 기록에는 정확한 위치 권한이 필요합니다.", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        setContentView(root)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), 0, dp(14), 0); setBackgroundColor(Color.rgb(43, 43, 43)) }
        top.addView(Button(this).apply { text = "‹"; textSize = 28f; setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); setOnClickListener { finish() } }, LinearLayout.LayoutParams(dp(56), dp(58)))
        top.addView(TextView(this).apply { text = "TimeGate 코스 만들기"; textSize = 22f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, dp(58), 1f))
        root.addView(top)

        map = RaceTrackBuilderMapView(this)
        root.addView(map, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottomScroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(11, 16, 23)) }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(16)) }
        bottomScroll.addView(body); root.addView(bottomScroll, LinearLayout.LayoutParams(-1, dp(372)))

        status = TextView(this).apply { textSize = 13f; setTextColor(Color.LTGRAY); setPadding(0, 0, 0, dp(8)) }
        body.addView(status)

        btnCourseType = Button(this).apply { isAllCaps = false; textSize = 14f; setTypeface(typeface, Typeface.BOLD); setOnClickListener { chooseCourseType() } }
        body.addView(btnCourseType, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(6) })

        btnMapEdit = Button(this).apply {
            isAllCaps = false; text = "↗ 큰 지도에서 위치 · 방향 · 폭 자유 편집"; textSize = 14f; setTypeface(typeface, Typeface.BOLD)
            setOnClickListener { openFullscreenTrapEditor() }
        }
        body.addView(btnMapEdit, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(7) })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRecord = Button(this).apply { text = "START"; setTypeface(typeface, Typeface.BOLD); setOnClickListener { startNewDraft() } }
        btnPause = Button(this).apply { text = "Ⅱ 일시정지"; setOnClickListener { togglePause() } }
        btnFinish = Button(this).apply { text = "FINISH"; setTypeface(typeface, Typeface.BOLD); setOnClickListener { finishCourse() } }
        row.addView(btnRecord, LinearLayout.LayoutParams(0, dp(50), 1f))
        row.addView(btnPause, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(6) })
        row.addView(btnFinish, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(6) })
        body.addView(row)

        seekLabel = TextView(this).apply { text = "게이트 위치"; textSize = 12f; setTextColor(Color.LTGRAY); setPadding(0, dp(8), 0, 0) }
        body.addView(seekLabel)
        seek = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser || points.isEmpty()) return
                    val total = points.last().routeM.coerceAtLeast(1.0)
                    selectedRouteM = total * progress / 1000.0
                    suggestedCpRouteM = null
                    refreshMap(false); updateSeekLabel(); updateActionLabels()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        body.addView(seek)

        val trapActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnAddTrap = Button(this).apply { text = "+ CP1"; setOnClickListener { addCp() } }
        btnSave = Button(this).apply { text = "코스 저장"; setOnClickListener { saveCourse() } }
        btnPublish = Button(this).apply { text = "서버 등록"; setOnClickListener { publishCourse() } }
        trapActions.addView(btnAddTrap, LinearLayout.LayoutParams(0, dp(48), 1f))
        trapActions.addView(btnSave, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        trapActions.addView(btnPublish, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        body.addView(trapActions)

        body.addView(TextView(this).apply {
            text = "START → CP1 → CP2… → FINISH 순서로 누르면 됩니다. 급감속·큰 방향전환 지점은 CP 후보로만 추천합니다. 게이트 방향은 진행방향, 폭 기본 5m(1~20m 편집)입니다."
            textSize = 10.5f; setTextColor(Color.GRAY); setPadding(0, dp(8), 0, dp(5))
        })
        trapContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(trapContainer)
    }

    private fun ensureLocationPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION)
    }

    private fun chooseCourseType() {
        val choices = arrayOf("개방형 · START / FINISH 분리", "폐쇄형 · START = FINISH")
        val checked = if (courseType == TYPE_CLOSED) 1 else 0
        AlertDialog.Builder(this).setTitle("코스 형태").setSingleChoiceItems(choices, checked) { dialog, which ->
            val previous = courseType
            courseType = if (which == 1) TYPE_CLOSED else TYPE_OPEN
            draft?.let { saveCourseType(it.id) }
            if (courseType == TYPE_CLOSED) syncClosedLoopFinish(persist = true)
            else if (previous == TYPE_CLOSED) { gates.removeAll { it.type == "FINISH" }; draft?.let { drafts.writeTraps(it.id, gates) } }
            dialog.dismiss(); refreshUi(false)
        }.setNegativeButton("취소", null).show()
    }

    private fun openFullscreenTrapEditor() {
        if (points.size < 2) { Toast.makeText(this, "GPS 코스가 아직 없습니다.", Toast.LENGTH_SHORT).show(); return }
        if (gates.isEmpty()) { Toast.makeText(this, "START/CP/FINISH를 하나 이상 먼저 추가해 주세요.", Toast.LENGTH_SHORT).show(); return }
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(7, 16, 26)) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(6), dp(8), dp(6)); setBackgroundColor(Color.rgb(13, 21, 32)) }
        bar.addView(TextView(this).apply { text = "TimeGate 큰 지도 편집"; textSize = 19f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) }, LinearLayout.LayoutParams(0, dp(50), 1f))
        bar.addView(Button(this).apply { text = "완료"; isAllCaps = false; setTypeface(typeface, Typeface.BOLD); setOnClickListener { dialog.dismiss() } }, LinearLayout.LayoutParams(dp(88), dp(44)))
        root.addView(bar)
        root.addView(TextView(this).apply {
            text = "화살표 몸통 드래그 = 위치 · 끝 흰 점 = 진행방향 · 옆 흰 점 = 게이트 폭 1~20m"
            textSize = 12f; setTextColor(Color.rgb(200, 216, 235)); setPadding(dp(12), dp(8), dp(12), dp(8)); setBackgroundColor(Color.rgb(10, 28, 43))
        }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))

        lateinit var editorMap: RaceTrackBuilderMapView
        editorMap = RaceTrackBuilderMapView(this)
        editorMap.setTrapEditListener(RaceTrackBuilderMapView.TrapEditListener { index, lat, lon, bearingDeg, widthM ->
            if (index !in gates.indices) return@TrapEditListener
            val original = gates[index]
            if (courseType == TYPE_CLOSED && original.type == "FINISH") {
                Toast.makeText(this, "폐쇄형 FINISH는 START 위치에 자동 고정됩니다.", Toast.LENGTH_SHORT).show(); editorMap.render(points, gates, selectedRouteM, false); return@TrapEditListener
            }
            gates[index] = original.copy(lat = lat, lon = lon, bearingDeg = ((bearingDeg % 360.0) + 360.0) % 360.0, widthM = widthM.coerceIn(MIN_GATE_WIDTH_M, MAX_GATE_WIDTH_M))
            selectedRouteM = original.routeM
            if (courseType == TYPE_CLOSED && original.type == "START") syncClosedLoopFinish(persist = true) else draft?.let { drafts.writeTraps(it.id, gates) }
            editorMap.render(points, gates, selectedRouteM, false)
        })
        root.addView(editorMap, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(root)
        dialog.setOnShowListener { editorMap.render(points, gates, selectedRouteM, false) }
        dialog.setOnDismissListener { editorMap.setTrapEditListener(null, false); refreshUi(false) }
        dialog.show()
    }

    private fun startNewDraft() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { ensureLocationPermission(); return }
        draft?.let { old -> if (old.state == RaceTrackDraftStore.STATE_RECORDING) sendAction(RaceTrackRecorderService.ACTION_STOP) }
        val d = drafts.start("새 TimeGate 코스")
        draft = d; points.clear(); gates.clear(); savedMeta = null; selectedRouteM = null
        pendingStartRouteM = 0.0; suggestedCpRouteM = null; lastSuggestedCpRouteM = -10_000.0
        saveCourseType(d.id)
        startForegroundService(Intent(this, RaceTrackRecorderService::class.java).apply { action = RaceTrackRecorderService.ACTION_START; putExtra(RaceTrackRecorderService.EXTRA_DRAFT_ID, d.id) })
        Toast.makeText(this, "START 기록 시작 · 첫 GPS 진행방향으로 START 게이트를 만듭니다.", Toast.LENGTH_LONG).show()
        refreshUi(true)
    }

    private fun togglePause() {
        val d = draft ?: return
        when (d.state) {
            RaceTrackDraftStore.STATE_RECORDING -> sendAction(RaceTrackRecorderService.ACTION_PAUSE)
            RaceTrackDraftStore.STATE_PAUSED -> sendAction(RaceTrackRecorderService.ACTION_RESUME)
            else -> Toast.makeText(this, "START를 먼저 눌러 주세요.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun finishCourse() {
        val d = draft ?: run { Toast.makeText(this, "START를 먼저 눌러 주세요.", Toast.LENGTH_SHORT).show(); return }
        if (points.size < 2) { Toast.makeText(this, "GPS 포인트가 아직 부족합니다.", Toast.LENGTH_SHORT).show(); return }
        if (gates.none { it.type == "START" }) addTrap("START", 0.0, refresh = false)
        selectedRouteM = points.last().routeM
        if (courseType == TYPE_OPEN) addTrap("FINISH", selectedRouteM!!, refresh = false)
        sendAction(RaceTrackRecorderService.ACTION_STOP)
        draft = drafts.setState(d.id, RaceTrackDraftStore.STATE_STOPPED)
        if (courseType == TYPE_CLOSED) syncClosedLoopFinish(persist = true) else drafts.writeTraps(d.id, gates)
        suggestedCpRouteM = null
        Toast.makeText(this, "FINISH 지정 완료 · 코스 저장 전 게이트를 수정할 수 있습니다.", Toast.LENGTH_LONG).show()
        refreshUi(false)
    }

    private fun sendAction(a: String) { startService(Intent(this, RaceTrackRecorderService::class.java).apply { action = a }) }

    private fun addCp() {
        if (points.size < 2) { Toast.makeText(this, "GPS 포인트가 아직 부족합니다.", Toast.LENGTH_SHORT).show(); return }
        val m = suggestedCpRouteM ?: points.last().routeM
        selectedRouteM = m
        addTrap("SECTOR", m, refresh = false)
        suggestedCpRouteM = null
        refreshUi(false)
    }

    private fun addTrap(type: String, routeM: Double? = null, refresh: Boolean = true) {
        if (courseType == TYPE_CLOSED && type == "FINISH") return
        val m = routeM ?: selectedRouteM ?: points.lastOrNull()?.routeM ?: return
        val name = when (type) {
            "START" -> "START"
            "FINISH" -> "FINISH"
            else -> "CP${gates.count { it.type == "SECTOR" } + 1}"
        }
        if (type == "START") gates.removeAll { it.type == "START" }
        if (type == "FINISH") gates.removeAll { it.type == "FINISH" }
        gates += gateAt(m, name, type, DEFAULT_GATE_WIDTH_M)
        gates.sortBy { it.routeM }
        if (courseType == TYPE_CLOSED && type == "START") syncClosedLoopFinish(persist = true) else draft?.let { drafts.writeTraps(it.id, gates) }
        if (refresh) refreshUi(false)
    }

    private fun evaluateCpSuggestion() {
        val d = draft ?: return
        if (d.state != RaceTrackDraftStore.STATE_RECORDING || points.size < 8) return
        val end = points.last()
        if (end.routeM - lastSuggestedCpRouteM < 90.0) return
        val midI = indexNearRoute((end.routeM - 8.0).coerceAtLeast(0.0))
        val startI = indexNearRoute((end.routeM - 28.0).coerceAtLeast(0.0))
        if (startI >= midI || midI >= points.lastIndex) return
        val a = points[startI]; val b = points[midI]; val c = end
        val before = speedMps(a, b); val after = speedMps(b, c)
        if (before < 2.5) return
        val drop = (1.0 - after / before.coerceAtLeast(0.1)).coerceIn(-2.0, 1.0)
        val turn = angleDiff(bearingBetween(a, b), bearingBetween(b, c))
        val strong = (drop >= 0.45 && turn >= 20.0) || drop >= 0.58 || turn >= 55.0
        if (!strong) return
        val candidateM = b.routeM
        if (gates.any { abs(it.routeM - candidateM) < 70.0 }) return
        suggestedCpRouteM = candidateM; selectedRouteM = candidateM; lastSuggestedCpRouteM = candidateM
        updateSeekFromSelection(); updateActionLabels(); refreshMap(false)
    }

    private fun speedMps(a: RaceTrackDraftStore.Point, b: RaceTrackDraftStore.Point): Double {
        val dt = (b.timeMs - a.timeMs).coerceAtLeast(1L) / 1000.0
        return (b.routeM - a.routeM).coerceAtLeast(0.0) / dt
    }

    private fun bearingBetween(a: RaceTrackDraftStore.Point, b: RaceTrackDraftStore.Point): Double {
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat); val dl = Math.toRadians(b.lon - a.lon)
        val y = sin(dl) * cos(p2); val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun angleDiff(a: Double, b: Double): Double { val d = abs(((b - a + 540.0) % 360.0) - 180.0); return d.coerceIn(0.0, 180.0) }

    private fun gateAt(routeM: Double, name: String, type: String, width: Double): RaceGate {
        val i = nearestPointIndex(routeM); val p = points[i]
        return RaceGate(name, type, p.routeM, p.lat, p.lon, bearingAt(i), width.coerceIn(MIN_GATE_WIDTH_M, MAX_GATE_WIDTH_M))
    }

    private fun syncClosedLoopFinish(persist: Boolean) {
        if (courseType != TYPE_CLOSED || points.size < 2) return
        val start = gates.firstOrNull { it.type == "START" } ?: return
        val totalM = points.last().routeM
        if (totalM <= start.routeM + 1.0) return
        val oldFinish = gates.firstOrNull { it.type == "FINISH" }
        val width = (oldFinish?.widthM ?: start.widthM).coerceIn(MIN_GATE_WIDTH_M, MAX_GATE_WIDTH_M)
        val finish = RaceGate("FINISH", "FINISH", totalM, start.lat, start.lon, bearingAt(points.lastIndex), width)
        gates.removeAll { it.type == "FINISH" }; gates += finish; gates.sortBy { it.routeM }
        if (persist) draft?.let { drafts.writeTraps(it.id, gates) }
    }

    private fun indexNearRoute(routeM: Double): Int = nearestPointIndex(routeM)
    private fun nearestPointIndex(routeM: Double): Int {
        if (points.isEmpty()) return 0
        var best = 0; var bestD = Double.MAX_VALUE
        points.forEachIndexed { i, p -> val d = abs(p.routeM - routeM); if (d < bestD) { best = i; bestD = d } }
        return best
    }

    private fun bearingAt(i: Int): Double {
        val p = points[i]
        if (p.bearingDeg.isFinite()) return ((p.bearingDeg % 360.0) + 360.0) % 360.0
        val a = points[(i - 1).coerceAtLeast(0)]; val b = points[(i + 1).coerceAtMost(points.lastIndex)]
        return bearingBetween(a, b)
    }

    private fun renderTraps() {
        trapContainer.removeAllViews()
        if (gates.isEmpty()) { trapContainer.addView(TextView(this).apply { text = "START를 누르면 첫 게이트가 자동 생성됩니다."; textSize = 11f; setTextColor(Color.GRAY) }); return }
        gates.sortedBy { it.routeM }.forEach { gate ->
            val lockedFinish = courseType == TYPE_CLOSED && gate.type == "FINISH"
            val b = Button(this).apply {
                text = "${gate.name} · ${"%.3f".format(Locale.US, gate.routeM / 1000.0)}km · 폭 ${"%.1f".format(Locale.US, gate.widthM)}m · 방향 ${Math.round(gate.bearingDeg)}°${if (lockedFinish) " · START와 동일" else ""}"
                textSize = 12f; isAllCaps = false; gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { if (lockedFinish) Toast.makeText(this@RaceTrackBuilderActivity, "폐쇄형 FINISH는 START 위치에 자동 고정됩니다.", Toast.LENGTH_SHORT).show() else editTrap(gate) }
            }
            trapContainer.addView(b, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(3) })
        }
    }

    private fun editTrap(original: RaceGate) {
        if (courseType == TYPE_CLOSED && original.type == "FINISH") { Toast.makeText(this, "폐쇄형 FINISH는 START 위치에 자동 고정됩니다.", Toast.LENGTH_SHORT).show(); return }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, dp(18), 0) }
        val name = EditText(this).apply { hint = "게이트 이름"; setText(original.name); setSingleLine(true) }
        val width = EditText(this).apply { hint = "폭(m) · 1~20"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setText("%.1f".format(Locale.US, original.widthM.coerceIn(MIN_GATE_WIDTH_M, MAX_GATE_WIDTH_M))) }
        val label = TextView(this).apply { textSize = 13f; setTextColor(Color.DKGRAY) }
        val slider = SeekBar(this).apply { max = 1000 }
        val total = points.lastOrNull()?.routeM?.coerceAtLeast(1.0) ?: 1.0
        slider.progress = ((original.routeM / total) * 1000.0).toInt().coerceIn(0, 1000)
        fun updateLabel(progress: Int) { label.text = "위치 ${"%.3f".format(Locale.US, total * progress / 1000.0 / 1000.0)} km · 폭 1~20m" }
        updateLabel(slider.progress)
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { updateLabel(progress); if (fromUser) { selectedRouteM = total * progress / 1000.0; refreshMap(false) } }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        root.addView(name); root.addView(width); root.addView(label); root.addView(slider)
        AlertDialog.Builder(this).setTitle("${original.name} 편집").setView(root)
            .setPositiveButton("저장") { _, _ ->
                val m = total * slider.progress / 1000.0
                val w = width.text.toString().toDoubleOrNull()?.coerceIn(MIN_GATE_WIDTH_M, MAX_GATE_WIDTH_M) ?: original.widthM
                val replacement = gateAt(m, name.text.toString().trim().ifBlank { original.name }, original.type, w)
                val idx = gates.indexOf(original); if (idx >= 0) gates[idx] = replacement
                gates.sortBy { it.routeM }
                if (courseType == TYPE_CLOSED && replacement.type == "START") syncClosedLoopFinish(persist = true) else draft?.let { drafts.writeTraps(it.id, gates) }
                selectedRouteM = replacement.routeM; refreshUi(false)
            }
            .setNeutralButton("삭제") { _, _ ->
                gates.remove(original)
                if (courseType == TYPE_CLOSED && original.type == "START") gates.removeAll { it.type == "FINISH" }
                draft?.let { drafts.writeTraps(it.id, gates) }; refreshUi(false)
            }.setNegativeButton("취소", null).show()
    }

    private fun saveCourse() {
        if (points.size < 2 || (points.lastOrNull()?.routeM ?: 0.0) < 50.0) { Toast.makeText(this, "코스 길이가 너무 짧습니다.", Toast.LENGTH_SHORT).show(); return }
        if (courseType == TYPE_CLOSED) syncClosedLoopFinish(persist = true)
        if (gates.none { it.type == "START" } || gates.none { it.type == "FINISH" }) { Toast.makeText(this, "START와 FINISH를 먼저 지정해 주세요.", Toast.LENGTH_LONG).show(); return }
        val suggested = draft?.name ?: "TimeGate 코스"
        val input = EditText(this).apply { setText(if (suggested == "새 TimeGate 코스") "TimeGate 코스" else suggested); selectAll() }
        val typeLabel = if (courseType == TYPE_CLOSED) "폐쇄형 · START=FINISH" else "개방형 · START/FINISH 분리"
        AlertDialog.Builder(this).setTitle("코스 저장").setMessage("$typeLabel\nTimeGate의 GPX 코스 폴더에 저장합니다.").setView(input)
            .setPositiveButton("저장") { _, _ ->
                runCatching {
                    val tmp = File(cacheDir, "race_track_${System.currentTimeMillis()}.gpx")
                    val name = input.text.toString().trim().ifBlank { "TimeGate 코스" }
                    writeGpx(tmp, name)
                    val meta = repo.importGpxFile(tmp, name, enqueueServer = false)
                    tmp.delete(); savedMeta = meta; draft?.let { drafts.clearActive(it.id) }
                    status.setTextColor(GOOD); status.text = "✓ 저장 완료 · ${meta.name} · ${"%.2f".format(Locale.US, meta.totalKm)}km"
                    Toast.makeText(this, "코스를 저장하고 현재 코스로 선택했습니다.", Toast.LENGTH_LONG).show(); refreshUi(false)
                }.onFailure { e -> Toast.makeText(this, "저장 실패 · ${e.message}", Toast.LENGTH_LONG).show() }
            }.setNegativeButton("취소", null).show()
    }

    private fun publishCourse() {
        if (courseType == TYPE_CLOSED) syncClosedLoopFinish(persist = true)
        val meta = savedMeta ?: run { Toast.makeText(this, "먼저 코스를 휴대폰에 저장해 주세요.", Toast.LENGTH_SHORT).show(); return }
        val file = repo.sourceFile(meta.id) ?: run { Toast.makeText(this, "GPX 파일을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show(); return }
        btnPublish.isEnabled = false; status.setTextColor(Color.LTGRAY); status.text = "관리자 서버에 코스와 게이트 등록 중…"
        RaceCoursePublisher(sync).publishAsync(meta, file, gates.map { it.copy(widthM = it.widthM.coerceIn(MIN_GATE_WIDTH_M, MAX_GATE_WIDTH_M)) }) { result ->
            runOnUiThread { btnPublish.isEnabled = true; status.setTextColor(if (result.ok) GOOD else WARN); status.text = result.message + (result.serverCourseId?.let { " · 서버 코스 #$it" } ?: ""); Toast.makeText(this, result.message, Toast.LENGTH_LONG).show() }
        }
    }

    private fun writeGpx(target: File, name: String) {
        val sortedGates = gates.sortedBy { it.routeM }.map { it.copy(widthM = it.widthM.coerceIn(MIN_GATE_WIDTH_M, MAX_GATE_WIDTH_M)) }
        target.bufferedWriter(Charsets.UTF_8).use { w ->
            w.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            w.append("<gpx version=\"1.1\" creator=\"TimeGate\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            w.append("<metadata><name>${xml(name)}</name><desc>course_type=$courseType</desc></metadata>\n")
            sortedGates.forEach { g ->
                val type = when (g.type) { "START" -> "RACE_START"; "FINISH" -> "RACE_FINISH"; else -> "RACE_SECTOR" }
                w.append("<wpt lat=\"${fmt(g.lat)}\" lon=\"${fmt(g.lon)}\"><name>${xml(g.name)}</name><desc>bearing=${fmt(g.bearingDeg)};width=${fmt(g.widthM)};route_m=${fmt(g.routeM)};course_type=$courseType</desc><type>$type</type></wpt>\n")
            }
            w.append("<trk><name>${xml(name)}</name><trkseg>\n")
            points.forEach { p -> w.append("<trkpt lat=\"${fmt(p.lat)}\" lon=\"${fmt(p.lon)}\"><ele>${fmt(p.ele)}</ele><time>${Instant.ofEpochMilli(p.timeMs)}</time></trkpt>\n") }
            w.append("</trkseg></trk></gpx>\n")
        }
    }

    private fun restoreDraft() {
        val d = drafts.active()
        if (d != null && draft?.id != d.id) {
            draft = d; courseType = loadCourseType(d.id)
            points.clear(); points.addAll(drafts.points(d.id)); gates.clear(); gates.addAll(drafts.traps(d.id).map { it.copy(widthM = it.widthM.coerceIn(MIN_GATE_WIDTH_M, MAX_GATE_WIDTH_M)) })
            if (courseType == TYPE_CLOSED) syncClosedLoopFinish(persist = false)
            drafts.writeTraps(d.id, gates); selectedRouteM = points.lastOrNull()?.routeM
        } else if (d != null) {
            draft = d; courseType = loadCourseType(d.id); if (courseType == TYPE_CLOSED) syncClosedLoopFinish(persist = false)
        }
        refreshUi(d?.state == RaceTrackDraftStore.STATE_RECORDING)
    }

    private fun refreshUi(follow: Boolean) {
        if (courseType == TYPE_CLOSED) syncClosedLoopFinish(persist = false)
        val d = draft; val state = d?.state ?: "READY"; val dist = points.lastOrNull()?.routeM ?: d?.distanceM ?: 0.0
        val typeText = if (courseType == TYPE_CLOSED) "폐쇄형 · START = FINISH" else "개방형 · START / FINISH 분리"
        btnCourseType.text = "코스 형태 · $typeText  ▼"
        status.setTextColor(Color.LTGRAY)
        if (savedMeta == null || d != null) status.text = buildString {
            append(typeText).append('\n')
            append(when (state) {
                RaceTrackDraftStore.STATE_RECORDING -> "● 기록 중 · ${"%.2f".format(Locale.US, dist / 1000.0)}km · CP ${gates.count { it.type == "SECTOR" }}개"
                RaceTrackDraftStore.STATE_PAUSED -> "Ⅱ 일시정지 · 게이트를 수정할 수 있습니다."
                RaceTrackDraftStore.STATE_STOPPED -> "FINISH 완료 · 확인 후 코스를 저장하세요."
                else -> "START를 누르면 현재 위치부터 코스 제작을 시작합니다."
            })
            suggestedCpRouteM?.let { append("\n★ CP 추천 · ${"%.3f".format(Locale.US, it / 1000.0)}km · 감속/방향전환 감지") }
        }
        btnPause.text = if (state == RaceTrackDraftStore.STATE_PAUSED) "▶ 재개" else "Ⅱ 일시정지"
        btnPause.isEnabled = state == RaceTrackDraftStore.STATE_RECORDING || state == RaceTrackDraftStore.STATE_PAUSED
        btnRecord.isEnabled = state != RaceTrackDraftStore.STATE_RECORDING && state != RaceTrackDraftStore.STATE_PAUSED
        btnRecord.text = if (state == RaceTrackDraftStore.STATE_RECORDING || state == RaceTrackDraftStore.STATE_PAUSED) "START ✓" else "START"
        btnFinish.isEnabled = d != null && points.size >= 2 && state != RaceTrackDraftStore.STATE_STOPPED
        btnAddTrap.isEnabled = points.size >= 2 && state != RaceTrackDraftStore.STATE_STOPPED
        btnMapEdit.isEnabled = points.size >= 2 && gates.isNotEmpty(); btnSave.isEnabled = points.size >= 2
        btnPublish.visibility = if (sync.isAdminDeviceCached()) View.VISIBLE else View.GONE
        btnPublish.isEnabled = sync.isAdminDeviceCached() && savedMeta != null
        updateActionLabels(); updateSeekFromSelection(); updateSeekLabel(); renderTraps(); refreshMap(follow)
    }

    private fun updateActionLabels() {
        val next = gates.count { it.type == "SECTOR" } + 1
        btnAddTrap.text = "+ CP$next" + if (suggestedCpRouteM != null) " · 추천" else ""
    }

    private fun updateSeekFromSelection() {
        val total = points.lastOrNull()?.routeM ?: 0.0
        seek.isEnabled = points.size >= 2
        if (total > 0 && selectedRouteM != null) seek.progress = ((selectedRouteM!! / total) * 1000.0).toInt().coerceIn(0, 1000)
    }

    private fun saveCourseType(id: String) { getSharedPreferences(PREF_COURSE_TYPE, Context.MODE_PRIVATE).edit().putString("type_$id", courseType).apply() }
    private fun loadCourseType(id: String): String = getSharedPreferences(PREF_COURSE_TYPE, Context.MODE_PRIVATE).getString("type_$id", TYPE_OPEN)?.takeIf { it == TYPE_OPEN || it == TYPE_CLOSED } ?: TYPE_OPEN
    private fun updateSeekLabel() { val m = selectedRouteM ?: points.lastOrNull()?.routeM ?: 0.0; seekLabel.text = "게이트 위치 · ${"%.3f".format(Locale.US, m / 1000.0)} km" }
    private fun refreshMap(follow: Boolean) { map.render(points, gates, selectedRouteM, follow) }

    private fun registerUpdates() {
        if (receiverRegistered) return
        val f = IntentFilter(RaceTrackRecorderService.ACTION_UPDATE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(updateReceiver, f, Context.RECEIVER_NOT_EXPORTED) else @Suppress("DEPRECATION") registerReceiver(updateReceiver, f)
        receiverRegistered = true
    }
    private fun unregisterUpdates() { if (!receiverRegistered) return; runCatching { unregisterReceiver(updateReceiver) }; receiverRegistered = false }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    private fun fmt(v: Double) = String.format(Locale.US, "%.7f", v)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val GOOD = Color.rgb(80, 220, 120)
    private val WARN = Color.rgb(255, 130, 80)
}