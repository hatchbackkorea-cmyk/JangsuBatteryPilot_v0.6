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
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * TimeGate course builder.
 * START begins GPS recording and fixes START, +CP adds a manual CP at the selected/current point,
 * and FINISH fixes FINISH and stops recording. Pause is intentionally not part of the workflow.
 *
 * CP detection is recommendation-only. Suggested CPs are collected while recording and rebuilt
 * from the whole track when the large editor opens. They are visually distinct from manual CPs and
 * never become real timing gates until the user explicitly applies one.
 */
class RaceTrackBuilderActivity : Activity() {
    companion object {
        private const val REQ_LOCATION = 8841
        private const val DEFAULT_GATE_WIDTH_M = 5.0
        private const val MIN_GATE_WIDTH_M = 1.0
        private const val MAX_GATE_WIDTH_M = 20.0
        private const val PREF_COURSE_TYPE = "race_track_builder_course_type_v1"
        private const val PREF_CP_SOURCE = "race_track_builder_cp_source_v1"
        private const val TYPE_OPEN = "OPEN"
        private const val TYPE_CLOSED = "CLOSED"
        private const val MIN_CP_SPACING_M = 75.0
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
    private lateinit var btnFinish: Button
    private lateinit var btnAddTrap: Button
    private lateinit var btnSave: Button
    private lateinit var btnPublish: Button
    private lateinit var btnCourseType: Button
    private lateinit var btnMapEdit: Button

    private var draft: RaceTrackDraftStore.Draft? = null
    private val points = mutableListOf<RaceTrackDraftStore.Point>()
    private val gates = mutableListOf<RaceGate>()
    private val cpSuggestions = mutableListOf<RaceCpSuggestion>()
    private val recommendedGateKeys = mutableSetOf<Int>()
    private var selectedSuggestionIndex = -1
    private var selectedRouteM: Double? = null
    private var savedMeta: CourseMeta? = null
    private var receiverRegistered = false
    private var courseType = TYPE_OPEN
    private var pendingStartRouteM: Double? = null
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
                evaluateLatestCpSuggestion()
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
        bottomScroll.addView(body); root.addView(bottomScroll, LinearLayout.LayoutParams(-1, dp(356)))

        status = TextView(this).apply { textSize = 13f; setTextColor(Color.LTGRAY); setPadding(0, 0, 0, dp(8)) }
        body.addView(status)

        btnCourseType = Button(this).apply { isAllCaps = false; textSize = 14f; setTypeface(typeface, Typeface.BOLD); setOnClickListener { chooseCourseType() } }
        body.addView(btnCourseType, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(6) })

