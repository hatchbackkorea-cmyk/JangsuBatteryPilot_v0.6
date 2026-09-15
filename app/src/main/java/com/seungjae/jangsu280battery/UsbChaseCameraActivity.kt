package com.seungjae.jangsu280battery

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.base.CameraActivity
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IEncodeDataCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * Experimental USB UVC source for the CHASE role.
 *
 * DJI Action 5 Pro is opened as MJPEG 1280x720. AUSBC renders/decompresses that UVC preview and
 * feeds its own surface MediaCodec encoder. The resulting AVC packets are injected into the exact
 * same TimeGate broadcast bridge used by the phone camera, so WebRTC / warm standby / Director V2
 * do not need a separate transport implementation.
 *
 * This path is deliberately broadcast-only. It never participates in START/CP/FINISH timing and
 * never runs the point-camera local motion detector.
 */
class UsbChaseCameraActivity : CameraActivity() {
    private lateinit var root: LinearLayout
    private lateinit var cameraContainer: FrameLayout
    private lateinit var cameraView: AspectRatioTextureView
    private lateinit var statusText: TextView
    private lateinit var streamText: TextView

    private val main = Handler(Looper.getMainLooper())
    private val controlExecutor = Executors.newSingleThreadExecutor()
    private val packetCount = AtomicLong(0L)
    private val lastPacketUiMs = AtomicLong(0L)
    @Volatile private var streamStarted = false

    private val assignment: TimingOperatorStore.Assignment?
        get() = TimingOperatorStore.current(this)?.takeIf { it.role.trim().uppercase(Locale.US) == "CHASE" }

    private val activateTicker = object : Runnable {
        override fun run() {
            activateDirector()
            main.postDelayed(this, ACTIVATE_REFRESH_MS)
        }
    }

    private val encodeCallback = object : IEncodeDataCallBack {
        override fun onEncodeData(
            type: IEncodeDataCallBack.DataType,
            buffer: ByteBuffer,
            offset: Int,
            size: Int,
            timestamp: Long,
        ) {
            if (size <= 0) return
            if (type == IEncodeDataCallBack.DataType.AAC) return
            val end = offset + size
            if (offset < 0 || end > buffer.limit()) return
            val copy = buffer.duplicate().apply {
                position(offset)
                limit(end)
            }
            val bytes = ByteArray(size)
            copy.get(bytes)

            val kind = when (type) {
                IEncodeDataCallBack.DataType.H264_SPS -> CameraGateBroadcastStreamer.KIND_CSD0
                else -> CameraGateBroadcastStreamer.KIND_FRAME
            }
            val flags = if (type == IEncodeDataCallBack.DataType.H264_KEY) {
                CameraGateBroadcastStreamer.KEY_FRAME_FLAG
            } else 0
            val ptsUs = if (timestamp > 0L) timestamp else System.nanoTime() / 1_000L

            CameraGateBroadcastBridge.offer(
                CameraGateBroadcastPacket(
                    kind = kind,
                    ptsUs = ptsUs,
                    flags = flags,
                    data = bytes,
                    width = USB_WIDTH,
                    height = USB_HEIGHT,
                    mime = "video/avc",
                    fps = USB_ENCODE_FPS,
                    bitrate = USB_META_BITRATE,
                )
            )

            val count = packetCount.incrementAndGet()
            val now = android.os.SystemClock.elapsedRealtime()
            val prior = lastPacketUiMs.get()
            if (now - prior >= 500L && lastPacketUiMs.compareAndSet(prior, now)) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        streamText.text = "TimeGate 송출 · H.264 패킷 $count · ${USB_WIDTH}×${USB_HEIGHT} ${USB_ENCODE_FPS}fps"
                        streamText.setTextColor(Color.rgb(111, 232, 167))
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onCreate(savedInstanceState)
        if (assignment == null) {
            statusText.text = "CHASE 연결권한이 없습니다. 중계 카메라 메뉴에서 CHASE를 먼저 연결해 주세요."
            main.postDelayed({ finish() }, 1_600L)
        }
    }

    override fun getRootView(layoutInflater: LayoutInflater): View {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        top.addView(Button(this).apply {
            text = "←"
            textSize = 22f
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(60), dp(48)))
        top.addView(TextView(this).apply {
            text = "USB CHASE · ACTION CAM"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(top)

        cameraContainer = FrameLayout(this).apply { setBackgroundColor(Color.rgb(10, 10, 10)) }
        cameraView = AspectRatioTextureView(this)
        root.addView(cameraContainer, LinearLayout.LayoutParams(-1, 0, 1f))

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(18))
            setBackgroundColor(Color.rgb(13, 18, 24))
        }
        statusText = TextView(this).apply {
            text = "DJI Action 5 Pro 연결 대기 · USB에서 웹캠을 선택하세요."
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }
        info.addView(statusText, LinearLayout.LayoutParams(-1, -2))
        info.addView(TextView(this).apply {
            text = "입력 · UVC MJPG ${USB_WIDTH}×${USB_HEIGHT}\n처리 · USB MJPG → S25 디코드/렌더 → H.264 → TimeGate WebRTC"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(5), 0, dp(5))
        })
        streamText = TextView(this).apply {
            text = "TimeGate 송출 · 인코더 대기"
            textSize = 13f
            setTextColor(Color.rgb(255, 201, 92))
            setPadding(0, dp(3), 0, dp(8))
        }
        info.addView(streamText)
        info.addView(Button(this).apply {
            text = "📱 휴대폰 CHASE 카메라로 전환"
            isAllCaps = false
            setOnClickListener {
                startActivity(android.content.Intent(this@UsbChaseCameraActivity, CameraGateHighSpeedActivity::class.java))
                finish()
            }
        }, LinearLayout.LayoutParams(-1, dp(50)))
        root.addView(info, LinearLayout.LayoutParams(-1, -2))
        return root
    }

