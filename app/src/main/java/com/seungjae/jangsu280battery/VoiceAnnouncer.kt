package com.seungjae.jangsu280battery

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class VoiceAnnouncer(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var lastPeriodicKm = 0.0
    private var lastPeriodicAtMs = System.currentTimeMillis()
    private var offCourseWarned = false
    private var lastRiskLabel = ""
    private val announced = mutableSetOf<String>()

    var enabled: Boolean = true
    var distanceIntervalKm: Int = AppSettings.DEFAULT_DISTANCE_INTERVAL_KM
    var timeIntervalMinutes: Int = AppSettings.DEFAULT_TIME_INTERVAL_MIN

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.KOREA)
            ready = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            tts.setSpeechRate(1.03f)
        }
    }

    fun configure(distanceKm: Int, timeMinutes: Int, routeKm: Double? = null) {
        distanceIntervalKm = distanceKm.coerceIn(0, 50)
        timeIntervalMinutes = timeMinutes.coerceIn(0, 120)
        if (routeKm != null) prime(routeKm)
    }

    fun prime(routeKm: Double) {
        lastPeriodicKm = routeKm.coerceAtLeast(0.0)
        lastPeriodicAtMs = System.currentTimeMillis()
    }

    fun reset() {
        lastPeriodicKm = 0.0
        lastPeriodicAtMs = System.currentTimeMillis()
        offCourseWarned = false
        lastRiskLabel = ""
        announced.clear()
        tts.stop()
    }

    fun speakNow(text: String) {
        if (!enabled || !ready) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "manual_${System.currentTimeMillis()}")
    }

    fun handle(
        routeKm: Double,
        battery: BatteryEstimate,
        checkpoint: Checkpoint?,
        poi: RoutePoi?,
        stats: ElevationStats,
        offCourseMeters: Double,
        reserve: ReserveStatus? = null,
        majorClimb: MajorClimb? = null,
        pacing: PacingAdvice? = null
    ) {
        if (!enabled || !ready) return

        if (offCourseMeters >= 150.0 && !offCourseWarned) {
            offCourseWarned = true
            enqueue("코스에서 약 ${offCourseMeters.roundToInt()}미터 벗어났습니다. 진행 방향을 확인하세요.")
        } else if (offCourseMeters <= 80.0) {
            offCourseWarned = false
        }

        if (reserve != null && reserve.label != lastRiskLabel) {
            lastRiskLabel = reserve.label
            when (reserve.label) {
                "위험" -> {
                    val pace = pacing?.takeIf { it.learned }?.voiceText?.let { " $it" }.orEmpty()
                    enqueue("배터리 위험 구간입니다. ${reserve.targetName} 예상 잔량 ${reserve.predictedPct.roundToInt()}퍼센트. 목표보다 ${(-reserve.differencePct).roundToInt()}퍼센트 부족합니다. 보조 강도를 줄이세요.$pace")
                }
                "주의" -> enqueue("배터리 주의 구간입니다. ${reserve.targetName} 예상 ${reserve.predictedPct.roundToInt()}퍼센트입니다.")
            }
        }

        if (checkpoint != null) {
            val remain = checkpoint.km - routeKm
            listOf(5.0, 2.0, 0.5).forEach { threshold ->
                if (remain in 0.0..threshold) {
                    val key = "cp_${checkpoint.km}_$threshold"
                    if (announced.add(key)) {
                        val distText = if (remain < 1.0) "${(remain * 1000).roundToInt()}미터" else "${String.format(Locale.US, "%.1f", remain)}킬로미터"
                        enqueue("${checkpoint.name}까지 $distText 남았습니다.")
                    }
                }
            }
            if (abs(remain) <= 0.12) {
                val key = "cp_arrive_${checkpoint.km}"
                if (announced.add(key)) {
                    if (checkpoint.chargeToPct != null) {
                        enqueue("${checkpoint.name}입니다. 계획상 도착 배터리 ${checkpoint.arrivalPct.roundToInt()}퍼센트. 사용자 설정 충전 목표는 ${checkpoint.chargeToPct.roundToInt()}퍼센트입니다. 앱의 현재 권장값도 확인하세요.")
                    } else if (checkpoint.name.contains("종점")) {
                        enqueue("종점입니다. 예상 배터리 약 ${checkpoint.arrivalPct.roundToInt()}퍼센트입니다.")
                    } else {
                        enqueue("${checkpoint.name}에 도착했습니다. 보급이나 충전이 필요하면 확인하세요.")
                    }
                }
            }
        }

        val segment = (routeKm / 25.0).toInt().coerceAtLeast(0)
        listOf(30, 20, 15).forEach { threshold ->
            if (battery.percent <= threshold) {
                val key = "battery_${segment}_$threshold"
                if (announced.add(key)) enqueue("예상 배터리가 ${threshold}퍼센트 이하입니다.")
            }
        }

        if (poi != null) {
            val remainPoi = poi.routeKm - routeKm
            if (remainPoi in 0.0..0.18) {
                val key = "poi_${poi.name}_${poi.routeKm.roundToInt()}"
                if (announced.add(key)) enqueue("잠시 후 ${poi.name}.")
            }
        }

        if (majorClimb != null) {
            val remainClimb = majorClimb.startKm - routeKm
            if (remainClimb in 0.0..1.0 && majorClimb.ascentM >= 120.0) {
                val key = "climb_${(majorClimb.startKm * 10).roundToInt()}"
                if (announced.add(key)) {
                    enqueue("약 ${String.format(Locale.US, "%.1f", remainClimb)}킬로미터 후 주요 업힐입니다. 길이 ${String.format(Locale.US, "%.1f", majorClimb.distanceKm)}킬로미터, 상승 약 ${majorClimb.ascentM.roundToInt()}미터입니다.")
                }
            }
        }

        val now = System.currentTimeMillis()
        val distanceDue = distanceIntervalKm > 0 && routeKm - lastPeriodicKm >= distanceIntervalKm - 0.02
        val timeDue = timeIntervalMinutes > 0 && now - lastPeriodicAtMs >= timeIntervalMinutes * 60_000L
        if (distanceDue || timeDue) {
            lastPeriodicKm = routeKm
            lastPeriodicAtMs = now
            val nextText = checkpoint?.let {
                val r = (it.km - routeKm).coerceAtLeast(0.0)
                " ${it.name}까지 ${String.format(Locale.US, "%.1f", r)}킬로미터."
            }.orEmpty()
            val terrain = when {
                stats.ascentM >= 550 -> " 앞으로 10킬로미터에 큰 오르막이 있습니다."
                stats.ascentM >= 350 -> " 앞으로 10킬로미터는 오르막 비중이 높습니다."
                stats.descentM >= 500 -> " 앞으로 10킬로미터는 다운힐 비중이 큽니다."
                else -> ""
            }
            val triggerText = when {
                distanceDue && timeDue -> "정기 안내입니다. "
                timeDue -> "시간 기준 안내입니다. "
                else -> ""
            }
            val batteryLabel = if (battery.calibrated) "실제값 반영 예상 배터리" else "예상 배터리"
            val pacingText = pacing?.takeIf { it.learned }?.voiceText?.let { " $it" }.orEmpty()
            enqueue("${triggerText}현재 ${String.format(Locale.US, "%.1f", routeKm)}킬로미터. $batteryLabel ${battery.percent.roundToInt()}퍼센트.$terrain$nextText$pacingText")
        }
    }

    fun summaryText(routeKm: Double, battery: BatteryEstimate, checkpoint: Checkpoint?, stats: ElevationStats, reserve: ReserveStatus? = null, pacing: PacingAdvice? = null): String {
        val kmText = String.format(Locale.US, "%.1f", routeKm)
        val cpText = checkpoint?.let {
            val remain = (it.km - routeKm).coerceAtLeast(0.0)
            " ${it.name}까지 ${String.format(Locale.US, "%.1f", remain)}킬로미터 남았습니다."
        }.orEmpty()
        val reserveText = reserve?.let { " 상태는 ${it.label}. ${it.targetName} 예상 ${it.predictedPct.roundToInt()}퍼센트입니다." }.orEmpty()
        val batteryLabel = if (battery.calibrated) "실제값을 반영한 예상 배터리" else "예상 배터리"
        val pacingText = pacing?.voiceText?.let { " $it" }.orEmpty()
        return "현재 $kmText 킬로미터. $batteryLabel ${battery.percent.roundToInt()}퍼센트. 앞으로 10킬로미터 상승 약 ${stats.ascentM.roundToInt()}미터.$cpText$reserveText$pacingText"
    }

    private fun enqueue(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "auto_${System.nanoTime()}")
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
