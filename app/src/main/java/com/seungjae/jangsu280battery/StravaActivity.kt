package com.seungjae.jangsu280battery

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

class StravaActivity : Activity() {
    companion object { private const val REQ_FIT = 9101 }

    private lateinit var store: StravaSecureStore
    private lateinit var etSecret: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvFitPreview: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnPickFit: Button
    private lateinit var btnUpload: Button
    private lateinit var btnUploadOriginal: Button

    private var selectedFit: Uri? = null
    private var selectedAnalysis: HistoricalRideAnalysis? = null
    private var selectedOverlay: StravaRideOverlay? = null
    private var cleanBuild: StravaFitBuilder.Result? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_strava)
        applySystemInsets()
        store = StravaSecureStore(this)

        findViewById<Button>(R.id.btnStravaBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvStravaClientId).text = "Client ID ${StravaSecureStore.CLIENT_ID}"
        etSecret = findViewById(R.id.etStravaSecret)
        tvStatus = findViewById(R.id.tvStravaStatus)
        tvFitPreview = findViewById(R.id.tvStravaFitPreview)
        btnConnect = findViewById(R.id.btnStravaConnect)
        btnPickFit = findViewById(R.id.btnStravaPickFit)
        btnUpload = findViewById(R.id.btnStravaUpload)
        btnUploadOriginal = findViewById(R.id.btnStravaUploadOriginal)

        findViewById<Button>(R.id.btnStravaSaveSecret).setOnClickListener { saveSecret() }
        btnConnect.setOnClickListener { startOAuth() }
        findViewById<Button>(R.id.btnStravaDisconnect).setOnClickListener {
            store.clearTokens()
            refreshUi("연결을 해제했습니다. Client Secret은 기기에 남아 있습니다.")
        }
        btnPickFit.setOnClickListener { pickFit() }
        btnUpload.setOnClickListener { uploadClean() }
        btnUploadOriginal.setOnClickListener { uploadOriginal() }

        handleCallback(intent)
        refreshUi()
    }

    private fun applySystemInsets() {
        val root = findViewById<View>(R.id.stravaRoot)
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(baseLeft + bars.left, baseTop + bars.top, baseRight + bars.right, baseBottom + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            handleCallback(intent)
        }
    }

    private fun saveSecret(): Boolean {
        val secret = etSecret.text.toString().trim()
        if (secret.isBlank()) {
            Toast.makeText(this, "Strava Client Secret을 입력해 주세요.", Toast.LENGTH_SHORT).show()
            return false
        }
        store.saveClientSecret(secret)
        etSecret.text.clear()
        refreshUi("Client Secret을 이 휴대폰의 Android Keystore로 암호화해 저장했습니다.")
        return true
    }

    private fun startOAuth() {
        if (store.clientSecret().isNullOrBlank() && !saveSecret()) return
        val redirect = URLEncoder.encode(StravaSecureStore.REDIRECT_URI, "UTF-8")
        val scope = URLEncoder.encode("activity:read_all,activity:write", "UTF-8")
        val url = "https://www.strava.com/oauth/mobile/authorize" +
            "?client_id=${StravaSecureStore.CLIENT_ID}" +
            "&redirect_uri=$redirect" +
            "&response_type=code&approval_prompt=force&scope=$scope&state=jangsu"
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun handleCallback(intent: Intent) {
        val data = intent.data ?: return
        if (data.scheme != "jangsubatterypilot" || data.host != "localhost") return
        val error = data.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            refreshUi("Strava 승인이 취소되었습니다 · $error")
            return
        }
        val code = data.getQueryParameter("code") ?: return
        val granted = data.getQueryParameter("scope").orEmpty()
        val hasRead = granted.contains("activity:read") || granted.contains("activity:read_all")
        if (!hasRead) {
            refreshUi("ROAD 분석 권한(activity:read)이 허용되지 않았습니다.")
            return
        }
        val secret = store.clientSecret()
        if (secret.isNullOrBlank()) {
            refreshUi("Client Secret이 없어 인증 코드를 교환하지 못했습니다.")
            return
        }
        tvStatus.text = "Strava 인증 마무리 중…"
        Thread {
            val result = runCatching { StravaClient.exchangeCode(secret, code) }
            runOnUiThread {
                result.onSuccess {
                    store.saveTokens(it.accessToken, it.refreshToken, it.expiresAt, it.athleteName, granted)
                    refreshUi("Strava 연결 완료${it.athleteName?.let { n -> " · $n" } ?: ""}")
                }.onFailure { e -> refreshUi("Strava 연결 실패 · ${e.message ?: e.javaClass.simpleName}") }
            }
        }.start()
    }

    private fun pickFit() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(i, REQ_FIT)
    }

    @Deprecated("Deprecated in Android API, kept for min-dependency project")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FIT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        cleanBuild?.file?.delete()
        selectedFit = uri
        selectedAnalysis = null
        selectedOverlay = null
        cleanBuild = null
        tvFitPreview.text = "FIT + 최근 주행 BLE 로그 분석 중…"
        setUploadButtons(false)

        Thread {
            val result = runCatching {
                val analysis = HistoricalRideImporter.analyze(this, uri, HistoricalSourceType.FIT)
                val overlay = StravaRideFusion.findBestMatch(this, analysis)
                val clean = StravaFitBuilder.build(this, analysis, overlay)
                Triple(analysis, overlay, clean)
            }
            runOnUiThread {
                result.onSuccess { (analysis, overlay, clean) ->
                    selectedAnalysis = analysis
                    selectedOverlay = overlay
                    cleanBuild = clean
                    tvFitPreview.text = buildPreview(analysis, overlay, clean)
                    setUploadButtons(store.isConnected())
                }.onFailure { e ->
                    tvFitPreview.text = "FIT 분석/생성 실패 · ${e.message ?: e.javaClass.simpleName}\n원본 FIT 비교 업로드는 분석이 성공해야 사용할 수 있습니다."
                }
            }
        }.start()
    }

    private fun buildPreview(a: HistoricalRideAnalysis, overlay: StravaRideOverlay?, clean: StravaFitBuilder.Result): String {
        val stats = TelemetryMath.segmentStats(a.telemetry, 0.0, a.distanceKm)
        val riderMax = a.telemetry.mapNotNull { it.riderPowerW }.maxOrNull()
        val motorMax = a.telemetry.mapNotNull { it.motorPowerW }.maxOrNull()
        val hr = a.telemetry.mapNotNull { it.heartRateBpm }
        val hrAvg = hr.takeIf { it.isNotEmpty() }?.average()
        val hrMax = hr.maxOrNull()
        val battery = batteryRange(a, overlay)
        val modeShare = modeShare(a, overlay)

        return buildString {
            append("${a.displayName}\n")
            append("${fmt(a.distanceKm)} km · 상승 ${a.ascentM.roundToInt()}m")
            a.durationSec?.let { append(" · ${duration(it)}") }
            append("\n\n🚴 Rider  ${stats.avgRiderPowerW?.let(::watts) ?: "—"} avg / ${riderMax?.let(::watts) ?: "—"} max · ${(stats.riderWh * 3.6).roundToInt()} kJ")
            append("\n❤️ HR  ${hrAvg?.let { "${it.roundToInt()} bpm avg" } ?: "—"} / ${hrMax?.let { "${it.roundToInt()} max" } ?: "—"}")
            append("\n🔄 Cadence  ${stats.avgCadenceRpm?.let { "${it.roundToInt()} rpm avg" } ?: "—"}")
            append("\n⚡ Motor  ${stats.avgMotorPowerW?.let(::watts) ?: "—"} avg / ${motorMax?.let(::watts) ?: "—"} max · ${stats.motorWh.roundToInt()} Wh")
            battery?.let { append("\n🔋 Battery  ${it.first.roundToInt()}% → ${it.second.roundToInt()}%") }
            modeShare?.let { append("\n🎛 Mode  $it") }

            append("\n\n${clean.coverageText()}")
            if (overlay != null) {
                append("\n● 우리 BLE 로그 자동매칭 · 시작차 ${String.format(Locale.US, "%.1f", overlay.matchStartDeltaMin)}분 · 거리차 ${String.format(Locale.US, "%.1f", overlay.matchDistanceDeltaPct)}%")
            } else {
                append("\n○ 같은 주행의 우리 BLE 로그를 못 찾음 · 원본 FIT에 없는 배터리/모드는 비워둠")
            }
            append("\n\n클린 FIT: Rider Power를 표준 power로 유지하고 HR/Cadence/Motor/Battery/Mode를 가능한 한 모두 기록합니다.")
        }
    }

    private fun buildDescription(a: HistoricalRideAnalysis, overlay: StravaRideOverlay?, clean: Boolean): String {
        val s = TelemetryMath.segmentStats(a.telemetry, 0.0, a.distanceKm)
        val riderMax = a.telemetry.mapNotNull { it.riderPowerW }.maxOrNull()
        val motorMax = a.telemetry.mapNotNull { it.motorPowerW }.maxOrNull()
        val hr = a.telemetry.mapNotNull { it.heartRateBpm }
        val hrAvg = hr.takeIf { it.isNotEmpty() }?.average()
        val hrMax = hr.maxOrNull()
        val riderAvgW = s.avgRiderPowerW
        val motorAvgW = s.avgMotorPowerW
        val assist = if (riderAvgW != null && riderAvgW > 1.0 && motorAvgW != null) motorAvgW / riderAvgW else null
        val battery = batteryRange(a, overlay)
        val modes = modeShare(a, overlay)
        return buildString {
            append("⚡ Jangsu Battery Pilot · AMFLOW Ride Report")
            append("\n🚴 Rider: avg ${s.avgRiderPowerW?.let(::watts) ?: "—"} · max ${riderMax?.let(::watts) ?: "—"} · ${(s.riderWh * 3.6).roundToInt()} kJ")
            if (hr.isNotEmpty()) append("\n❤️ HR: avg ${hrAvg!!.roundToInt()} bpm · max ${hrMax!!.roundToInt()} bpm")
            s.avgCadenceRpm?.let { append("\n🔄 Cadence: avg ${it.roundToInt()} rpm") }
            append("\n⚡ Motor: avg ${s.avgMotorPowerW?.let(::watts) ?: "—"} · max ${motorMax?.let(::watts) ?: "—"} · ${s.motorWh.roundToInt()} Wh")
            assist?.let { append("\nAssist ratio: ${String.format(Locale.US, "%.2f", it)}×") }
            battery?.let { append("\n🔋 Battery: ${it.first.roundToInt()}% → ${it.second.roundToInt()}%") }
            modes?.let { append("\n🎛 Mode: $it") }
            append("\nDistance ${fmt(a.distanceKm)} km · Elevation +${a.ascentM.roundToInt()} m")
            append(if (clean) "\nSource: Avinox FIT + Jangsu BLE → Clean FIT" else "\nSource: Avinox original FIT → direct Strava upload (comparison)")
        }
    }

    private fun batteryRange(a: HistoricalRideAnalysis, overlay: StravaRideOverlay?): Pair<Double, Double>? {
        val fromOverlayA = overlay?.batteryStart()
        val fromOverlayB = overlay?.batteryEnd()
        if (fromOverlayA != null && fromOverlayB != null) return fromOverlayA to fromOverlayB
        val vals = a.telemetry.mapNotNull { it.ebikeBatteryLevelPercent ?: it.batterySocPercent }
        return if (vals.size >= 2) vals.first() to vals.last() else null
    }

    private fun modeShare(a: HistoricalRideAnalysis, overlay: StravaRideOverlay?): String? {
        overlay?.modeShareText()?.let { return it }
        val modes = a.telemetry.mapNotNull { p -> p.ebikeAssistMode?.let(::modeNameFromCode) }
        if (modes.isEmpty()) return null
        val total = modes.size.toDouble()
        return listOf("ECO", "AUTO", "TRAIL", "TURBO").mapNotNull { mode ->
            val n = modes.count { it == mode }
            if (n > 0) "$mode ${String.format(Locale.US, "%.0f", n / total * 100.0)}%" else null
        }.joinToString(" · ").takeIf { it.isNotBlank() }
    }

    private fun modeNameFromCode(code: Int): String? = when (code) {
        1 -> "ECO"
        2 -> "TRAIL"
        3 -> "TURBO"
        4 -> "AUTO"
        else -> null
    }

    private fun uploadClean() {
        val a = selectedAnalysis ?: return toast("먼저 Avinox FIT를 선택해 주세요.")
        val clean = cleanBuild ?: return toast("클린 FIT 생성이 끝난 뒤 업로드해 주세요.")
        if (!store.isConnected()) return toast("먼저 Strava를 연결해 주세요.")
        setUploadButtons(false)
        tvStatus.text = "클린 FIT Strava 업로드 중…"
        Thread {
            val result = runCatching {
                val token = StravaClient.ensureAccessToken(store)
                pollUpload(StravaClient.uploadFitFile(
                    clean.file,
                    token,
                    "AMFLOW PL · ${fmt(a.distanceKm)} km",
                    buildDescription(a, selectedOverlay, clean = true)
                ), token)
            }
            finishUpload(result)
        }.start()
    }

    private fun uploadOriginal() {
        val uri = selectedFit ?: return toast("먼저 Avinox FIT를 선택해 주세요.")
        val a = selectedAnalysis ?: return toast("FIT 분석이 끝난 뒤 업로드해 주세요.")
        if (!store.isConnected()) return toast("먼저 Strava를 연결해 주세요.")
        setUploadButtons(false)
        tvStatus.text = "원본 FIT 비교 업로드 중…"
        Thread {
            val result = runCatching {
                val token = StravaClient.ensureAccessToken(store)
                pollUpload(StravaClient.uploadFit(
                    this,
                    uri,
                    token,
                    "AMFLOW PL 원본비교 · ${fmt(a.distanceKm)} km",
                    buildDescription(a, selectedOverlay, clean = false)
                ), token)
            }
            finishUpload(result)
        }.start()
    }

    private fun pollUpload(initial: StravaClient.UploadResult, token: String): StravaClient.UploadResult {
        if (initial.error != null) error(initial.error)
        var up = initial
        var tries = 0
        while (up.activityId == null && tries < 20 && up.error == null) {
            Thread.sleep(1500L)
            up = StravaClient.uploadStatus(token, up.uploadId)
            tries++
        }
        return up
    }

    private fun finishUpload(result: Result<StravaClient.UploadResult>) {
        runOnUiThread {
            result.onSuccess { up ->
                if (up.error != null) refreshUi("업로드 처리 실패 · ${up.error}")
                else if (up.activityId != null) refreshUi("업로드 완료 · Strava 활동 ${up.activityId}")
                else refreshUi("업로드 접수 완료 · ${up.status}")
            }.onFailure { e -> refreshUi("업로드 실패 · ${e.message ?: e.javaClass.simpleName}") }
            setUploadButtons(store.isConnected() && selectedAnalysis != null)
        }
    }

    private fun setUploadButtons(enabled: Boolean) {
        btnUpload.isEnabled = enabled && cleanBuild != null
        btnUploadOriginal.isEnabled = enabled && selectedFit != null && selectedAnalysis != null
    }

    private fun refreshUi(extra: String? = null) {
        val connected = store.isConnected()
        val secretSaved = !store.clientSecret().isNullOrBlank()
        tvStatus.text = buildString {
            append(if (connected) "● Strava 연결됨" else "○ Strava 연결 안 됨")
            store.athleteName()?.let { append(" · $it") }
            append(if (secretSaved) "\nClient Secret · 기기 암호화 저장됨" else "\nClient Secret · 아직 저장 안 됨")
            if (!extra.isNullOrBlank()) append("\n$extra")
        }
        btnConnect.text = if (connected) "Strava 다시 인증" else "Strava 연결"
        setUploadButtons(connected && store.hasActivityWrite() && selectedAnalysis != null)
    }

    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    private fun fmt(v: Double) = String.format(Locale.US, "%.2f", v)
    private fun watts(v: Double) = "${v.roundToInt()} W"
    private fun duration(sec: Long): String {
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }
}
