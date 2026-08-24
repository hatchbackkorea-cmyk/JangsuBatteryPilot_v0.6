package com.seungjae.jangsu280battery

enum class RequestedVoiceLevel { QUIET, NORMAL, CHATTY }

sealed class VoiceCommand {
    data class Battery(val percent: Int, val forcePostCharge: Boolean = false) : VoiceCommand()
    data class FinishTarget(val percent: Int) : VoiceCommand()
    data class SetVoiceLevel(val level: RequestedVoiceLevel) : VoiceCommand()
    data object Repeat : VoiceCommand()
    data object NextCheckpoint : VoiceCommand()
    data object FinishInfo : VoiceCommand()
    data object CurrentStatus : VoiceCommand()
    data object RemainingOverview : VoiceCommand()
    data object NextClimb : VoiceCommand()
    data object LocationInfo : VoiceCommand()
    data object ChargeStart : VoiceCommand()
    data object ChargeStop : VoiceCommand()
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
 *
 * ChatGPT 같은 범용 LLM을 호출하지 않고, 라이딩 중 자주 쓰는 한국어 표현을 넓게
 * 정규화해서 의도(intent)를 찾는다. 네트워크가 없는 산속에서도 즉시 동작하는 것이 목표다.
 */
object VoiceCommandParser {
    fun parse(text: String): VoiceCommand {
        val raw = text.trim()
        if (raw.isBlank()) return VoiceCommand.Unknown

        val t = normalize(raw)
        val percent = BatteryVoiceParser.parsePercent(raw)

        // 1) 잘못 실행되면 영향이 큰 명령부터 명확한 문맥 우선 처리.
        if (hasAny(t, "입력취소", "방금취소", "마지막취소", "되돌려", "되돌리기") || t == "취소") {
            return VoiceCommand.UndoActual
        }

        // 종점 목표는 실제 배터리 입력보다 먼저 판정한다.
        if (percent != null && hasFinishTargetContext(t)) {
            return VoiceCommand.FinishTarget(percent)
        }

        // 충전 후 잔량 표현. 숫자가 있으면 충전 종료보다 '새 기준 배터리'가 더 중요하다.
        if (percent != null && hasPostChargeContext(t)) {
            return VoiceCommand.Battery(percent, forcePostCharge = true)
        }

        // 실제 배터리 자연어 입력.
        if (percent != null && hasBatteryContext(t)) {
            return VoiceCommand.Battery(percent, forcePostCharge = false)
        }

        // "지금 48이야", "이제 마흔여덟이야" 같은 짧은 문맥형 입력.
        if (percent != null && isImplicitBatteryStatement(t)) {
            return VoiceCommand.Battery(percent, forcePostCharge = false)
        }

        // 주행 제어/현재 위치를 코스 지점으로 등록.
        if (hasAny(t, "주행시작", "라이딩시작", "기록시작", "출발할게", "출발하자")) {
            return VoiceCommand.RideStart
        }
        if (hasAny(t, "주행종료", "라이딩종료", "기록종료", "라이딩끝", "주행끝", "오늘라이딩끝")) {
            return VoiceCommand.RideStop
        }
        if (hasAny(t, "여기를보급소로등록", "여기보급소등록", "여기를충전지점으로등록", "여기충전지점등록", "여기를보급지점으로")) {
            return VoiceCommand.AddSupplyPoint
        }

        // 2) 충전 타이머.
        if (hasAny(t, "충전시작", "충전타이머시작", "충전할게", "충전한다", "충전들어가")) {
            return VoiceCommand.ChargeStart
        }
        if (hasAny(t, "충전종료", "충전끝", "충전끝났", "충전그만", "충전멈춰", "충전다했")) {
            return VoiceCommand.ChargeStop
        }

        // 3) 음성 안내 수준도 말로 변경.
        if (hasAny(t, "조용히해", "말좀줄여", "안내줄여", "덜말해", "중요한것만", "조용모드")) {
            return VoiceCommand.SetVoiceLevel(RequestedVoiceLevel.QUIET)
        }
        if (hasAny(t, "기본안내", "보통으로", "기본모드", "평소대로")) {
            return VoiceCommand.SetVoiceLevel(RequestedVoiceLevel.NORMAL)
        }
        if (hasAny(t, "더자주알려", "계속알려", "자주말해", "수다쟁이", "매킬로", "1킬로마다", "일킬로마다")) {
            return VoiceCommand.SetVoiceLevel(RequestedVoiceLevel.CHATTY)
        }

        // 4) 질문/조회 의도.
        if (hasAny(t, "안내다시", "다시말해", "다시말", "다시안내", "한번더말해", "방금뭐라고")) {
            return VoiceCommand.Repeat
        }
        if (hasAny(t, "다음보급", "다음충전", "보급소까지", "충전소까지", "보급어디", "충전어디")) {
            return VoiceCommand.NextCheckpoint
        }
        if (hasAny(t, "종점까지", "완주까지", "끝까지", "피니시까지", "도착까지") || t == "종점" || t == "완주") {
            return VoiceCommand.FinishInfo
        }
        if (hasAny(t, "앞에업힐", "앞에오르막", "힘든업힐", "힘든오르막", "다음업힐", "다음오르막", "큰업힐", "큰오르막")) {
            return VoiceCommand.NextClimb
        }
        if (hasAny(t, "코스정보", "코스어때", "전체거리", "전체획고", "총상승", "이코스정보")) {
            return VoiceCommand.CourseInfo
        }
        if (hasAny(t, "어디쯤", "지금어디", "몇킬로지점", "몇키로지점", "현재위치")) {
            return VoiceCommand.LocationInfo
        }
        if (hasAny(t, "얼마남았", "뭐가남았", "남은거리", "앞으로얼마", "얼마나남았")) {
            return VoiceCommand.RemainingOverview
        }
        if (hasAny(
                t,
                "현재예상", "현재상태", "배터리예상", "배터리괜찮", "베터리괜찮", "이대로가도돼",
                "이대로가도되", "충분해", "완주가능", "가능할까", "여유있", "위험해", "상태어때"
            )
        ) {
            return VoiceCommand.CurrentStatus
        }
        if (hasAny(t, "명령어", "뭐라고말", "어떻게말", "도움말", "사용법")) {
            return VoiceCommand.Help
        }

        // 5) 숫자만 말했거나 아주 짧은 응답은 라이딩 화면의 큰 마이크에서 입력했다고 보고
        // 배터리 값으로 허용한다. 단, 거리/시간 질문 문맥은 제외한다.
        if (percent != null && !looksLikeDistanceOrTimeQuestion(t)) {
            val compact = t.replace(Regex("[^0-9가-힣]"), "")
            if (compact.length <= 12) return VoiceCommand.Battery(percent, forcePostCharge = false)
        }

        return VoiceCommand.Unknown
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

    private fun hasBatteryContext(t: String): Boolean {
        return hasAny(t, "배터리", "잔량", "남았", "남아", "현재프로", "지금프로", "실제") &&
            !looksLikeDistanceOrTimeQuestion(t)
    }

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