    override fun getCameraView(): IAspectRatio = cameraView

    override fun getCameraViewContainer(): ViewGroup = cameraContainer

    override fun getCameraRequest(): CameraRequest = CameraRequest.Builder()
        .setPreviewWidth(USB_WIDTH)
        .setPreviewHeight(USB_HEIGHT)
        .setRenderMode(CameraRequest.RenderMode.OPENGL)
        .setDefaultRotateType(RotateType.ANGLE_0)
        .setAudioSource(CameraRequest.AudioSource.NONE)
        .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG)
        .setAspectRatioShow(true)
        .setCaptureRawImage(false)
        .setRawPreviewData(false)
        .create()

    override fun onCameraState(
        self: MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?,
    ) {
        runOnUiThread {
            when (code) {
                ICameraStateCallBack.State.OPENED -> {
                    statusText.text = "USB 카메라 연결됨 · MJPG ${USB_WIDTH}×${USB_HEIGHT} · TimeGate 인코더 시작"
                    statusText.setTextColor(Color.rgb(111, 232, 167))
                    startEncodedStream()
                }
                ICameraStateCallBack.State.CLOSED -> {
                    streamStarted = false
                    statusText.text = "USB 카메라 연결 끊김 · 케이블을 다시 연결하세요."
                    statusText.setTextColor(Color.rgb(255, 190, 90))
                    streamText.text = "TimeGate 송출 · 대기"
                }
                ICameraStateCallBack.State.ERROR -> {
                    streamStarted = false
                    statusText.text = "USB 카메라 오류 · ${msg ?: "연결/포맷을 확인해 주세요."}"
                    statusText.setTextColor(Color.rgb(255, 105, 105))
                    streamText.text = "TimeGate 송출 · 오류"
                }
            }
        }
    }

    private fun startEncodedStream() {
        if (streamStarted) return
        streamStarted = true
        packetCount.set(0L)
        setEncodeDataCallBack(encodeCallback)
        captureStreamStart()
    }

    override fun onResume() {
        super.onResume()
        main.removeCallbacks(activateTicker)
        main.post(activateTicker)
    }

    override fun onPause() {
        main.removeCallbacks(activateTicker)
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacks(activateTicker)
        runCatching { captureStreamStop() }
        streamStarted = false
        controlExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun activateDirector() {
        val a = assignment ?: return
        controlExecutor.execute {
            runCatching { BroadcastDirectorClient.activate(applicationContext, a) }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val USB_WIDTH = 1280
        private const val USB_HEIGHT = 720
        private const val USB_ENCODE_FPS = 30
        private const val USB_META_BITRATE = 3_000_000
        private const val ACTIVATE_REFRESH_MS = 30_000L
    }
}
