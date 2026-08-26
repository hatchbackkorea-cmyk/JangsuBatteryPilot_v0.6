package com.seungjae.jangsu280battery

/**
 * Experimental Avinox assist-mode detector for the verified FFF4 long packet family.
 *
 * Field findings on 2026-08-26:
 * - byte[68] == 1 tracked ECO repeatedly
 * - byte[68] == 4 tracked AUTO in one clean window
 * - byte[68] == 2 tracked TRAIL, but AUTO can also pass through 2
 * - byte[68] == 3 tracked TURBO, but AUTO can also pass through 3
 *
 * Therefore 1 and 4 are strong candidates, while 2/3 are intentionally marked
 * ambiguous until another independent selected-mode field is found.
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
            1 -> AvinoxAssistDetection(AvinoxAssistMode.ECO, rawCode = code, confidence = "HIGH", note = "ECO 강한 후보")
            4 -> AvinoxAssistDetection(AvinoxAssistMode.AUTO, rawCode = code, confidence = "HIGH", note = "AUTO 강한 후보")
            2 -> AvinoxAssistDetection(AvinoxAssistMode.TRAIL, AvinoxAssistMode.AUTO, code, "AMBIGUOUS", "TRAIL 또는 AUTO 동적상태")
            3 -> AvinoxAssistDetection(AvinoxAssistMode.TURBO, AvinoxAssistMode.AUTO, code, "AMBIGUOUS", "TURBO 또는 AUTO 동적상태")
            else -> null
        }
    }
}