        btnMapEdit = Button(this).apply {
            isAllCaps = false; text = "↗ 큰 화면 편집 · 추천 CP 확인"; textSize = 14f; setTypeface(typeface, Typeface.BOLD)
            setOnClickListener { openFullscreenTrapEditor() }
        }
        body.addView(btnMapEdit, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(7) })

        val timingRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRecord = Button(this).apply { text = "START"; setTypeface(typeface, Typeface.BOLD); setOnClickListener { startNewDraft() } }
        btnAddTrap = Button(this).apply { text = "+ CP1"; setTypeface(typeface, Typeface.BOLD); setOnClickListener { addManualCp() } }
        btnFinish = Button(this).apply { text = "FINISH"; setTypeface(typeface, Typeface.BOLD); setOnClickListener { finishCourse() } }
        timingRow.addView(btnRecord, LinearLayout.LayoutParams(0, dp(50), 1f))
        timingRow.addView(btnAddTrap, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(6) })
        timingRow.addView(btnFinish, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(6) })
        body.addView(timingRow)

        seekLabel = TextView(this).apply { text = "게이트 위치"; textSize = 12f; setTextColor(Color.LTGRAY); setPadding(0, dp(8), 0, 0) }
        body.addView(seekLabel)
        seek = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser || points.isEmpty()) return
                    val total = points.last().routeM.coerceAtLeast(1.0)
                    selectedRouteM = total * progress / 1000.0
                    updateSeekLabel(); refreshMap(false)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        body.addView(seek)

        val saveRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnSave = Button(this).apply { text = "코스 저장"; setOnClickListener { saveCourse() } }
        btnPublish = Button(this).apply { text = "서버 등록"; setOnClickListener { publishCourse() } }
        saveRow.addView(btnSave, LinearLayout.LayoutParams(0, dp(48), 1f))
        saveRow.addView(btnPublish, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        body.addView(saveRow)

        body.addView(TextView(this).apply {
            text = "START → +CP → +CP… → FINISH. 수동 CP는 즉시 적용됩니다. 자동 감지 CP는 큰 화면 편집에서 보라색 추천 후보로만 표시되며, 선택 후 적용해야 실제 CP가 됩니다."
            textSize = 10.5f; setTextColor(Color.GRAY); setPadding(0, dp(7), 0, dp(4))
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
        rebuildCpSuggestions()
        if (gates.isEmpty()) { Toast.makeText(this, "START를 먼저 지정해 주세요.", Toast.LENGTH_SHORT).show(); return }

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(7, 16, 26)) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(6), dp(8), dp(6)); setBackgroundColor(Color.rgb(13, 21, 32)) }
        bar.addView(TextView(this).apply { text = "TimeGate 큰 화면 편집"; textSize = 19f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) }, LinearLayout.LayoutParams(0, dp(50), 1f))
        bar.addView(Button(this).apply { text = "완료"; isAllCaps = false; setTypeface(typeface, Typeface.BOLD); setOnClickListener { dialog.dismiss() } }, LinearLayout.LayoutParams(dp(88), dp(44)))
        root.addView(bar)
        root.addView(TextView(this).apply {
            text = "노랑 CP=수동 · 파랑 ◆R=추천에서 적용 · 보라 ★=아직 미적용 추천 · 각 표시를 눌러 선택할 수 있습니다."
            textSize = 12f; setTextColor(Color.rgb(220, 225, 240)); setPadding(dp(12), dp(7), dp(12), dp(7)); setBackgroundColor(Color.rgb(10, 28, 43))
        }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))

        lateinit var editorMap: RaceTrackBuilderMapView
        lateinit var refreshEditor: () -> Unit
        val suggestionList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val suggestionScroll = ScrollView(this).apply { addView(suggestionList) }
        val suggestionTitle = TextView(this).apply { textSize = 13f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); setPadding(dp(10), dp(6), dp(10), dp(4)) }
        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(4), dp(8), dp(6)) }
        val applySuggestion = Button(this).apply { text = "선택 추천 CP 적용"; isAllCaps = false; setTypeface(typeface, Typeface.BOLD) }
        val skipSuggestion = Button(this).apply { text = "선택 추천 제외"; isAllCaps = false }
        actionRow.addView(applySuggestion, LinearLayout.LayoutParams(0, dp(44), 1f))
        actionRow.addView(skipSuggestion, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6) })

        editorMap = RaceTrackBuilderMapView(this)
        editorMap.setTrapEditListener(RaceTrackBuilderMapView.TrapEditListener { index, lat, lon, bearingDeg, widthM ->
            if (index !in gates.indices) return@TrapEditListener
            val original = gates[index]
            if (courseType == TYPE_CLOSED && original.type == "FINISH") {
                Toast.makeText(this, "폐쇄형 FINISH는 START 위치에 자동 고정됩니다.", Toast.LENGTH_SHORT).show(); refreshEditor(); return@TrapEditListener
            }
            val wasRecommended = recommendedGateKeys.remove(routeKey(original.routeM))
            val replacement = original.copy(lat = lat, lon = lon, bearingDeg = ((bearingDeg % 360.0) + 360.0) % 360.0, widthM = widthM.coerceIn(MIN_GATE_WIDTH_M, MAX_GATE_WIDTH_M))
            gates[index] = replacement
            if (wasRecommended) recommendedGateKeys += routeKey(replacement.routeM)
            selectedRouteM = replacement.routeM
            if (courseType == TYPE_CLOSED && original.type == "START") syncClosedLoopFinish(persist = true) else draft?.let { drafts.writeTraps(it.id, gates) }
            saveRecommendedSources(); refreshEditor()
        })
        editorMap.setSuggestionTapListener(RaceTrackBuilderMapView.SuggestionTapListener { index ->
            if (index !in cpSuggestions.indices) return@SuggestionTapListener
            selectedSuggestionIndex = index
            selectedRouteM = cpSuggestions[index].routeM
            refreshEditor()
        })

        refreshEditor = {
            if (selectedSuggestionIndex !in cpSuggestions.indices) selectedSuggestionIndex = if (cpSuggestions.isNotEmpty()) 0 else -1
            suggestionTitle.text = "추천 CP ${cpSuggestions.size}개 · 눌러 선택 후 적용"
            suggestionList.removeAllViews()
            if (cpSuggestions.isEmpty()) {
                suggestionList.addView(TextView(this).apply { text = "현재 조건에서 추가 추천 CP가 없습니다."; setTextColor(Color.GRAY); textSize = 12f; setPadding(dp(12), dp(8), dp(12), dp(8)) })
            } else {
                cpSuggestions.forEachIndexed { index, s ->
                    suggestionList.addView(Button(this).apply {
                        isAllCaps = false; textSize = 11.5f; gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        text = "${if (index == selectedSuggestionIndex) "★ 선택 · " else "☆ 추천 ${index + 1} · "}${"%.3f".format(Locale.US, s.routeM / 1000.0)}km · 감속 ${Math.round(s.speedDropRatio * 100)}% · 회전 ${Math.round(s.turnDeg)}°"
                        setOnClickListener { selectedSuggestionIndex = index; selectedRouteM = s.routeM; refreshEditor() }
                    }, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(2) })
                }
            }
            applySuggestion.isEnabled = selectedSuggestionIndex in cpSuggestions.indices
            skipSuggestion.isEnabled = selectedSuggestionIndex in cpSuggestions.indices
            editorMap.render(points, gates, selectedRouteM, false, cpSuggestions, selectedSuggestionIndex, recommendedGateKeys)
        }

        applySuggestion.setOnClickListener {
            val idx = selectedSuggestionIndex
            if (idx !in cpSuggestions.indices) return@setOnClickListener
            val s = cpSuggestions[idx]
            val gate = addTrap("SECTOR", s.routeM, refresh = false)
            if (gate != null) {
                recommendedGateKeys += routeKey(gate.routeM)
                saveRecommendedSources()
                Toast.makeText(this, "추천 CP를 적용했습니다. 파란 ◆R로 표시됩니다.", Toast.LENGTH_SHORT).show()
            }
            cpSuggestions.removeAt(idx); selectedSuggestionIndex = -1; rebuildCpSuggestions(); refreshEditor(); refreshUi(false)
        }
        skipSuggestion.setOnClickListener {
            val idx = selectedSuggestionIndex
            if (idx !in cpSuggestions.indices) return@setOnClickListener
            cpSuggestions.removeAt(idx); selectedSuggestionIndex = -1; refreshEditor()
        }

        root.addView(editorMap, LinearLayout.LayoutParams(-1, 0, 1f))
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(13, 21, 32)); addView(suggestionTitle); addView(suggestionScroll, LinearLayout.LayoutParams(-1, 0, 1f)); addView(actionRow) }
        root.addView(panel, LinearLayout.LayoutParams(-1, dp(190)))
        dialog.setContentView(root)
        dialog.setOnShowListener { refreshEditor() }
        dialog.setOnDismissListener { editorMap.setTrapEditListener(null, false); editorMap.setSuggestionTapListener(null); refreshUi(false) }
        dialog.show()
    }

    private fun startNewDraft() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { ensureLocationPermission(); return }
        draft?.let { old -> if (old.state == RaceTrackDraftStore.STATE_RECORDING || old.state == RaceTrackDraftStore.STATE_PAUSED) sendAction(RaceTrackRecorderService.ACTION_STOP) }
        val d = drafts.start("새 TimeGate 코스")
        draft = d; points.clear(); gates.clear(); cpSuggestions.clear(); recommendedGateKeys.clear(); savedMeta = null; selectedRouteM = null
        pendingStartRouteM = 0.0; selectedSuggestionIndex = -1; lastSuggestedCpRouteM = -10_000.0
        saveCourseType(d.id); saveRecommendedSources()
        startForegroundService(Intent(this, RaceTrackRecorderService::class.java).apply { action = RaceTrackRecorderService.ACTION_START; putExtra(RaceTrackRecorderService.EXTRA_DRAFT_ID, d.id) })
        Toast.makeText(this, "START · 첫 GPS 진행방향으로 START 게이트를 만듭니다.", Toast.LENGTH_LONG).show()
        refreshUi(true)
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
        rebuildCpSuggestions()
        Toast.makeText(this, "FINISH 지정 완료 · 큰 화면 편집에서 추천 CP를 확인할 수 있습니다.", Toast.LENGTH_LONG).show()
        refreshUi(false)
    }

    private fun sendAction(a: String) { startService(Intent(this, RaceTrackRecorderService::class.java).apply { action = a }) }

    private fun addManualCp() {
        if (points.size < 2) { Toast.makeText(this, "GPS 포인트가 아직 부족합니다.", Toast.LENGTH_SHORT).show(); return }
        val m = selectedRouteM ?: points.last().routeM
        val gate = addTrap("SECTOR", m, refresh = false)
        gate?.let { recommendedGateKeys.remove(routeKey(it.routeM)) }
        cpSuggestions.removeAll { abs(it.routeM - m) < MIN_CP_SPACING_M }
        saveRecommendedSources(); refreshUi(false)
    }

    private fun addTrap(type: String, routeM: Double? = null, refresh: Boolean = true): RaceGate? {
        if (courseType == TYPE_CLOSED && type == "FINISH") return null
        val m = routeM ?: selectedRouteM ?: points.lastOrNull()?.routeM ?: return null
        val name = when (type) {
            "START" -> "START"
            "FINISH" -> "FINISH"
            else -> "CP${gates.count { it.type == "SECTOR" } + 1}"
        }
        if (type == "START") gates.removeAll { it.type == "START" }
        if (type == "FINISH") gates.removeAll { it.type == "FINISH" }
        val gate = gateAt(m, name, type, DEFAULT_GATE_WIDTH_M)
        gates += gate; gates.sortBy { it.routeM }
        if (courseType == TYPE_CLOSED && type == "START") syncClosedLoopFinish(persist = true) else draft?.let { drafts.writeTraps(it.id, gates) }
        if (refresh) refreshUi(false)
        return gate
    }

    private fun evaluateLatestCpSuggestion() {
        val d = draft ?: return
        if (d.state != RaceTrackDraftStore.STATE_RECORDING || points.size < 8) return
        val centerM = (points.last().routeM - 10.0).coerceAtLeast(0.0)
        if (centerM - lastSuggestedCpRouteM < MIN_CP_SPACING_M) return
        val candidate = cpCandidate(centerM) ?: return
        if (gates.any { abs(it.routeM - candidate.routeM) < MIN_CP_SPACING_M }) return
        if (cpSuggestions.any { abs(it.routeM - candidate.routeM) < MIN_CP_SPACING_M }) return
        cpSuggestions += candidate; lastSuggestedCpRouteM = candidate.routeM
    }

    private fun rebuildCpSuggestions() {
        if (points.size < 8) { cpSuggestions.clear(); selectedSuggestionIndex = -1; return }
        val out = mutableListOf<RaceCpSuggestion>()
        val total = points.last().routeM
        var m = 55.0
        while (m <= total - 45.0 && out.size < 24) {
            val c = cpCandidate(m)
            if (c != null && gates.none { abs(it.routeM - c.routeM) < MIN_CP_SPACING_M } && out.none { abs(it.routeM - c.routeM) < MIN_CP_SPACING_M }) out += c
            m += 15.0
        }
        cpSuggestions.clear(); cpSuggestions += out.sortedByDescending { it.score }.take(16).sortedBy { it.routeM }
        selectedSuggestionIndex = if (cpSuggestions.isNotEmpty()) 0 else -1
    }

    private fun cpCandidate(centerM: Double): RaceCpSuggestion? {
        if (points.size < 6) return null
        val a = points[indexNearRoute((centerM - 24.0).coerceAtLeast(0.0))]
        val b = points[indexNearRoute(centerM)]
        val c = points[indexNearRoute((centerM + 16.0).coerceAtMost(points.last().routeM))]
        if (a.timeMs >= b.timeMs || b.timeMs >= c.timeMs || a.routeM >= b.routeM || b.routeM >= c.routeM) return null
        val before = speedMps(a, b); val after = speedMps(b, c)
        if (before < 2.5) return null
        val drop = (1.0 - after / before.coerceAtLeast(0.1)).coerceIn(-2.0, 1.0)
        val turn = angleDiff(bearingBetween(a, b), bearingBetween(b, c))
        val strong = (drop >= 0.42 && turn >= 18.0) || drop >= 0.57 || turn >= 52.0
        if (!strong) return null
        val score = drop.coerceAtLeast(0.0) * 100.0 + turn * 0.55
        return RaceCpSuggestion(b.routeM, b.lat, b.lon, bearingAt(indexNearRoute(b.routeM)), drop.coerceAtLeast(0.0), turn, score)
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

    private fun angleDiff(a: Double, b: Double): Double = abs(((b - a + 540.0) % 360.0) - 180.0).coerceIn(0.0, 180.0)

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

    private fun indexNearRoute(routeM: Double): Int {
        if (points.isEmpty()) return 0
        var lo = 0; var hi = points.lastIndex
        while (lo + 1 < hi) {
            val mid = (lo + hi) ushr 1
            if (points[mid].routeM <= routeM) lo = mid else hi = mid
        }
        return if (abs(points[lo].routeM - routeM) <= abs(points[hi].routeM - routeM)) lo else hi
    }

    private fun nearestPointIndex(routeM: Double): Int = indexNearRoute(routeM)

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
            val source = if (gate.type == "SECTOR" && recommendedGateKeys.contains(routeKey(gate.routeM))) "추천 적용" else if (gate.type == "SECTOR") "수동" else ""
            val b = Button(this).apply {
                text = "${gate.name}${if (source.isNotBlank()) " · $source" else ""} · ${"%.3f".format(Locale.US, gate.routeM / 1000.0)}km · 폭 ${"%.1f".format(Locale.US, gate.widthM)}m · 방향 ${Math.round(gate.bearingDeg)}°${if (lockedFinish) " · START와 동일" else ""}"
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
                val wasRecommended = recommendedGateKeys.remove(routeKey(original.routeM))
                val replacement = gateAt(m, name.text.toString().trim().ifBlank { original.name }, original.type, w)
                val idx = gates.indexOf(original); if (idx >= 0) gates[idx] = replacement
                if (wasRecommended) recommendedGateKeys += routeKey(replacement.routeM)
                gates.sortBy { it.routeM }
                if (courseType == TYPE_CLOSED && replacement.type == "START") syncClosedLoopFinish(persist = true) else draft?.let { drafts.writeTraps(it.id, gates) }
                saveRecommendedSources(); selectedRouteM = replacement.routeM; refreshUi(false)
            }
            .setNeutralButton("삭제") { _, _ ->
                recommendedGateKeys.remove(routeKey(original.routeM)); gates.remove(original)
                if (courseType == TYPE_CLOSED && original.type == "START") gates.removeAll { it.type == "FINISH" }
                draft?.let { drafts.writeTraps(it.id, gates) }; saveRecommendedSources(); refreshUi(false)
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
                val source = if (g.type == "SECTOR" && recommendedGateKeys.contains(routeKey(g.routeM))) "recommended" else "manual"
                w.append("<wpt lat=\"${fmt(g.lat)}\" lon=\"${fmt(g.lon)}\"><name>${xml(g.name)}</name><desc>bearing=${fmt(g.bearingDeg)};width=${fmt(g.widthM)};route_m=${fmt(g.routeM)};course_type=$courseType;source=$source</desc><type>$type</type></wpt>\n")
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
            loadRecommendedSources(d.id)
            if (courseType == TYPE_CLOSED) syncClosedLoopFinish(persist = false)
            drafts.writeTraps(d.id, gates); selectedRouteM = points.lastOrNull()?.routeM
        } else if (d != null) {
            draft = d; courseType = loadCourseType(d.id); loadRecommendedSources(d.id); if (courseType == TYPE_CLOSED) syncClosedLoopFinish(persist = false)
        }
        if (d?.state == RaceTrackDraftStore.STATE_PAUSED) {
            sendAction(RaceTrackRecorderService.ACTION_RESUME)
            draft = drafts.setState(d.id, RaceTrackDraftStore.STATE_RECORDING) ?: draft
        }
        refreshUi(draft?.state == RaceTrackDraftStore.STATE_RECORDING)
    }

    private fun refreshUi(follow: Boolean) {
        if (courseType == TYPE_CLOSED) syncClosedLoopFinish(persist = false)
        val d = draft; val state = d?.state ?: "READY"; val dist = points.lastOrNull()?.routeM ?: d?.distanceM ?: 0.0
        val recording = state == RaceTrackDraftStore.STATE_RECORDING || state == RaceTrackDraftStore.STATE_PAUSED
        val typeText = if (courseType == TYPE_CLOSED) "폐쇄형 · START = FINISH" else "개방형 · START / FINISH 분리"
        btnCourseType.text = "코스 형태 · $typeText  ▼"
        status.setTextColor(Color.LTGRAY)
        if (savedMeta == null || d != null) status.text = buildString {
            append(typeText).append('\n')
            append(when {
                recording -> "● 기록 중 · ${"%.2f".format(Locale.US, dist / 1000.0)}km · 수동 CP ${gates.count { it.type == "SECTOR" && !recommendedGateKeys.contains(routeKey(it.routeM)) }}개 · 추천 후보 ${cpSuggestions.size}개"
                state == RaceTrackDraftStore.STATE_STOPPED -> "FINISH 완료 · 큰 화면 편집에서 추천 CP를 확인한 뒤 저장하세요."
                else -> "START를 누르면 현재 위치부터 코스 제작을 시작합니다."
            })
        }
        btnRecord.isEnabled = !recording
        btnRecord.text = if (recording) "START ✓" else "START"
        btnFinish.isEnabled = d != null && points.size >= 2 && state != RaceTrackDraftStore.STATE_STOPPED
        btnAddTrap.isEnabled = points.size >= 2
        btnMapEdit.isEnabled = points.size >= 2 && gates.isNotEmpty(); btnSave.isEnabled = points.size >= 2
        btnPublish.visibility = if (sync.isAdminDeviceCached()) View.VISIBLE else View.GONE
        btnPublish.isEnabled = sync.isAdminDeviceCached() && savedMeta != null
        updateActionLabels(); updateSeekFromSelection(); updateSeekLabel(); renderTraps(); refreshMap(follow)
    }

    private fun updateActionLabels() { btnAddTrap.text = "+ CP${gates.count { it.type == "SECTOR" } + 1}" }

    private fun updateSeekFromSelection() {
        val total = points.lastOrNull()?.routeM ?: 0.0
        seek.isEnabled = points.size >= 2
        if (total > 0 && selectedRouteM != null) seek.progress = ((selectedRouteM!! / total) * 1000.0).toInt().coerceIn(0, 1000)
    }

    private fun routeKey(routeM: Double): Int = routeM.roundToInt()

    private fun saveRecommendedSources() {
        val id = draft?.id ?: return
        getSharedPreferences(PREF_CP_SOURCE, Context.MODE_PRIVATE).edit().putString("recommended_$id", recommendedGateKeys.sorted().joinToString(",")).apply()
    }

    private fun loadRecommendedSources(id: String) {
        recommendedGateKeys.clear()
        val raw = getSharedPreferences(PREF_CP_SOURCE, Context.MODE_PRIVATE).getString("recommended_$id", "").orEmpty()
        raw.split(',').mapNotNull { it.trim().toIntOrNull() }.forEach { recommendedGateKeys.add(it) }
    }

    private fun saveCourseType(id: String) { getSharedPreferences(PREF_COURSE_TYPE, Context.MODE_PRIVATE).edit().putString("type_$id", courseType).apply() }
    private fun loadCourseType(id: String): String = getSharedPreferences(PREF_COURSE_TYPE, Context.MODE_PRIVATE).getString("type_$id", TYPE_OPEN)?.takeIf { it == TYPE_OPEN || it == TYPE_CLOSED } ?: TYPE_OPEN
    private fun updateSeekLabel() { val m = selectedRouteM ?: points.lastOrNull()?.routeM ?: 0.0; seekLabel.text = "게이트 위치 · ${"%.3f".format(Locale.US, m / 1000.0)} km" }
    private fun refreshMap(follow: Boolean) { map.render(points, gates, selectedRouteM, follow, emptyList(), -1, recommendedGateKeys) }

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
