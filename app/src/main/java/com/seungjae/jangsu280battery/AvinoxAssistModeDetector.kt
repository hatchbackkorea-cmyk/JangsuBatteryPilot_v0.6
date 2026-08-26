package com.seungjae.jangsu280battery

/**
 * Avinox selected assist-mode detector for the verified FFF4 long packet family.
 *
 * Repeated stationary switch test on 2026-08-27:
 *   ECO -> AUTO -> TRAIL -> TURBO -> TRAIL -> AUTO -> ECO ...
 * tracked byte[68] without a mismatch:
 *   1=ECO, 2=TRAIL, 3=TURBO, 4=AUTO.
 *
 * Important: this field is treated as the rider-selected mode only.
 * We do NOT infer AUTO's internal/effective assist level from this same byte.
 * A separate BLE field must be verified before showing AUTO · ECO/TRAIL/TURBO급.
 */
data class AvinoxAssistDetection(
    val primary: AvinoxAssistMode,
    val alternate: AvinoxAssistMode? = null,
    val rawCode: Int,
    val confidence: String,
    val note: String
) {
    val compatibleModes: Set<AvinoxAssistMode>
        get() = buildSet {
            add(primary)
            alternate?.let { add(it) }
        }

    val isHighConfidence: Boolean get() = confidence == "HIGH"

    fun compactLabel(): String = alternate?.let { "${primary.label} / ${it.label}" } ?: primary.label
}

object AvinoxAssistModeDetector {
    fun detect(bytes: ByteArray): AvinoxAssistDetection? {
        if (bytes.size < 69) return null
        val prefix = intArrayOf(0x55, 0x4F, 0x04, 0x39, 0x05, 0x02)
        for (i in prefix.indices) if ((bytes[i].toInt() and 0xff) != prefix[i]) return null
        if ((bytes[9].toInt() and 0xff) != 0x57 || (bytes[10].toInt() and 0xff) != 0x09) return null
        val code = bytes[68].toInt() and 0xff
        return when (code) {
            1 -> AvinoxAssistDetection(AvinoxAssistMode.ECO, rawCode = code, confidence = "HIGH", note = "선택 모드 ECO")
            2 -> AvinoxAssistDetection(AvinoxAssistMode.TRAIL, rawCode = code, confidence = "HIGH", note = "선택 모드 TRAIL")
            3 -> AvinoxAssistDetection(AvinoxAssistMode.TURBO, rawCode = code, confidence = "HIGH", note = "선택 모드 TURBO")
            4 -> AvinoxAssistDetection(AvinoxAssistMode.AUTO, rawCode = code, confidence = "HIGH", note = "선택 모드 AUTO")
            else -> null
        }
    }
}
