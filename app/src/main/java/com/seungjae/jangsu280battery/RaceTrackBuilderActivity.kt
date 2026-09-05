package com.seungjae.jangsu280battery

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
 * Phone RACE course builder inspired by RaceChrono's Create New Track workflow.
 * Raw GPS is recorded first. START/FINISH/Sector traps can be dropped while riding or edited
 * afterwards by moving them along the recorded route. Saving creates a normal local GPX so the
 * rest of Ride Copilot can use it immediately. Administrator phones may explicitly publish it.
 */
class RaceTrackBuilderActivity : Activity() {
    companion object {
        private const val REQ_LOCATION = 8841
        private const val DEFAULT_GATE_WIDTH_M = 5.0
        private const val MIN_GATE_WIDTH_M = 1.0
        private const val MAX_GATE_WIDTH_M = 20.0
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

    private var draft: RaceTrackDraftStore.Draft? = null
    private val points = mutableListOf<RaceTrackDraftStore.Point>()
    private val gates = mutableListOf<RaceGate>()
    private var selectedRouteM: Double? = null
    private var savedMeta: CourseMeta? = null
    private var receiverRegistered = false

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
            }
            refreshUi(follow = draft?.state == RaceTrackDraftStore.STATE_RECORDING)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        drafts = RaceTrackDraftStore(this)
        repo = CourseRepository(this)
        sync = RiderServerSync(this)
        buildUi()
        restoreDraft()
        ensureLocationPermission()
    }

    override fun onResume() {
        super.onResume()
        registerUpdates()
        restoreDraft()
    }

    override fun onPause() {
        unregisterUpdates()
        super.onPause()
    }

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
        top.addView(TextView(this).apply { text = "RACE 코스 만들기"; textSize = 22f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, dp(58), 1f))
        root.addView(top)

        map = RaceTrackBuilderMapView(this)
        root.addView(map, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottomScroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(11, 16, 23)) }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(16)) }
        bottomScroll.addView(body)
        root.addView(bottomScroll, LinearLayout.LayoutParams(-1, dp(330)))

        status = TextView(this).apply { textSize = 13f; setTextColor(Color.LTGRAY); setPadding(0, 0, 0, dp(8)) }
        body.addView(status)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRecord = Button(this).apply { text = "● 새 기록"; setOnClickListener { startNewDraft() } }
        btnPause = Button(this).apply { text = "Ⅱ 일시정지"; setOnClickListener { togglePause() } }
        btnFinish = Button(this).apply { text = "기록 종료"; setOnClickListener { stopRecording() } }
        row.addView(btnRecord, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(btnPause, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        row.addView(btnFinish, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        body.addView(row)

        seekLabel = TextView(this).apply { text = "트랩 위치 선택"; textSize = 12f; setTextColor(Color.LTGRAY); setPadding(0, dp(8), 0, 0) }
        body.addView(seekLabel)
        seek = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser || points.isEmpty()) return
                    val total = points.last().routeM.coerceAtLeast(1.0)
                    selectedRouteM = total * progress / 1000.0
                    refreshMap(false)
                    updateSeekLabel()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        body.addView(seek)

        val trapActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnAddTrap = Button(this).apply { text = "+ 트랩 추가"; setOnClickListener { chooseTrapType() } }
        btnSave = Button(this).apply { text = "코스 저장"; setOnClickListener { saveCourse() } }
        btnPublish = Button(this).apply { text = "서버 등록"; setOnClickListener { publishCourse() } }
        trapActions.addView(btnAddTrap, LinearLayout.LayoutParams(0, dp(48), 1f))
        trapActions.addView(btnSave, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        trapActions.addView(btnPublish, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        body.addView(trapActions)

        body.addView(TextView(this).apply {
            text = "산악 MTB 기준 트랩 폭 기본 5m · 조절 범위 1~20m. 주행 중 트랩을 찍거나 기록 종료 후 위치·이름·폭을 다시 편집할 수 있습니다."
            textSize = 10.5f; setTextColor(Color.GRAY); setPadding(0, dp(8), 0, dp(5))
        })
        trapContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(trapContainer)
    }

    private fun ensureLocationPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION)
        }
    }

    private fun startNewDraft() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ensureLocationPermission(); return
        }
        val input = EditText(this).apply { hint = "예: 백봉 MTB 코스"; setText("새 RACE 코스") }
        AlertDialog.Builder(this)
            .setTitle("새 코스 기록")
            .setMessage("GPS 원본을 먼저 기록합니다. START/FINISH/Sector 트랩은 주행 중 또는 종료 후에 추가할 수 있습니다.")
            .setView(input)
            .setPositiveButton("기록 시작") { _, _ ->
                draft?.let { old -> if (old.state == RaceTrackDraftStore.STATE_RECORDING) sendAction(RaceTrackRecorderService.ACTION_STOP) }
                val d = drafts.start(input.text.toString())
                draft = d; points.clear(); gates.clear(); savedMeta = null; selectedRouteM = null
                startForegroundService(Intent(this, RaceTrackRecorderService::class.java).apply { action = RaceTrackRecorderService.ACTION_START; putExtra(RaceTrackRecorderService.EXTRA_DRAFT_ID, d.id) })
                refreshUi(true)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun togglePause() {
        val d = draft ?: return
        when (d.state) {
            RaceTrackDraftStore.STATE_RECORDING -> sendAction(RaceTrackRecorderService.ACTION_PAUSE)
            RaceTrackDraftStore.STATE_PAUSED -> sendAction(RaceTrackRecorderService.ACTION_RESUME)
            else -> Toast.makeText(this, "새 기록을 시작해 주세요.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        val d = draft ?: return
        sendAction(RaceTrackRecorderService.ACTION_STOP)
        draft = drafts.setState(d.id, RaceTrackDraftStore.STATE_STOPPED)
        selectedRouteM = points.lastOrNull()?.routeM
        refreshUi(false)
    }

    private fun sendAction(a: String) {
        startService(Intent(this, RaceTrackRecorderService::class.java).apply { action = a })
    }

    private fun chooseTrapType() {
        if (points.size < 2) { Toast.makeText(this, "GPS 포인트가 아직 부족합니다.", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setTitle("트랩 추가").setItems(arrayOf("START", "SECTOR", "FINISH")) { _, which ->
            addTrap(arrayOf("START", "SECTOR", "FINISH")[which])
        }.show()
    }

    private fun addTrap(type: String) {
        val m = selectedRouteM ?: points.lastOrNull()?.routeM ?: return
        val name = when (type) {
            "START" -> "START"
            "FINISH" -> "FINISH"
            else -> "S${gates.count { it.type == "SECTOR" } + 1}"
        }
        if (type == "START") gates.removeAll { it.type == "START" }
        if (type == "FINISH") gates.removeAll { it.type == "FINISH" }
        gates += gateAt(m, name, type, DEFAULT_GATE_WIDTH_M)
        gates.sortBy { it.routeM }
        draft?.let { drafts.writeTraps(it.id, gates) }
        refreshUi(false)
    }

    private fun gateAt(routeM: Double, name: String, type: String, width: Double): RaceGate {
        val i = nearestPointIndex(routeM)
        val p = points[i]
        return RaceGate(name, type, p.routeM, p.lat, p.lon, bearingAt(i), width.coerceIn(MIN_GATE_WIDTH_M, MAX_GATE_WIDTH_M))
    }

    private fun nearestPointIndex(routeM: Double): Int {
        if (points.isEmpty()) return 0
        var best = 0; var bestD = Double.MAX_VALUE
        points.forEachIndexed { i, p -> val d = abs(p.routeM - routeM); if (d < bestD) { best = i; bestD = d } }
        return best
    }

    private fun bearingAt(i: Int): Double {
        val p = points[i]
        if (p.bearingDeg.isFinite()) return ((p.bearingDeg % 360.0) + 360.0) % 360.0
        val a = points[(i - 1).coerceAtLeast(0)]
        val b = points[(i + 1).coerceAtMost(points.lastIndex)]
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat); val dl = Math.toRadians(b.lon - a.lon)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun renderTraps() {
        trapContainer.removeAllViews()
        if (gates.isEmpty()) {
            trapContainer.addView(TextView(this).apply { text = "등록된 트랩 없음"; textSize = 11f; setTextColor(Color.GRAY) })
            return
        }
        gates.sortedBy { it.routeM }.forEach { gate ->
            val b = Button(this).apply {
                text = "${gate.type} · ${gate.name} · ${"%.3f".format(Locale.US, gate.routeM / 1000.0)}km · 폭 ${"%.1f".format(Locale.US, gate.widthM)}m"
                textSize = 12f; isAllCaps = false; gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { editTrap(gate) }
            }
            trapContainer.addView(b, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(3) })
        }
    }

    private fun editTrap(original: RaceGate) {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, dp(18), 0) }
        val name = EditText(this).apply { hint = "트랩 이름"; setText(original.name); setSingleLine(true) }
        val width = EditText(this).apply {
            hint = "폭(m) · 1~20"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("%.1f".format(Locale.US, original.widthM.coerceIn(MIN_GATE_WIDTH_M, MAX_GATE_WIDTH_M)))
        }
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
        AlertDialog.Builder(this).setTitle("${original.type} 트랩 편집").setView(root)
            .setPositiveButton("저장") { _, _ ->
                val m = total * slider.progress / 1000.0
                val w = width.text.toString().toDoubleOrNull()?.coerceIn(MIN_GATE_WIDTH_M, MAX_GATE_WIDTH_M) ?: original.widthM.coerceIn(MIN_GATE_WIDTH_M, MAX_GATE_WIDTH_M)
                val replacement = gateAt(m, name.text.toString().trim().ifBlank { original.name }, original.type, w)
                val idx = gates.indexOf(original); if (idx >= 0) gates[idx] = replacement
                gates.sortBy { it.routeM }; draft?.let { drafts.writeTraps(it.id, gates) }; selectedRouteM = replacement.routeM; refreshUi(false)
            }
            .setNeutralButton("삭제") { _, _ -> gates.remove(original); draft?.let { drafts.writeTraps(it.id, gates) }; refreshUi(false) }
            .setNegativeButton("취소", null).show()
    }

    private fun saveCourse() {
        if (points.size < 2 || (points.lastOrNull()?.routeM ?: 0.0) < 50.0) {
            Toast.makeText(this, "코스 길이가 너무 짧습니다.", Toast.LENGTH_SHORT).show(); return
        }
        if (gates.none { it.type == "START" } || gates.none { it.type == "FINISH" }) {
            Toast.makeText(this, "START와 FINISH 트랩을 먼저 지정해 주세요.", Toast.LENGTH_LONG).show(); return
        }
        val suggested = draft?.name ?: "RACE 코스"
        val input = EditText(this).apply { setText(suggested); selectAll() }
        AlertDialog.Builder(this).setTitle("코스 저장").setMessage("휴대폰의 기존 GPX 코스 저장 위치에 저장합니다.").setView(input)
            .setPositiveButton("저장") { _, _ ->
                runCatching {
                    val tmp = File(cacheDir, "race_track_${System.currentTimeMillis()}.gpx")
                    writeGpx(tmp, input.text.toString().trim().ifBlank { suggested })
                    val meta = repo.importGpxFile(tmp, input.text.toString().trim().ifBlank { suggested }, enqueueServer = false)
                    tmp.delete(); savedMeta = meta; draft?.let { drafts.clearActive(it.id) }
                    status.setTextColor(GOOD); status.text = "✓ 휴대폰 코스 저장 완료 · ${meta.name} · ${"%.2f".format(Locale.US, meta.totalKm)}km"
                    Toast.makeText(this, "코스가 저장되고 현재 코스로 선택되었습니다.", Toast.LENGTH_LONG).show()
                    refreshUi(false)
                }.onFailure { e -> Toast.makeText(this, "저장 실패 · ${e.message}", Toast.LENGTH_LONG).show() }
            }.setNegativeButton("취소", null).show()
    }

    private fun publishCourse() {
        val meta = savedMeta ?: run { Toast.makeText(this, "먼저 코스를 휴대폰에 저장해 주세요.", Toast.LENGTH_SHORT).show(); return }
        val file = repo.sourceFile(meta.id) ?: run { Toast.makeText(this, "GPX 파일을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show(); return }
        btnPublish.isEnabled = false
        status.setTextColor(Color.LTGRAY); status.text = "관리자 서버에 RACE 코스와 트랩 등록 중…"
        RaceCoursePublisher(sync).publishAsync(meta, file, gates.map { it.copy(widthM = it.widthM.coerceIn(MIN_GATE_WIDTH_M, MAX_GATE_WIDTH_M)) }) { result ->
            runOnUiThread {
                btnPublish.isEnabled = true
                status.setTextColor(if (result.ok) GOOD else WARN)
                status.text = result.message + (result.serverCourseId?.let { " · 서버 코스 #$it" } ?: "")
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun writeGpx(target: File, name: String) {
        val sortedGates = gates.sortedBy { it.routeM }.map { it.copy(widthM = it.widthM.coerceIn(MIN_GATE_WIDTH_M, MAX_GATE_WIDTH_M)) }
        target.bufferedWriter(Charsets.UTF_8).use { w ->
            w.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            w.append("<gpx version=\"1.1\" creator=\"Ride Copilot RACE\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            w.append("<metadata><name>${xml(name)}</name></metadata>\n")
            sortedGates.forEach { g ->
                val type = when (g.type) { "START" -> "RACE_START"; "FINISH" -> "RACE_FINISH"; else -> "RACE_SECTOR" }
                w.append("<wpt lat=\"${fmt(g.lat)}\" lon=\"${fmt(g.lon)}\"><name>${xml(g.name)}</name><desc>bearing=${fmt(g.bearingDeg)};width=${fmt(g.widthM)};route_m=${fmt(g.routeM)}</desc><type>$type</type></wpt>\n")
            }
            w.append("<trk><name>${xml(name)}</name><trkseg>\n")
            points.forEach { p ->
                w.append("<trkpt lat=\"${fmt(p.lat)}\" lon=\"${fmt(p.lon)}\"><ele>${fmt(p.ele)}</ele><time>${Instant.ofEpochMilli(p.timeMs).toString()}</time></trkpt>\n")
            }
            w.append("</trkseg></trk></gpx>\n")
        }
    }

    private fun restoreDraft() {
        val d = drafts.active()
        if (d != null && draft?.id != d.id) {
            draft = d
            points.clear(); points.addAll(drafts.points(d.id))
            gates.clear(); gates.addAll(drafts.traps(d.id).map { it.copy(widthM = it.widthM.coerceIn(MIN_GATE_WIDTH_M, MAX_GATE_WIDTH_M)) })
            drafts.writeTraps(d.id, gates)
            selectedRouteM = points.lastOrNull()?.routeM
        } else if (d != null) draft = d
        refreshUi(d?.state == RaceTrackDraftStore.STATE_RECORDING)
    }

    private fun refreshUi(follow: Boolean) {
        val d = draft
        val state = d?.state ?: "READY"
        val dist = points.lastOrNull()?.routeM ?: d?.distanceM ?: 0.0
        status.setTextColor(Color.LTGRAY)
        if (savedMeta == null || d != null) status.text = when (state) {
            RaceTrackDraftStore.STATE_RECORDING -> "● GPS 기록 중 · ${"%.2f".format(Locale.US, dist / 1000.0)}km · ${points.size} points"
            RaceTrackDraftStore.STATE_PAUSED -> "Ⅱ 일시정지 · 트랩을 추가/편집할 수 있습니다."
            RaceTrackDraftStore.STATE_STOPPED -> "기록 종료 · 트랩을 확인한 뒤 코스를 저장하세요."
            else -> "새 코스를 기록하거나 기존 RACE 코스 생성을 시작하세요."
        }
        btnPause.text = if (state == RaceTrackDraftStore.STATE_PAUSED) "▶ 재개" else "Ⅱ 일시정지"
        btnPause.isEnabled = state == RaceTrackDraftStore.STATE_RECORDING || state == RaceTrackDraftStore.STATE_PAUSED
        btnFinish.isEnabled = d != null && state != RaceTrackDraftStore.STATE_STOPPED
        btnAddTrap.isEnabled = points.size >= 2
        btnSave.isEnabled = points.size >= 2
        btnPublish.visibility = if (sync.isAdminDeviceCached()) View.VISIBLE else View.GONE
        btnPublish.isEnabled = sync.isAdminDeviceCached() && savedMeta != null
        val total = points.lastOrNull()?.routeM ?: 0.0
        seek.isEnabled = points.size >= 2
        if (total > 0 && selectedRouteM != null) seek.progress = ((selectedRouteM!! / total) * 1000.0).toInt().coerceIn(0, 1000)
        updateSeekLabel(); renderTraps(); refreshMap(follow)
    }

    private fun updateSeekLabel() {
        val m = selectedRouteM ?: points.lastOrNull()?.routeM ?: 0.0
        seekLabel.text = "트랩 위치 · ${"%.3f".format(Locale.US, m / 1000.0)} km"
    }

    private fun refreshMap(follow: Boolean) { map.render(points, gates, selectedRouteM, follow) }

    private fun registerUpdates() {
        if (receiverRegistered) return
        val f = IntentFilter(RaceTrackRecorderService.ACTION_UPDATE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(updateReceiver, f, Context.RECEIVER_NOT_EXPORTED) else @Suppress("DEPRECATION") registerReceiver(updateReceiver, f)
        receiverRegistered = true
    }

    private fun unregisterUpdates() {
        if (!receiverRegistered) return
        runCatching { unregisterReceiver(updateReceiver) }; receiverRegistered = false
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    private fun fmt(v: Double) = String.format(Locale.US, "%.7f", v)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val GOOD = Color.rgb(80, 220, 120)
    private val WARN = Color.rgb(255, 130, 80)
}
