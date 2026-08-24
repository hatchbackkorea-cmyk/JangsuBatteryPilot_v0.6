package com.seungjae.jangsu280battery

object BatteryVoiceParser {
    private val digitRegex = Regex("""(?<!\d)(100|[0-9]{1,2})(?!\d)""")

    fun parsePercent(text: String): Int? {
        val normalized = text.lowercase()
            .replace("퍼센트", "")
            .replace("프로", "")
            .replace("percent", "")
            .replace("배터리", "")
            .replace("베터리", "")
            .replace("잔량", "")
            .replace("현재", "")
            .replace("실제", "")
            .replace("정도", "")
            .replace("%", "")
            .replace(" ", "")
            .trim()

        digitRegex.find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
            if (it in 0..100) return it
        }

        parseNativeKoreanNumber(normalized)?.let { if (it in 0..100) return it }
        return parseSinoKoreanNumber(normalized)?.takeIf { it in 0..100 }
    }

    /** 마흔여덟, 일흔, 스물셋 같은 고유어 숫자 지원. */
    private fun parseNativeKoreanNumber(raw: String): Int? {
        if (raw.isBlank()) return null
        val text = raw.replace(Regex("[^가-힣]"), "")
        val tens = linkedMapOf(
            "아흔" to 90, "여든" to 80, "일흔" to 70, "예순" to 60, "쉰" to 50,
            "마흔" to 40, "서른" to 30, "스물" to 20, "스무" to 20, "열" to 10
        )
        val ones = linkedMapOf(
            "아홉" to 9, "여덟" to 8, "일곱" to 7, "여섯" to 6, "다섯" to 5,
            "넷" to 4, "네" to 4, "셋" to 3, "세" to 3, "둘" to 2, "두" to 2, "하나" to 1, "한" to 1
        )
        for ((word, ten) in tens) {
            val idx = text.indexOf(word)
            if (idx >= 0) {
                val rest = text.substring(idx + word.length)
                val one = ones.entries.firstOrNull { rest.startsWith(it.key) }?.value ?: 0
                return ten + one
            }
        }
        return ones.entries.firstOrNull { text.contains(it.key) }?.value
    }

    /** 사십팔, 칠십, 백 같은 한자어 숫자 지원. */
    private fun parseSinoKoreanNumber(raw: String): Int? {
        if (raw.isBlank()) return null
        val text = raw.replace("퍼", "").replace("%", "")
        val ones = mapOf(
            '영' to 0, '공' to 0, '일' to 1, '이' to 2, '삼' to 3, '사' to 4,
            '오' to 5, '육' to 6, '칠' to 7, '팔' to 8, '구' to 9
        )
        if (text == "백") return 100
        if (text.length == 1 && text[0] in ones) return ones[text[0]]

        var total = 0
        var pending: Int? = null
        var saw = false
        for (ch in text) {
            when {
                ch in ones -> {
                    pending = ones[ch]
                    saw = true
                }
                ch == '십' -> {
                    total += (pending ?: 1) * 10
                    pending = null
                    saw = true
                }
                ch == '백' -> {
                    total += (pending ?: 1) * 100
                    pending = null
                    saw = true
                }
                ch == '점' -> return null
                else -> Unit
            }
        }
        total += pending ?: 0
        return total.takeIf { saw }
    }
}
