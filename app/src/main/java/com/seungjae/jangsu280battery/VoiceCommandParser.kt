package com.seungjae.jangsu280battery

sealed class VoiceCommand {
    data class Battery(val percent: Int) : VoiceCommand()
    data class FinishTarget(val percent: Int) : VoiceCommand()
    data class SetVoiceEnabled(val enabled: Boolean) : VoiceCommand()
    data class SetDistanceInterval(val km: Int) : VoiceCommand()
    data class SetTimeInterval(val minutes: Int) : VoiceCommand()
    data object Repeat : VoiceCommand()
    data object NextCheckpoint : VoiceCommand()
    data object FinishInfo : VoiceCommand()
    data object CurrentStatus : VoiceCommand()
    data object RemainingOverview : VoiceCommand()
    data object NextClimb : VoiceCommand()
    data object LocationInfo : VoiceCommand()
    data object UndoActual : VoiceCommand()
    data object RideStart : VoiceCommand()
    data object RideStop : VoiceCommand()
    data object AddSupplyPoint : VoiceCommand()
    data object CourseInfo : VoiceCommand()
    data object Help : VoiceCommand()
    data object Unknown : VoiceCommand()
}

/**
 * 오프라인 자연어 명령 파서.
 * 범용 LLM 없이 라이딩/배터리 관련 한국어 표현을 로컬에서 처리한다.
 */
object VoiceCommandParser {
    fun parse(text: String): VoiceCommand {
        val raw = text.trim()
        if (raw.isBlank()) return VoiceCommand.Unknown

        val t = normalize(raw)
        val number = extractNumber(raw)
        val percent = BatteryVoiceParser.parsePercent(raw)

        if (hasAny(t, "입력취소", "방금취소", "마지막취소", "되돌려", "되돌리기") || t == "취소") {
            return VoiceCommand.UndoActual
        }

        // 종점 목표는 실제 배터리 입력보다 먼저 판정. v0.8은 1~99%만 허용한다.
        if (percent != null && hasFinishTargetContext(t)) {
            return VoiceCommand.FinishTarget(percent.coerceIn(1, 99))
        }

        // 안내 간격 자연어 설정: "5킬로마다 알려줘", "10분마다 안내해".
        if (hasDistanceIntervalContext(t)) {
            if (hasAny(t, "꺼", "끄기", "사용안함", "안해", "하지마")) return VoiceCommand.SetDistanceInterval(0)
            if (number != null) return VoiceCommand.SetDistanceInterval(number.coerceIn(0, 50))
        }
        if (hasTimeIntervalContext(t)) {
            if (hasAny(t, "꺼", "끄기", "사용안함", "안해", "하지마")) return VoiceCommand.SetTimeInterval(0)
            if (number != null) return VoiceCommand.SetTimeInterval(number.coerceIn(0, 120))
        }

        if (hasAny(t, "음성안내꺼", "음성꺼", "말하지마", "조용히해", "안내꺼")) {
            return VoiceCommand.SetVoiceEnabled(false)
        }
        if (hasAny(t, "음성안내켜", "음성켜", "다시말해줘", "안내켜")) {
            return VoiceCommand.SetVoiceEnabled(true)
        }

        // 충전 시작/완료 잔량은 확인/취소가 있는 전용 버튼에서만 저장한다.
        // 충전 문맥을 일반 주행 배터리로 잘못 넣으면 학습 데이터가 오염될 수 있으므로 음성 저장하지 않는다.
        if (percent != null && hasPostChargeContext(t)) return VoiceCommand.Unknown
        if (percent != null && hasBatteryContext(t)) return VoiceCommand.Battery(percent)
        if (percent != null && isImplicitBatteryStatement(t)) return VoiceCommand.Battery(percent)

        if (hasAny(t, "주행시작", "라이딩시작", "기록시작", "출발할게", "출발하자")) return VoiceCommand.RideStart
        if (hasAny(t, "주행종료", "라이딩종료", "기록종료", "라이딩끝", "주행끝", "오늘라이딩끝")) return VoiceCommand.RideStop
        if (hasAny(t, "여기를보급소로등록", "여기보급소등록", "여기를충전지점으로등록", "여기충전지점등록", "여기를보급지점으로")) return VoiceCommand.AddSupplyPoint

        if (hasAny(t, "안내다시", "다시말해", "다시말", "다시안내", "한번더말해", "방금뭐라고")) return VoiceCommand.Repeat
        if (hasAny(t, "다음보급", "다음충전", "보급소까지", "충전소까지", "보급어디", "충전어디")) return VoiceCommand.NextCheckpoint
        if (hasAny(t, "종점까지", "완주까지", "끝까지", "피니시까지", "도착까지") || t == "종점" || t == "완주") return VoiceCommand.FinishInfo
        if (hasAny(t, "앞에업힐", "앞에오르막", "힘든업힐", "힘든오르막", "다음업힐", "다음오르막", "큰업힐", "큰오르막")) return VoiceCommand.NextClimb
        if (hasAny(t, "코스정보", "코스어때", "전체거리", "전체획고", "총상승", "이코스정보")) return VoiceCommand.CourseInfo
        if (hasAny(t, "어디쯤", "지금어디", "몇킬로지점", "몇키로지점", "현재위치")) return VoiceCommand.LocationInfo
        if (hasAny(t, "얼마남았", "뭐가남았", "남은거리", "앞으로얼마", "얼마나남았")) return VoiceCommand.RemainingOverview
        if (hasAny(t, "현재예상", "현재상태", "배터리예상", "배터리괜찮", "베터리괜찮", "이대로가도돼", "이대로가도되", "충분해", "완주가능", "가능할까", "여유있", "위험해", "상태어때")) return VoiceCommand.CurrentStatus
        if (hasAny(t, "명령어", "뭐라고말", "어떻게말", "도움말", "사용법")) return VoiceCommand.Help

        // 큰 마이크에서 짧게 숫자만 말하면 배터리 값으로 해석.
        if (percent != null && !looksLikeDistanceOrTimeQuestion(t)) {
            val compact = t.replace(Regex("[^0-9가-힣]"), "")
            if (compact.length <= 12) return VoiceCommand.Battery(percent)
        }

        return VoiceCommand.Unknown
    }

