package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoricalRideActivity : Activity() {
    companion object {
        private const val REQ_PICK_HISTORY = 4101
        const val EXTRA_AUTO_PICK_TYPE = "auto_pick_type"
    }

    private data class ManualPoint(val routeKm: Double, val percent: Double, val order: Int)

    private lateinit var learningStore: BatteryLearningStore
    private lateinit var rideStore: HistoricalRideStore
    private lateinit var logManager: RideLogManager
    private lateinit var dataStore: HistoricalRideDataStore

    private lateinit var panelAnalysis: View
    private lateinit var panelBattery: View
    private lateinit var tvAnalysis: TextView
    private lateinit var etStart: EditText
    private lateinit var etEnd: EditText
    private lateinit var etUsed: EditText
    private lateinit var llPoints: LinearLayout
    private lateinit var llRideList: LinearLayout
    private lateinit var tvRideCount: TextView
    private lateinit var btnTrain: Button

    private var pendingType = HistoricalSourceType.FIT
    private var pendingUris: List<android.net.Uri> = emptyList()
    private var analysis: HistoricalRideAnalysis? = null
    private val manualPoints = mutableListOf<ManualPoint>()
    private var nextPointOrder = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_historical_ride)
        LearningMigration.ensureV0110FreshStart(this)

        learningStore = BatteryLearningStore(this)
        rideStore = HistoricalRideStore(this)
        logManager = RideLogManager(this)
        dataStore = HistoricalRideDataStore(this)

        panelAnalysis = findViewById(R.id.panelHistoricalAnalysis)
        panelBattery = findViewById(R.id.panelHistoricalBattery)
        tvAnalysis = findViewById(R.id.tvHistoricalAnalysis)
        etStart = findViewById(R.id.etHistoricalStartBattery)
        etEnd = findViewById(R.id.etHistoricalEndBattery)
        etUsed = findViewById(R.id.etHistoricalUsedBattery)
        llPoints = findViewById(R.id.llHistoricalBatteryPoints)
        llRideList = findViewById(R.id.llHistoricalRideList)
        tvRideCount = findViewById(R.id.tvHistoricalRideCount)
        btnTrain = findViewById(R.id.btnTrainHistoricalRide)

        findViewById<Button>(R.id.btnHistoricalBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnImportFitHistory).setOnClickListener { pickFile(HistoricalSourceType.FIT) }
        findViewById<Button>(R.id.btnImportGpxHistory).setOnClickListener { pickFile(HistoricalSourceType.GPX) }
        findViewById<Button>(R.id.btnAddHistoricalBatteryPoint).setOnClickListener { showAddPointDialog() }
        btnTrain.setOnClickListener { trainSelectedRide() }

        if (logManager.isActive()) {
            findViewById<Button>(R.id.btnImportFitHistory).isEnabled = false
            findViewById<Button>(R.id.btnImportGpxHistory).isEnabled = false
            Toast.makeText(this, "주행 기록 중에는 과거 학습 데이터를 가져올 수 없습니다.", Toast.LENGTH_LONG).show()
        }
        renderStoredRides()
        if (!logManager.isActive()) {
            val auto = intent.getStringExtra(EXTRA_AUTO_PICK_TYPE)
            val type = runCatching { HistoricalSourceType.valueOf(auto.orEmpty()) }.getOrNull()
            if (type != null) {
                pendingType = type
                window.decorView.post { pickFile(type) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::rideStore.isInitialized) renderStoredRides()
    }

    @Deprecated("Deprecated in Android API, kept for min-dependency project")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_HISTORY || resultCode != RESULT_OK) return
        val uris = mutableListOf<android.net.Uri>()
        data?.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri
        }
        data?.data?.let { if (it !in uris) uris += it }
        if (uris.isEmpty()) return
        uris.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        analyzeUris(uris)
    }

    private fun pickFile(type: HistoricalSourceType) {
        if (logManager.isActive()) {
            Toast.makeText(this, "주행 종료 후 가져와 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        pendingType = type
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            this.type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, type == HistoricalSourceType.FIT)
        }
        startActivityForResult(intent, REQ_PICK_HISTORY)
    }

    private fun analyzeUris(uris: List<android.net.Uri>) {
        pendingUris = uris.distinct()
        panelAnalysis.visibility = View.VISIBLE
        panelBattery.visibility = View.GONE
        btnTrain.isEnabled = false
        tvAnalysis.text = if (pendingType == HistoricalSourceType.FIT && pendingUris.size > 1) {
            "FIT ${pendingUris.size}개를 시간·위치 순서로 검사하고 하나의 라이딩 세션으로 결합하는 중…"
        } else "파일 분석 중…"

        Thread {
            val result = runCatching {
                if (pendingType == HistoricalSourceType.FIT && pendingUris.size > 1) {
                    HistoricalRideImporter.analyzeMultipleFit(this, pendingUris)
                } else {
                    HistoricalRideImporter.analyze(this, pendingUris.first(), pendingType)
                }
            }
            runOnUiThread {
                result.onSuccess { a ->
                    analysis = a
                    // importer가 시간순으로 정렬한 실제 원본 URI 순서를 사용한다.
                    val orderedUris = a.sourceParts.mapNotNull { it.uri }
                    if (orderedUris.isNotEmpty()) pendingUris = orderedUris
                    manualPoints.clear()
                    nextPointOrder = 0
                    etStart.setText("100")
                    etEnd.text.clear()
                    etUsed.text.clear()
                    panelBattery.visibility = View.VISIBLE
                    btnTrain.isEnabled = true
                    tvAnalysis.text = buildString {
                        append(a.summaryText())
                        append("\n데이터 품질 ${a.dataQualityScore}%")
                        if (a.sourceParts.size > 1) {
                            append("\n\n결합 순서")
                            a.sourceParts.forEachIndexed { index, part ->
                                append("\n${index + 1}. ${part.displayName} · ${String.format(Locale.US, "%.2f", part.distanceKm)} km")
                            }
                            append("\n※ 파일 사이 휴식/전원 OFF 시간은 이동시간과 모터 에너지에 포함하지 않습니다.")
                        }
                        if (rideStore.findByHash(a.fileHash) != null) {
                            append("\n\nℹ 같은 세션의 이전 학습 기록이 있습니다. 다시 학습하면 새 분석값으로 교체합니다.")
                        }
                    }
                    renderManualPoints()
                }.onFailure { e ->
                    analysis = null
                    pendingUris = emptyList()
                    tvAnalysis.text = "분석 실패: ${e.message ?: e.javaClass.simpleName}"
                    panelBattery.visibility = View.GONE
                    btnTrain.isEnabled = false
                }
            }
        }.start()
    }

    private fun showAddPointDialog() {
        val a = analysis ?: run {
            Toast.makeText(this, "먼저 FIT 또는 GPX 파일을 불러오세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), 0)
        }
        val kmInput = EditText(this).apply {
            hint = "코스 km (0 ~ ${RideFormatter.one(a.distanceKm)})"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val pctInput = EditText(this).apply {
            hint = "그 지점의 배터리 %"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        container.addView(kmInput)
        container.addView(pctInput)

        AlertDialog.Builder(this)
            .setTitle("중간 배터리 / 충전 지점")
            .setMessage("중간 충전이 있었다면 같은 km에 도착 잔량을 먼저, 충전 후 잔량을 다음으로 추가하세요.")
            .setView(container)
            .setPositiveButton("추가", null)
            .setNegativeButton("취소", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val km = kmInput.text.toString().toDoubleOrNull()
                        val pct = pctInput.text.toString().toDoubleOrNull()
                        if (km == null || km !in 0.0..a.distanceKm || pct == null || pct !in 0.0..100.0) {
                            Toast.makeText(this, "km와 배터리 %를 확인해 주세요.", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        manualPoints += ManualPoint(km, pct, nextPointOrder++)
                        renderManualPoints()
                        dialog.dismiss()
                    }
                }
                dialog.show()
            }
    }

    private fun renderManualPoints() {
        llPoints.removeAllViews()
        if (manualPoints.isEmpty()) return
        manualPoints.sortedWith(compareBy<ManualPoint> { it.routeKm }.thenBy { it.order }).forEach { point ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
            }
            val pointText = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setTextColor(getColor(R.color.text_primary))
                textSize = 13f
                text = "${RideFormatter.one(point.routeKm)} km · ${formatPct(point.percent)}%"
            }
            val delete = Button(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(78), dp(44))
                text = "삭제"
                setOnClickListener {
                    manualPoints.remove(point)
                    renderManualPoints()
                }
            }
            row.addView(pointText)
            row.addView(delete)
            llPoints.addView(row)
        }
    }

    private fun trainSelectedRide() {
        val a = analysis ?: return
        // 같은 파일이라도 분석 알고리즘이 개선된 버전에서는 다시 학습할 수 있다.
        // 실제 교체는 사용자가 최종 확인에서 '학습에 사용'을 누른 뒤 수행한다.

        val startPct = etStart.text.toString().trim().toDoubleOrNull() ?: 100.0
        val directUsed = etUsed.text.toString().trim().toDoubleOrNull()
        val enteredEnd = etEnd.text.toString().trim().toDoubleOrNull()
        val sortedManual = manualPoints.sortedWith(compareBy<ManualPoint> { it.routeKm }.thenBy { it.order })
        val hasManualCharge = sortedManual.zipWithNext().any { (x, y) ->
            kotlin.math.abs(x.routeKm - y.routeKm) <= 0.02 && y.percent > x.percent + 0.5
        }
        if (startPct !in 1.0..100.0) {
            Toast.makeText(this, "시작 배터리는 1~100%로 입력해 주세요.", Toast.LENGTH_LONG).show()
            return
        }
        val endPct = when {
            directUsed != null -> startPct - directUsed
            enteredEnd != null -> enteredEnd
            else -> null
        }
        if (directUsed != null && directUsed !in 0.8..startPct) {
            Toast.makeText(this, "충전이 없는 기록의 총 사용 배터리는 0.8% 이상, 시작 배터리 이하로 입력해 주세요.", Toast.LENGTH_LONG).show()
            return
        }
        if (directUsed != null && hasManualCharge) {
            Toast.makeText(this, "중간 충전이 있는 기록은 총 사용량 대신 종료 배터리를 입력해 주세요. 충전별 소비량은 체크포인트에서 자동 계산합니다.", Toast.LENGTH_LONG).show()
            return
        }
        if (endPct == null || endPct !in 0.0..100.0 || (!hasManualCharge && endPct >= startPct - 0.7)) {
            Toast.makeText(this, "종료 배터리 또는 총 사용량을 확인해 주세요.", Toast.LENGTH_LONG).show()
            return
        }

        val baseTime = System.currentTimeMillis()
        fun timelineTime(km: Double, order: Int): Long {
            val nearest = a.telemetry.minByOrNull { kotlin.math.abs(it.routeKm - km) }?.timestampMs
            return (nearest ?: baseTime) + order
        }
        val orderedEntries = mutableListOf<ActualBatteryEntry>()
        orderedEntries += ActualBatteryEntry(startPct, 0.0, timelineTime(0.0, 0), ActualEntryKind.RIDING)
        sortedManual.forEachIndexed { index, p ->
            var kind = ActualEntryKind.RIDING
            val prev = sortedManual.getOrNull(index - 1)
            val next = sortedManual.getOrNull(index + 1)
            if (next != null && kotlin.math.abs(next.routeKm - p.routeKm) <= 0.02 && next.percent > p.percent + 0.5) {
                kind = ActualEntryKind.ARRIVAL
            } else if (prev != null && kotlin.math.abs(prev.routeKm - p.routeKm) <= 0.02 && p.percent > prev.percent + 0.5) {
                kind = ActualEntryKind.POST_CHARGE
            }
            orderedEntries += ActualBatteryEntry(p.percent, p.routeKm, timelineTime(p.routeKm, index + 1), kind)
        }
        orderedEntries += ActualBatteryEntry(endPct, a.distanceKm, timelineTime(a.distanceKm, sortedManual.size + 1), ActualEntryKind.RIDING)

        val sessionId = "history_v3_${a.fileHash}"
        val modeled = learningStore.baseConsumption(a.distanceKm, a.ascentM)
        val actualUsed = orderedEntries.zipWithNext().sumOf { (x, y) ->
            if (y.routeKm > x.routeKm + 0.05) (x.percent - y.percent).coerceAtLeast(0.0) else 0.0
        }
        val factor = if (modeled > 0.1) actualUsed / modeled else 1.0
        val message = buildString {
            append(a.summaryText())
            append("\n\n배터리 ${formatPct(startPct)}% → ${formatPct(endPct)}% · 소비구간 합계 ${formatPct(actualUsed)}%")
            if (manualPoints.isNotEmpty()) append("\n중간 기록 ${manualPoints.size}개 포함")
            append("\n중립 모델 대비 전체 소비 약 ×${String.format(Locale.US, "%.2f", factor)}")
            append("\n\n이 기록을 개인 배터리 예측 학습에 반영할까요?")
        }
        AlertDialog.Builder(this)
            .setTitle("학습 전 최종 확인")
            .setMessage(message)
            .setPositiveButton("학습에 사용") { _, _ ->
                // 동일 파일의 예전 파서 학습값이 있으면 먼저 제거하고 새 분석값으로 교체한다.
                rideStore.findByHash(a.fileHash)?.let { old ->
                    learningStore.removeSession(old.id)
                    rideStore.remove(old.id)
                }
                // 같은 v3 세션이 남아 있는 경우도 안전하게 제거한다.
                learningStore.removeSession(sessionId)
                val sourceUris = pendingUris
                val stored = try {
                    if (sourceUris.isNotEmpty()) dataStore.save(sourceUris, a, orderedEntries) else null
                } catch (e: Exception) {
                    Toast.makeText(this, "원본/시계열 저장 실패: ${e.message ?: "저장소 오류"}", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val expectedOriginals = a.sourceParts.size.coerceAtLeast(1)
                if (stored == null || stored.originals.size != expectedOriginals) {
                    Toast.makeText(this, "원본 FIT/GPX를 모두 보존하지 못해 학습을 취소했습니다. 파일 접근 권한을 확인해 주세요.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val count = learningStore.trainHistoricalRide(
                    sessionId = sessionId,
                    course = a.course,
                    entries = orderedEntries,
                    telemetry = a.telemetry,
                    qualityScore = a.dataQualityScore
                )
                if (count <= 0) {
                    learningStore.removeSession(sessionId)
                    dataStore.remove(a.fileHash)
                    Toast.makeText(this, "학습 가능한 구간을 만들지 못했습니다. 배터리 값과 거리를 확인해 주세요.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                rideStore.add(
                    HistoricalRideRecord(
                        id = sessionId,
                        fileHash = a.fileHash,
                        fileName = a.displayName,
                        sourceType = a.sourceType,
                        importedAtMs = System.currentTimeMillis(),
                        distanceKm = a.distanceKm,
                        ascentM = a.ascentM,
                        descentM = a.descentM,
                        durationSec = a.durationSec,
                        usedBatteryPct = actualUsed,
                        avgSpeedKph = a.avgSpeedKph,
                        sampleCount = count,
                        telemetryPointCount = a.telemetry.size,
                        dataQualityScore = a.dataQualityScore,
                        originalStored = stored?.originals?.size == a.sourceParts.size.coerceAtLeast(1),
                        fileCount = a.sourceParts.size.coerceAtLeast(1),
                        gapCount = a.gaps.size
                    )
                )
                Toast.makeText(this, "과거 라이딩을 ${count}개 학습 샘플로 반영했습니다.", Toast.LENGTH_LONG).show()
                btnTrain.isEnabled = false
                tvAnalysis.append("\n\n✅ 학습 반영 완료")
                renderStoredRides()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun renderStoredRides() {
        llRideList.removeAllViews()
        val records = rideStore.records()
        tvRideCount.text = "${records.size}개 라이딩 · 개인 학습 ${learningStore.samples().size}개 구간"
        if (records.isEmpty()) {
            llRideList.addView(TextView(this).apply {
                text = "아직 가져온 과거 라이딩이 없습니다."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(0, dp(8), 0, 0)
            })
            return
        }
        val dateFormat = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA)
        records.forEach { record ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(8))
            }
            val title = TextView(this).apply {
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                text = if (record.fileCount > 1) "${record.sourceType.label} ${record.fileCount}개 · ${record.fileName}" else "${record.sourceType.label} · ${record.fileName}"
            }
            val details = TextView(this).apply {
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                text = buildString {
                    append("${String.format(Locale.US, "%.2f", record.distanceKm)} km · +${record.ascentM.toInt()}m / -${record.descentM.toInt()}m")
                    record.durationSec?.takeIf { it > 0 }?.let { sec ->
                        val h = sec / 3600
                        val m = (sec % 3600) / 60
                        append(" · 이동 ${if (h > 0) "${h}시간 ${m}분" else "${m}분"}")
                    }
                    record.avgSpeedKph?.let { append(" · 평속 ${String.format(Locale.US, "%.1f", it)}km/h") }
                    append("\n배터리 ${formatPct(record.usedBatteryPct)}% 사용 · 학습 ${record.sampleCount}개 · 품질 ${record.dataQualityScore}%")
                    if (record.telemetryPointCount > 0) append(" · 시계열 ${record.telemetryPointCount}점")
                    if (record.gapCount > 0) append(" · 휴식/전원OFF ${record.gapCount}회")
                    append(" · ${dateFormat.format(Date(record.importedAtMs))}")
                }
            }
            val delete = Button(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(5) }
                text = "이 라이딩 학습 삭제"
                setOnClickListener { confirmDeleteRecord(record) }
            }
            row.addView(title)
            row.addView(details)
            row.addView(delete)
            llRideList.addView(row)
        }
    }

    private fun confirmDeleteRecord(record: HistoricalRideRecord) {
        AlertDialog.Builder(this)
            .setTitle("과거 라이딩 학습 삭제")
            .setMessage("${record.fileName}\n\n이 파일에서 만든 배터리 학습 샘플만 삭제합니다.")
            .setPositiveButton("삭제") { _, _ ->
                learningStore.removeSession(record.id)
                rideStore.remove(record.id)
                dataStore.remove(record.fileHash)
                renderStoredRides()
                Toast.makeText(this, "해당 라이딩의 학습 데이터를 삭제했습니다.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun formatPct(value: Double): String = if (kotlin.math.abs(value - value.toInt()) < 0.05) {
        value.toInt().toString()
    } else String.format(Locale.US, "%.1f", value)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
