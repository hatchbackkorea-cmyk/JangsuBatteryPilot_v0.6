package com.seungjae.jangsu280battery

import android.content.Context
import com.garmin.fit.Activity
import com.garmin.fit.ActivityMesg
import com.garmin.fit.DateTime
import com.garmin.fit.DeviceIndex
import com.garmin.fit.DeviceInfoMesg
import com.garmin.fit.Event
import com.garmin.fit.EventMesg
import com.garmin.fit.EventType
import com.garmin.fit.FileEncoder
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Fit
import com.garmin.fit.LapMesg
import com.garmin.fit.Manufacturer
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import com.garmin.fit.Sport
import com.garmin.fit.SubSport
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Produces a Strava-oriented FIT without inventing physiological or assist data.
 *
 * Avinox FIT remains the source of timestamp/GPS/elevation/speed/cadence/heart-rate/
 * rider-power/motor-power. When the matching Jangsu ride log exists, only the fields
 * that Avinox currently omits (real BLE battery SOC + rider-selected assist mode) are
 * overlaid by timestamp. The standard FIT `power` field is ALWAYS rider power.
 */
object StravaFitBuilder {
    data class Result(
        val file: File,
        val overlay: StravaRideOverlay?,
        val recordCount: Int,
        val riderPowerRecords: Int,
        val heartRateRecords: Int,
        val cadenceRecords: Int,
        val motorPowerRecords: Int,
        val batteryRecords: Int,
        val assistModeRecords: Int,
        val temperatureRecords: Int
    ) {
        fun coverageText(): String = buildString {
            append("FIT ${recordCount} records")
            append(" · Rider ${pct(riderPowerRecords)}")
            append(" · HR ${pct(heartRateRecords)}")
            append(" · Cad ${pct(cadenceRecords)}")
            append(" · Motor ${pct(motorPowerRecords)}")
            append(" · Battery ${pct(batteryRecords)}")
            append(" · Mode ${pct(assistModeRecords)}")
        }

        private fun pct(n: Int): String {
            if (recordCount <= 0) return "0%"
            return String.format(Locale.US, "%.0f%%", n * 100.0 / recordCount)
        }
    }

