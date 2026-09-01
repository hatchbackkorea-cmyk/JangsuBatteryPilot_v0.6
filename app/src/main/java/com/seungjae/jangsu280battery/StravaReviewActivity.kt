package com.seungjae.jangsu280battery

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class StravaReviewActivity : Activity() {
    private lateinit var secure: StravaSecureStore
    private lateinit var reviewStore: StravaReviewStore
    private lateinit var etSecret: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvProgress: TextView
    private lateinit var tvCandidate: TextView
    private lateinit var tvYearSummary: TextView
    private lateinit var tvActive: TextView
    private lateinit var ridesContainer: LinearLayout
    private lateinit var prContainer: LinearLayout
    private lateinit var spinnerYear: Spinner
    private lateinit var btnAnalyze: Button
    private lateinit var btnApply: Button
    private lateinit var btnDiscard: Button
    private lateinit var btnUnlink: Button
    private var updatingYearSpinner = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_strava_review)
        applySystemInsets()
        secure = StravaSecureStore(this)
        reviewStore = StravaReviewStore(this)

        findViewById<Button>(R.id.btnStravaReviewBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvStravaReviewClientId).text = "Client ID ${StravaSecureStore.CLIENT_ID} · 기존 Strava 인증 설정 재사용"
        etSecret = findViewById(R.id.etStravaReviewSecret)
        tvStatus = findViewById(R.id.tvStravaReviewStatus)
        tvProgress = findViewById(R.id.tvStravaReviewProgress)
        tvCandidate = findViewById(R.id.tvStravaReviewCandidateSummary)
        tvYearSummary = findViewById(R.id.tvStravaReviewYearSummary)
        tvActive = findViewById(R.id.tvStravaReviewActive)
        ridesContainer = findViewById(R.id.llStravaReviewRides)
        prContainer = findViewById(R.id.llStravaReviewPr)
        spinnerYear = findViewById(R.id.spinnerStravaReviewYear)
        btnAnalyze = findViewById(R.id.btnStravaReviewAnalyze)
        btnApply = findViewById(R.id.btnStravaReviewApply)
        btnDiscard = findViewById(R.id.btnStravaReviewDiscard)
        btnUnlink = findViewById(R.id.btnStravaReviewUnlink)

        findViewById<Button>(R.id.btnStravaReviewSaveSecret).setOnClickListener { saveSecret() }
        findViewById<Button>(R.id.btnStravaReviewConnect).setOnClickListener { startOAuth() }
        findViewById<Button>(R.id.btnStravaReviewDisconnect).setOnClickListener {
            secure.clearTokens()
            refreshAll("Strava 인증 연결을 해제했습니다. 분석 후보/활성 프로필은 그대로 남겨뒀습니다.")
        }
        btnAnalyze.setOnClickListener { analyzeNow() }
        btnDiscard.setOnClickListener {
            reviewStore.discardCandidate()
            refreshAll("분석 후보를 폐기했습니다. 현재 활성 연동에는 영향이 없습니다.")
        }
        btnApply.setOnClickListener {
            runCatching { reviewStore.applyCandidate() }
                .onSuccess { refreshAll("${it.resolvedYear()}년 Strava 프로필을 내 라이더 데이터에 연동했습니다.") }
                .onFailure { Toast.makeText(this, it.message ?: "연동 실패", Toast.LENGTH_LONG).show() }
        }
        btnUnlink.setOnClickListener {
            reviewStore.clearActive()
            refreshAll("활성 Strava 분석 연동을 해제했습니다. 분석 후보는 그대로 남아 있습니다.")
        }
        spinnerYear.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (updatingYearSpinner) return
                val candidate = reviewStore.loadCandidate() ?: return
                val year = candidate.availableYears.getOrNull(position) ?: return
                if (year == candidate.resolvedYear()) return
                runCatching { reviewStore.selectCandidateYear(year) }
                    .onSuccess { refreshAll("${year}년 PR을 연동 후보로 선택했습니다.") }
            }
        }

        handleCallback(intent)
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            handleCallback(intent)
        }
    }

    private fun applySystemInsets() {
        val root = findViewById<View>(R.id.stravaReviewRoot)
        val l = root.paddingLeft; val t = root.paddingTop; val r = root.paddingRight; val b = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun saveSecret(): Boolean {
        val s = etSecret.text.toString().trim()
        if (s.isBlank()) {
            if (!secure.clientSecret().isNullOrBlank()) return true
            Toast.makeText(this, "Strava Client Secret을 입력해 주세요.", Toast.LENGTH_SHORT).show()
            return false
        }
        secure.saveClientSecret(s)
        etSecret.text.clear()
        refreshAll("Client Secret을 Android Keystore로 암호화 저장했습니다.")
        return true
    }

    private fun startOAuth() {
        if (secure.clientSecret().isNullOrBlank() && !saveSecret()) return
        val redirect = URLEncoder.encode(StravaSecureStore.REDIRECT_URI, "UTF-8")
        val scope = URLEncoder.encode("activity:read_all", "UTF-8")
        val url = "https://www.strava.com/oauth/mobile/authorize" +
            "?client_id=${StravaSecureStore.CLIENT_ID}" +
            "&redirect_uri=$redirect&response_type=code&approval_prompt=force&scope=$scope&state=road_review"
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun handleCallback(intent: Intent) {
        val data = intent.data ?: return
        if (data.scheme != "jangsubatterypilot" || data.host != "localhost" || !data.path.orEmpty().startsWith("/strava")) return
        val error = data.getQueryParameter("error")
        if (!error.isNullOrBlank()) { refreshAll("Strava 승인이 취소되었습니다 · $error"); return }
        val code = data.getQueryParameter("code") ?: return
        val granted = data.getQueryParameter("scope").orEmpty()
        if (!granted.contains("activity:read")) { refreshAll("activity:read 권한이 허용되지 않았습니다."); return }
        val secret = secure.clientSecret()
        if (secret.isNullOrBlank()) { refreshAll("Client Secret이 없어 인증 코드를 교환하지 못했습니다."); return }
        tvStatus.text = "Strava 인증 마무리 중…"
        Thread {
            val result = runCatching { StravaClient.exchangeCode(secret, code) }
            runOnUiThread {
                result.onSuccess {
                    secure.saveTokens(it.accessToken, it.refreshToken, it.expiresAt, it.athleteName, granted)
                    refreshAll("Strava 연결 완료${it.athleteName?.let { n -> " · $n" } ?: ""}")
                }.onFailure { e -> refreshAll("Strava 연결 실패 · ${e.message ?: e.javaClass.simpleName}") }
            }
        }.start()
    }

    private fun analyzeNow() {
        if (!secure.isConnected()) {
            Toast.makeText(this, "먼저 Strava를 연결해 주세요.", Toast.LENGTH_LONG).show(); return
        }
        val previous = reviewStore.loadCandidate()
        btnAnalyze.isEnabled = false
        btnApply.isEnabled = false
        tvProgress.text = if (previous != null && !previous.scanComplete) {
            "기존 분석 ${previous.analyzedActivityCount}/${previous.totalRoadActivities}에서 이어서 준비 중…"
        } else "Strava 전체 ROAD 목록 불러오는 중…"
        Thread {
            val result = runCatching {
                val token = StravaClient.ensureAccessToken(secure)
                StravaRoadReviewAnalyzer.analyze(token, secure.athleteName(), previous) { done, total, name ->
                    runOnUiThread {
                        tvProgress.text = if (total <= 0) name else "전체 분석 ${done.coerceAtMost(total)}/$total · $name"
                    }
                }
            }
            runOnUiThread {
                btnAnalyze.isEnabled = true
                result.onSuccess { p ->
                    reviewStore.saveCandidate(p)
                    val message = if (p.scanComplete) {
                        "전체 ROAD ${p.totalRoadActivities}개 분석 완료 · 연도를 선택한 뒤 연동하세요."
                    } else {
                        "${p.analyzedActivityCount}/${p.totalRoadActivities} 분석 저장 · ${p.stopReason ?: "나중에 계속 분석하세요."}"
                    }
                    refreshAll(message)
                }.onFailure { e ->
                    tvProgress.text = "분석 실패 · ${e.message ?: e.javaClass.simpleName}"
                    refreshButtons()
                }
            }
        }.start()
    }

    private fun refreshAll(message: String? = null) {
        tvStatus.text = if (secure.isConnected()) {
            "● Strava 연결됨${secure.athleteName()?.let { " · $it" } ?: ""} · 읽기권한 ${if (secure.hasActivityRead()) "OK" else "확인필요"}"
        } else "○ Strava 연결 안 됨"
        message?.let { tvProgress.text = it }
        val candidate = reviewStore.loadCandidate()
        val active = reviewStore.loadActive()
        tvCandidate.text = candidate?.let { profileSummary(it, false) } ?: "불러온 분석 후보가 없습니다."
        tvActive.text = active?.let { profileSummary(it, true) } ?: "Strava 분석 미연동 · 현재 페이스 계획에는 Strava 데이터가 적용되지 않습니다."
        bindYearSpinner(candidate)
        renderYear(candidate)
        renderRides(candidate)
        refreshButtons()
    }

    private fun bindYearSpinner(p: StravaRiderReviewProfile?) {
        val years = p?.availableYears.orEmpty()
        updatingYearSpinner = true
        spinnerYear.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, years.map { "${it}년 PR" })
        spinnerYear.isEnabled = years.isNotEmpty()
        val selectedYear = p?.resolvedYear() ?: 0
        val selectedIndex = years.indexOf(selectedYear).coerceAtLeast(0)
        if (years.isNotEmpty()) spinnerYear.setSelection(selectedIndex, false)
        updatingYearSpinner = false
    }

    private fun refreshButtons() {
        val candidate = reviewStore.loadCandidate()
        btnAnalyze.isEnabled = secure.isConnected()
        btnAnalyze.text = if (candidate != null && !candidate.scanComplete) "전체 ROAD 계속 분석" else "전체 ROAD 기록 스캔"
        btnApply.isEnabled = candidate != null && candidate.scanComplete && candidate.resolvedYear() > 0
        btnDiscard.isEnabled = candidate != null
        btnUnlink.isEnabled = reviewStore.loadActive() != null
    }

    private fun profileSummary(p: StravaRiderReviewProfile, active: Boolean): String = buildString {
        append(if (active) "● 연동됨" else if (p.scanComplete) "○ 전체 분석 완료 · 미적용" else "◐ 전체 분석 진행 중 · 미적용")
        p.athleteName?.let { append(" · $it") }
        append("\n전체 ROAD ${p.totalRoadActivities}개 · 분석 ${p.analyzedActivityCount}개")
        if (!p.scanComplete) append(" · 미분석 ${(p.totalRoadActivities - p.analyzedActivityCount).coerceAtLeast(0)}개")
        val year = p.resolvedYear()
        if (year > 0) {
            val yr = p.ridesForYear(year)
            append("\n선택 $year · ROAD ${yr.size}개 · 연속장거리 ${p.enduranceRides.size} · 구간/파워만 ${p.partialRides.size} · 제외 ${p.excludedRides.size}")
            p.referenceMovingSpeedKph(year)?.let { append(" · 참고 ${one(it)} km/h") }
        }
        append("\n보유 연도 ${if (p.availableYears.isEmpty()) "없음" else p.availableYears.joinToString(" · ")}")
        if (!p.scanComplete) append("\n${p.stopReason ?: "전체 분석을 계속 진행해 주세요."}")
        append("\n분석 ${dateTime(p.analyzedAtMs)}")
        if (active && p.linkedAtMs > 0) append(" · 연동 ${dateTime(p.linkedAtMs)}")
        if (active) append("\n※ ${year}년 프로필이 활성화되어 있으며 목표시간/평속/컷오프 값을 자동 덮어쓰지는 않습니다.")
    }

    private fun renderYear(p: StravaRiderReviewProfile?) {
        prContainer.removeAllViews()
        if (p == null || p.resolvedYear() <= 0) {
            tvYearSummary.text = "연도별 PR 분석 결과가 없습니다."
            return
        }
        val year = p.resolvedYear()
        val yearRides = p.ridesForYear(year)
        val powerRides = yearRides.count { it.hasPower }
        tvYearSummary.text = buildString {
            append("${year}년 · ROAD ${yearRides.size}개 · 파워기록 ${powerRides}개")
            p.referenceMovingSpeedKph(year)?.let { append(" · 연속장거리 참고평속 ${one(it)} km/h") }
            if (!p.scanComplete) append("\n※ 전체 스캔이 끝나기 전 PR은 임시값입니다.")
        }

        addPrHeader("${year}년 PR", prContainer)
        val yearly = p.prResults(year)
        if (yearly.isEmpty()) addPrEmpty(prContainer, "이 연도에는 분석 가능한 파워 스트림이 없습니다.")
        else yearly.forEach { addPrRow(prContainer, it) }

        addPrHeader("역대 PR 비교", prContainer)
        val allTime = p.allTimePrResults()
        if (allTime.isEmpty()) addPrEmpty(prContainer, "역대 PR 데이터가 없습니다.")
        else allTime.forEach { addPrRow(prContainer, it) }
    }

    private fun addPrHeader(text: String, parent: LinearLayout) {
        parent.addView(TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.accent))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(4))
        })
    }

    private fun addPrEmpty(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.text_secondary))
            textSize = 11f
            setPadding(dp(8), dp(5), dp(8), dp(5))
        })
    }

    private fun addPrRow(parent: LinearLayout, pr: StravaPrResult) {
        parent.addView(TextView(this).apply {
            setTextColor(getColor(R.color.text_primary))
            textSize = 11f
            setPadding(dp(9), dp(7), dp(9), dp(7))
            setBackgroundColor(getColor(R.color.panel2))
            text = "${pr.label}  ${pr.watts.roundToInt()}W  ·  ${pr.dateText}  ·  ${pr.rideName}"
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(4)
        })
    }

    private fun renderRides(p: StravaRiderReviewProfile?) {
        ridesContainer.removeAllViews()
        if (p == null || p.resolvedYear() <= 0) return
        val year = p.resolvedYear()
        val rides = p.ridesForYear(year).sortedByDescending { it.dateText }
        rides.forEach { r ->
            val tv = TextView(this).apply {
                setTextColor(getColor(R.color.text_primary))
                textSize = 11f
                setPadding(dp(9), dp(8), dp(9), dp(8))
                setBackgroundColor(getColor(R.color.panel2))
                val mark = when (r.use) { StravaRideUse.ENDURANCE -> "✅"; StravaRideUse.PARTIAL -> "⚠️"; StravaRideUse.EXCLUDED -> "⛔" }
                text = buildString {
                    append("$mark ${r.dateText} · ${r.name}\n")
                    append("${one(r.distanceKm)}km · +${r.ascentM.roundToInt()}m · 이동 ${duration(r.movingSec)} · 경과 ${duration(r.elapsedSec)}")
                    append("\n평속 ${one(r.movingAvgKph)}km/h · 정차 ${duration(r.totalStopSec)} · 최대정차 ${duration(r.longestStopSec)}")
                    r.avgPowerW?.let { append(" · avg ${it.roundToInt()}W") }
                    append("\n${r.reason}")
                }
            }
            ridesContainer.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(5)
            })
        }
    }

    private fun dateTime(ms: Long): String = if (ms <= 0) "—" else SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).format(Date(ms))
    private fun duration(secRaw: Double): String {
        val sec = secRaw.toLong().coerceAtLeast(0); val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }
    private fun one(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
