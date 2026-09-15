package com.seungjae.jangsu280battery

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Broadcast-only camera enrollment.
 *
 * FIXED cameras keep the original GPS-to-course registration. CHASE is a moving default feed and
 * therefore registers without a fixed course coordinate. Both use the same low-latency video
 * transport and never receive START/CP/FINISH timing authority.
 */
class BroadcastCameraEnrollmentActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var codeInput: EditText
    private lateinit var status: TextView
    private lateinit var connectButton: Button
    private lateinit var chaseButton: Button
    private lateinit var locationManager: LocationManager
    private var pendingConnect = false
    private var locationListener: LocationListener? = null
    private var locationTimeout: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        setContentView(buildContent())

        val current = TimingOperatorStore.current(this)
        if (current != null && TimingOperatorStore.isBroadcastRole(current.role)) {
            codeInput.setText(current.eventCode)
            status.text = "현재 연결 · ${current.eventCode} · ${current.role}\n경기코드를 다시 입력하면 12시간 연결권한을 새로 시작합니다."
        }
    }

    private fun buildContent(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(32), dp(24), dp(26))
            setBackgroundColor(Color.rgb(247, 249, 252))
        }
        root.addView(TextView(this).apply {
            text = "중계 카메라"
            textSize = 30f
            setTextColor(Color.rgb(9, 18, 31))
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(TextView(this).apply {
            text = "경기코드만 입력한 뒤 카메라 역할을 선택하세요.\n고정 카메라는 현재 위치에 배치되고, 체이스 카메라는 경기 중 기본 메인 화면이 됩니다."
            textSize = 15f
            setTextColor(Color.rgb(78, 92, 111))
            setPadding(0, dp(10), 0, dp(22))
        }, LinearLayout.LayoutParams(-1, -2))

        codeInput = EditText(this).apply {
            hint = "경기코드 예: aB12cd"
            textSize = 24f
            gravity = Gravity.CENTER
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setPadding(dp(14), dp(15), dp(14), dp(15))
            background = rounded(Color.WHITE, Color.rgb(195, 205, 219), 12f)
        }
        root.addView(codeInput, LinearLayout.LayoutParams(-1, dp(66)))

        root.addView(TextView(this).apply {
            text = "대소문자는 구별하지 않습니다 · ab12cd = AB12CD"
            textSize = 13f
            setTextColor(Color.rgb(90, 107, 129))
            setPadding(dp(4), dp(8), 0, dp(16))
        }, LinearLayout.LayoutParams(-1, -2))

        connectButton = Button(this).apply {
            text = "📍 고정 / 포인트 중계카메라 연결"
            textSize = 16f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = rounded(Color.rgb(12, 91, 235), Color.rgb(12, 91, 235), 12f)
            setOnClickListener { startFixedConnect() }
        }
        root.addView(connectButton, LinearLayout.LayoutParams(-1, dp(58)))

        chaseButton = Button(this).apply {
            text = "🏍 체이스 카메라로 연결"
            textSize = 16f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = rounded(Color.rgb(20, 126, 82), Color.rgb(20, 126, 82), 12f)
            setOnClickListener { startChaseConnect() }
        }
        root.addView(chaseButton, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(10) })

        root.addView(TextView(this).apply {
            text = "체이스 카메라는 경기당 1대이며, CP/포인트 카메라가 방송권을 요청하지 않는 동안 기본 화면으로 사용됩니다."
            textSize = 12f
            setTextColor(Color.rgb(83, 101, 124))
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }, LinearLayout.LayoutParams(-1, -2))

        val openCurrent = Button(this).apply {
            text = "현재 중계 카메라 화면 열기"
            textSize = 15f
            setTextColor(Color.rgb(12, 91, 235))
            isAllCaps = false
            background = rounded(Color.WHITE, Color.rgb(156, 179, 214), 12f)
            setOnClickListener {
                val current = TimingOperatorStore.current(this@BroadcastCameraEnrollmentActivity)
                if (current != null && TimingOperatorStore.isBroadcastRole(current.role)) {
                    if (current.role.trim().uppercase(Locale.US) == "CHASE") {
                        openChaseSourcePicker(finishEnrollment = false)
                    } else {
                        startActivity(Intent(this@BroadcastCameraEnrollmentActivity, CameraGateHighSpeedActivity::class.java))
                    }
                } else {
                    status.text = "유효한 중계 카메라 연결이 없습니다. 경기코드를 입력해 주세요."
                }
            }
        }
        root.addView(openCurrent, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(10) })

        status = TextView(this).apply {
            text = "대기 중"
            textSize = 14f
            setTextColor(Color.rgb(48, 63, 84))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(20), dp(4), dp(8))
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        return root
    }

    private fun canonicalCode(): String? {
        val raw = codeInput.text?.toString().orEmpty().trim()
        if (raw.isBlank()) {
            status.text = "경기코드를 입력해 주세요."
            return null
        }
        val code = raw.uppercase(Locale.US)
        codeInput.setText(code)
        return code
    }

    private fun startFixedConnect() {
        canonicalCode() ?: return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pendingConnect = true
            status.text = "고정 중계카메라 자동배치에 GPS 권한이 필요합니다."
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
            return
        }
        acquireLocationAndRegister()
    }

    private fun startChaseConnect() {
        val code = canonicalCode() ?: return
        stopLocationWait()
        setButtonsEnabled(false)
        status.text = "${code} 확인 · CHASE 연결권한 발급 중…"
        executor.execute {
            val result = runCatching { BroadcastCameraClient.registerChase(applicationContext, code) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setButtonsEnabled(true)
                result.onSuccess { registered -> completeRegistration(registered, true) }
                    .onFailure { error -> status.text = "체이스 연결 실패 · ${error.message ?: error.javaClass.simpleName}" }
            }
        }
    }

    private fun acquireLocationAndRegister() {
        setButtonsEnabled(false)
        status.text = "GPS 위치 확인 중…"
        val cached = bestLastKnownLocation()
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.time <= 120_000L && (!cached.hasAccuracy() || cached.accuracy <= 120f)) {
            registerAt(cached)
            return
        }

        stopLocationWait()
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (location.latitude == 0.0 && location.longitude == 0.0) return
                if (location.hasAccuracy() && location.accuracy > 150f) {
                    status.text = "GPS 정밀도 개선 중… ±${location.accuracy.roundToInt()}m"
                    return
                }
                stopLocationWait()
                registerAt(location)
            }
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Deprecated in Android")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        locationListener = listener
        runCatching {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, listener, Looper.getMainLooper())
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 700L, 0f, listener, Looper.getMainLooper())
            }
        }.onFailure {
            setButtonsEnabled(true)
            status.text = "GPS를 시작할 수 없습니다 · ${it.message ?: "위치 설정을 확인해 주세요."}"
        }
        val timeout = Runnable {
            stopLocationWait()
            val fallback = bestLastKnownLocation()
            if (fallback != null) registerAt(fallback) else {
                setButtonsEnabled(true)
                status.text = "GPS 위치를 잡지 못했습니다. 위치 서비스를 켜고 다시 시도해 주세요."
            }
        }
        locationTimeout = timeout
        main.postDelayed(timeout, 9_000L)
    }

    private fun bestLastKnownLocation(): Location? {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
            .filter { it.latitude != 0.0 || it.longitude != 0.0 }
            .maxWithOrNull(compareBy<Location> { it.time }.thenBy { -(if (it.hasAccuracy()) it.accuracy else 9999f) })
    }

    private fun registerAt(location: Location) {
        stopLocationWait()
        val code = codeInput.text?.toString().orEmpty().trim().uppercase(Locale.US)
        status.text = "${code} 확인 · 고정 카메라 자동등록 중…"
        executor.execute {
            val result = runCatching { BroadcastCameraClient.register(applicationContext, code, location) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setButtonsEnabled(true)
                result.onSuccess { registered -> completeRegistration(registered, false) }
                    .onFailure { error -> status.text = "연결 실패 · ${error.message ?: error.javaClass.simpleName}" }
            }
        }
    }

    private fun completeRegistration(registered: BroadcastCameraClient.RegisterResult, chase: Boolean) {
        val base = runCatching { RaceServerClient(this).baseUrl().trim().trimEnd('/') }.getOrDefault("")
        TimingOperatorStore.save(
            this,
            TimingOperatorStore.Assignment(
                eventCode = registered.eventCode,
                role = registered.role,
                token = registered.leaseToken,
                expiresAtMs = registered.expiresAtMs,
                serverUrl = base,
            )
        )
        codeInput.setText(registered.eventCode)
        status.text = if (chase) {
            "연결 완료 · ${registered.eventCode} · CHASE\n영상 입력을 선택하세요. USB 액션캠은 현재 실험 기능입니다."
        } else {
            "연결 완료 · ${registered.eventCode} · ${registered.role}\n코스 ${(registered.routeM / 1000.0).format2()} km · GPS ±${registered.accuracyM.roundToInt()}m · 코스에서 ${registered.nearestM.roundToInt()}m"
        }
        main.postDelayed({
            if (!isFinishing && !isDestroyed) {
                if (chase) {
                    openChaseSourcePicker(finishEnrollment = true)
                } else {
                    startActivity(Intent(this, CameraGateHighSpeedActivity::class.java))
                    finish()
                }
            }
        }, 450L)
    }

    private fun openChaseSourcePicker(finishEnrollment: Boolean) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("CHASE 영상 입력")
            .setMessage("체이스 중계에 사용할 카메라를 선택하세요.")
            .setItems(arrayOf("📱 휴대폰 카메라", "🎥 USB 액션캠 · DJI Action 5 Pro")) { _, which ->
                val target = if (which == 1) UsbChaseCameraActivity::class.java else CameraGateHighSpeedActivity::class.java
                startActivity(Intent(this, target))
                if (finishEnrollment) finish()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        connectButton.isEnabled = enabled
        chaseButton.isEnabled = enabled
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_LOCATION) return
        if (grantResults.any { it == PackageManager.PERMISSION_GRANTED } && pendingConnect) {
            pendingConnect = false
            acquireLocationAndRegister()
        } else {
            pendingConnect = false
            setButtonsEnabled(true)
            status.text = "GPS 권한이 없으면 고정 중계카메라 위치를 자동 배치할 수 없습니다."
        }
    }

    private fun stopLocationWait() {
        locationTimeout?.let(main::removeCallbacks)
        locationTimeout = null
        locationListener?.let { listener -> runCatching { locationManager.removeUpdates(listener) } }
        locationListener = null
    }

    override fun onDestroy() {
        stopLocationWait()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(fill)
        setStroke(dp(1), stroke)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    private fun Double.format2(): String = String.format(Locale.US, "%.2f", this)

    companion object {
        private const val REQ_LOCATION = 4501
    }
}
