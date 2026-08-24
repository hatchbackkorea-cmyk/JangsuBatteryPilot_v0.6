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
    }

    private data class ManualPoint(val routeKm: Double, val percent: Double, val order: Int)

    private lateinit var learningStore: BatteryLearningStore
    private lateinit var rideStore: HistoricalRideStore
    private lateinit var logManager: RideLogManager

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
    private var analysis: HistoricalRideAnalysis? = null
    private val manualPoints = mutableListOf<ManualPoint>()
    private var nextPointOrder = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_historical_ride)

        learningStore = BatteryLearningStore(this)
        rideStore = HistoricalRideStore(this)
        logManager = RideLogManager(this)

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
    }

    override fun onResume() {
        super.onResume()
        if (::rideStore.isInitialized) renderStoredRides()
    }

    @Deprecated("Deprecated in Android API, kept for min-dependency project")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_HISTORY || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        analyzeUri(uri)
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
        }
        startActivityForResult(intent, REQ_PICK_HISTORY)
    }

    private fun analyzeUri(uri: android.net.Uri) {
        panelAnalysis.visibility = View.VISIBLE
        panelBattery.visibility = View.GONE
        tvAnalysis.text = "${pendingType.label} 파일 분석 중…"
        analysis = null
        manualPoints.clear()
        renderManualPoints()
        btnTrain.isEnabled = false

        Thread {
            val result = runCatching { HistoricalRideImporter.analyze(this, uri, pendingType) }
            runOnUiThread {
                result.onSuccess { parsed ->
                    analysis = parsed
                    tvAnalysis.text = parsed.summaryText()
                    panelBattery.visibility = View.VISIBLE
                    etStart.setText("100")
                    etEnd.setText("")
                    etUsed.setText("")
                    val duplicate = rideStore.findByHash(parsed.fileHash)
                    btnTrain.isEnabled = true
                    if (duplicate != null) {
                        tvAnalysis.append("\n\nℹ 이전 분석/학습 기록이 있습니다. 다시 학습하면 기존 값을 새 분석값으로 교체합니다.")
                    }
                }.onFailure { e ->
                    panelBattery.visibility = View.GONE
                    tvAnalysis.text = "분석 실패 · ${e.message ?: "파일 형식을 확인해 주세요."}"
                    Toast.makeText(this, "파일 분석에 실패했습니다.", Toast.LENGTH_LONG).show()
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
        if (startPct !in 1.0..100.0) {
            Toast.makeText(this, "시작 배터리는 1~100%로 입력해 주세요.", Toast.LENGTH_LONG).show()
            return
        }
        val endPct = when {
            directUsed != null -> startPct - directUsed
            enteredEnd != null -> enteredEnd
            else -> null
        }
        if (directUsed != null && directUsed !in 0.8..100.0) {
            Toast.makeText(this, "총 사용 배터리는 0.8~100%로 입력해 주세요.", Toast.LENGTH_LONG).show()
            return
        }
        if (endPct == null || endPct !in 0.0..100.0 || endPct >= startPct - 0.7) {
            Toast.makeText(this, "종료 배터리 또는 총 사용량을 확인해 주세요.", Toast.LENGTH_LONG).show()
            return
        }

        val entries = mutableListOf<Pair<Int, ActualBatteryEntry>>()
        var seq = 0
        val baseTime = System.currentTimeMillis()
        entries += seq to ActualBatteryEntry(startPct, 0.0, baseTime + seq, ActualEntryKind.RIDING)
        seq++
        manualPoints.sortedWith(compareBy<ManualPoint> { it.routeKm }.thenBy { it.order }).forEach { p ->
            entries += seq to ActualBatteryEntry(p.percent, p.routeKm, baseTime + seq, ActualEntryKind.RIDING)
            seq++
        }
        entries += seq to ActualBatteryEntry(endPct, a.distanceKm, baseTime + seq, ActualEntryKind.RIDING)
        val orderedEntries = entries.sortedWith(compareBy<Pair<Int, ActualBatteryEntry>> { it.second.routeKm }.thenBy { it.first })
            .map { it.second }

        val sessionId = "history_v2_${a.fileHash}"
        val modeled = learningStore.baseConsumption(a.distanceKm, a.ascentM)
        val actualUsed = startPct - endPct
        val factor = if (modeled > 0.1) actualUsed / modeled else 1.0
        val message = buildString {
            append(a.summaryText())
            append("\n\n배터리 ${formatPct(startPct)}% → ${formatPct(endPct)}% · 사용 ${formatPct(actualUsed)}%")
            if (manualPoints.isNotEmpty()) append("\n중간 기록 ${manualPoints.size}개 포함")
            append("\n기본 모델 대비 전체 소비 약 ×${String.format(Locale.US, "%.2f", factor)}")
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
                // 같은 v2 세션이 남아 있는 경우도 안전하게 제거한다.
                learningStore.removeSession(sessionId)
                val count = learningStore.trainHistoricalRide(sessionId, a.course, orderedEntries)
                if (count <= 0) {
                    learningStore.removeSession(sessionId)
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
                        sampleCount = count
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
                text = "${record.sourceType.label} · ${record.fileName}"
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
                    append("\n배터리 ${formatPct(record.usedBatteryPct)}% 사용 · 학습 ${record.sampleCount}개 · ${dateFormat.format(Date(record.importedAtMs))}")
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