    fun build(context: Context, analysis: HistoricalRideAnalysis, overlay: StravaRideOverlay?): Result {
        require(analysis.sourceType == HistoricalSourceType.FIT) { "Strava 클린 FIT은 Avinox FIT에서 생성합니다." }
        val points = analysis.telemetry
            .filter { it.timestampMs != null }
            .sortedBy { it.timestampMs }
        require(points.size >= 2) { "FIT Record가 부족해서 클린 FIT을 만들 수 없습니다." }

        val outDir = File(context.cacheDir, "strava_clean").apply { mkdirs() }
        val output = File(outDir, "jangsu_clean_${System.currentTimeMillis()}.fit")

        val startSec = points.first().timestampMs!! / 1000L
        val endSec = points.last().timestampMs!! / 1000L
        val start = DateTime(startSec)
        val end = DateTime(endSec)
        val elapsedSec = (endSec - startSec).coerceAtLeast(1L).toFloat()
        val timerSec = analysis.durationSec?.takeIf { it > 0 }?.toFloat()?.coerceAtMost(elapsedSec) ?: elapsedSec
        val serial = (analysis.fileHash.take(8).toLongOrNull(16) ?: System.currentTimeMillis()).and(0xFFFFFFFFL)

        var riderN = 0
        var hrN = 0
        var cadenceN = 0
        var motorN = 0
        var batteryN = 0
        var modeN = 0
        var tempN = 0

        val encoder = FileEncoder(output, Fit.ProtocolVersion.V2_0)
        try {
            val fileId = FileIdMesg().apply {
                setType(com.garmin.fit.File.ACTIVITY)
                setManufacturer(Manufacturer.DEVELOPMENT)
                setProduct(1901)
                setSerialNumber(serial)
                setTimeCreated(start)
            }
            encoder.write(fileId)

            val deviceInfo = DeviceInfoMesg().apply {
                setDeviceIndex(DeviceIndex.CREATOR)
                setManufacturer(Manufacturer.DEVELOPMENT)
                setProduct(1901)
                setProductName("Jangsu Battery Pilot") // FIT limit: 20 chars
                setSerialNumber(serial)
                setSoftwareVersion(0.191f)
                setTimestamp(start)
            }
            encoder.write(deviceInfo)

            encoder.write(EventMesg().apply {
                setTimestamp(start)
                setEvent(Event.TIMER)
                setEventType(EventType.START)
            })

            points.forEach { p ->
                val record = RecordMesg()
                record.setTimestamp(DateTime(p.timestampMs!! / 1000L))

                if (p.lat in -90.0..90.0 && p.lon in -180.0..180.0) {
                    record.setPositionLat(degreesToSemicircles(p.lat))
                    record.setPositionLong(degreesToSemicircles(p.lon))
                }
                record.setDistance((p.routeKm.coerceAtLeast(0.0) * 1000.0).toFloat())
                p.elevationM?.takeIf { it.isFinite() && it in -1000.0..10000.0 }?.let {
                    record.setAltitude(it.toFloat())
                    record.setEnhancedAltitude(it.toFloat())
                }
                p.speedKph?.takeIf { it.isFinite() && it in 0.0..216.0 }?.let {
                    val mps = (it / 3.6).toFloat()
                    record.setSpeed(mps)
                    record.setEnhancedSpeed(mps)
                }
                p.heartRateBpm?.takeIf { it in 20.0..250.0 }?.let {
                    record.setHeartRate(it.roundToInt().coerceIn(1, 254).toShort())
                    hrN++
                }
                p.cadenceRpm?.takeIf { it in 0.0..254.0 }?.let {
                    record.setCadence(it.roundToInt().coerceIn(0, 254).toShort())
                    cadenceN++
                }
                // Critical rule: Strava/standard cycling power = HUMAN rider power only.
                p.riderPowerW?.takeIf { it in 0.0..65534.0 }?.let {
                    record.setPower(it.roundToInt().coerceIn(0, 65534))
                    riderN++
                }
                p.motorPowerW?.takeIf { it in 0.0..65534.0 }?.let {
                    record.setMotorPower(it.roundToInt().coerceIn(0, 65534))
                    motorN++
                }
                p.temperatureC?.takeIf { it in -127.0..127.0 }?.let {
                    record.setTemperature(it.roundToInt().coerceIn(-127, 127).toByte())
                    tempN++
                }

                val phone = overlay?.sample(p.timestampMs, p.routeKm, analysis.distanceKm)
                val battery = phone?.batteryPct
                    ?: p.ebikeBatteryLevelPercent
                    ?: p.batterySocPercent
                battery?.takeIf { it in 0.0..100.0 }?.let {
                    // Store both native e-bike battery fields. Strava may ignore these visually,
                    // but other FIT readers can consume them and the activity description repeats it.
                    record.setBatterySoc(it.toFloat())
                    record.setEbikeBatteryLevel(it.roundToInt().coerceIn(0, 100).toShort())
                    batteryN++
                }

                val modeCode = phone?.assistRawCode
                    ?: phone?.assistMode?.let(StravaRideFusion::modeCode)
                    ?: p.ebikeAssistMode
                modeCode?.takeIf { it in 0..254 }?.let {
                    record.setEbikeAssistMode(it.toShort())
                    modeN++
                }

                // Never invent an assist %. Preserve it only when the source FIT actually has it.
                p.ebikeAssistLevelPercent?.takeIf { it in 0.0..100.0 }?.let {
                    record.setEbikeAssistLevelPercent(it.roundToInt().coerceIn(0, 100).toShort())
                }
                encoder.write(record)
            }

            encoder.write(EventMesg().apply {
                setTimestamp(end)
                setEvent(Event.TIMER)
                setEventType(EventType.STOP_ALL)
            })

            encoder.write(LapMesg().apply {
                setMessageIndex(0)
                setTimestamp(end)
                setStartTime(start)
                setTotalElapsedTime(elapsedSec)
                setTotalTimerTime(timerSec)
                setTotalDistance((analysis.distanceKm * 1000.0).toFloat())
                setSport(Sport.CYCLING)
                setSubSport(SubSport.GENERIC)
            })

            encoder.write(SessionMesg().apply {
                setMessageIndex(0)
                setTimestamp(end)
                setStartTime(start)
                setTotalElapsedTime(elapsedSec)
                setTotalTimerTime(timerSec)
                setTotalDistance((analysis.distanceKm * 1000.0).toFloat())
                setSport(Sport.CYCLING)
                setSubSport(SubSport.GENERIC)
                setFirstLapIndex(0)
                setNumLaps(1)
            })

            encoder.write(ActivityMesg().apply {
                setTimestamp(end)
                setNumSessions(1)
                setTotalTimerTime(timerSec)
                setType(Activity.MANUAL)
                // Avoid fabricating local timezone. FIT timestamp remains UTC-based and Strava resolves it.
            })
        } finally {
            encoder.close()
        }

        require(output.exists() && output.length() > 64L) { "클린 FIT 생성에 실패했습니다." }
        return Result(
            file = output,
            overlay = overlay,
            recordCount = points.size,
            riderPowerRecords = riderN,
            heartRateRecords = hrN,
            cadenceRecords = cadenceN,
            motorPowerRecords = motorN,
            batteryRecords = batteryN,
            assistModeRecords = modeN,
            temperatureRecords = tempN
        )
    }

    private fun degreesToSemicircles(degrees: Double): Int {
        val scaled = degrees * 2147483648.0 / 180.0
        return scaled.roundToLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }
}