    private fun extractNumber(text: String): Int? {
        Regex("(?<!\\d)(120|1[01][0-9]|100|[0-9]{1,2})(?!\\d)").find(text)?.value?.toIntOrNull()?.let { return it }
        return BatteryVoiceParser.parsePercent(text)
    }

    private fun normalize(text: String): String = text.lowercase()
        .replace(Regex("[\\s,.!?~·'\"”“‘’]"), "")
        .replace("베터리", "배터리")
        .replace("퍼센트", "프로")
        .replace("percent", "프로")
        .replace("킬로미터", "킬로")
        .replace("키로미터", "킬로")
        .replace("키로", "킬로")

    private fun hasAny(text: String, vararg needles: String): Boolean = needles.any { text.contains(it) }

    private fun hasDistanceIntervalContext(t: String): Boolean =
        hasAny(t, "킬로마다", "km마다", "거리안내", "거리기준", "킬로간격", "km간격") ||
            (hasAny(t, "킬로", "km") && hasAny(t, "안내", "알려", "말해", "마다", "간격"))

    private fun hasTimeIntervalContext(t: String): Boolean =
        hasAny(t, "분마다", "시간안내", "시간기준", "분간격") ||
            (t.contains("분") && hasAny(t, "안내", "알려", "말해", "마다", "간격"))

    private fun hasFinishTargetContext(t: String): Boolean {
        val targetWord = hasAny(t, "목표", "남기고싶", "남겨", "잔량설정", "마지막에", "도착할때", "종점에서", "완주할때")
        val finishWord = hasAny(t, "종점", "완주", "끝", "도착", "목표잔량", "목표배터리")
        return (targetWord && finishWord) || hasAny(t, "종점목표", "목표잔량", "목표배터리")
    }

    private fun hasPostChargeContext(t: String): Boolean = hasAny(
        t,
        "충전완료", "충전끝", "충전다했", "충전했", "충전해서", "충전하고", "까지채웠", "까지충전",
        "채웠어", "채웠다", "채워졌", "충전후", "충전끝났"
    )

    private fun hasBatteryContext(t: String): Boolean =
        hasAny(t, "배터리", "잔량", "남았", "남아", "현재프로", "지금프로", "실제") && !looksLikeDistanceOrTimeQuestion(t)

    private fun isImplicitBatteryStatement(t: String): Boolean {
        if (looksLikeDistanceOrTimeQuestion(t)) return false
        val statementCue = hasAny(t, "지금", "현재", "이제", "지금은", "현재는")
        val endingCue = hasAny(t, "이야", "야", "됐어", "됐네", "남았어", "남았네", "남아", "정도야")
        return statementCue && endingCue
    }

    private fun looksLikeDistanceOrTimeQuestion(t: String): Boolean = hasAny(
        t, "킬로", "km", "몇분", "몇시간", "시간", "거리", "언제", "도착시간", "eta"
    )
}
