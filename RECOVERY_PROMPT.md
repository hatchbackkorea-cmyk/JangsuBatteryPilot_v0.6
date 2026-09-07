# Ride Copilot Android · AI 기억 복구 시작점

> 목적: 이전 ChatGPT 대화가 전부 사라져도 이 Android 앱을 Rider Control Center 서버와 함께 이어서 개발할 수 있게 한다.

## 새 AI에게 그대로 줄 문장

**“이 저장소의 `RECOVERY_PROMPT.md`와 `docs/PROJECT_MEMORY_BACKUP.md`를 먼저 읽고, 서버 저장소 `hatchbackkorea-cmyk/RiderControlCenter`의 `RECOVERY_PROMPT.md`와 기억 백업 문서도 전부 읽어. 기존 기능을 추측해서 다시 만들지 말고 현재 코드와 결정사항을 기준으로 이어서 개발해.”**

## 핵심 정보

- Repository: `hatchbackkorea-cmyk/JangsuBatteryPilot_v0.6`
- Branch: `main`
- Package: `com.seungjae.jangsu280battery`
- 2026-09-07 기준 VERSION.txt: `0.34.29`
- Server repository: `hatchbackkorea-cmyk/RiderControlCenter` (`master`)

## 반드시 읽을 파일

1. `docs/PROJECT_MEMORY_BACKUP.md`
2. `docs/RACE_PROTOCOL_V1.md`
3. `CHANGELOG.md`
4. `VERSION.txt`
5. `RaceTimingService.kt`
6. `RaceServerClient.kt`
7. `RaceDataStore.kt`
8. `RaceActivity.kt`
9. `RiderServerSync.kt`
10. `SystemDiagnosticsClient.kt`
11. `UpdateManager.kt`
12. 서버 저장소의 `RECOVERY_PROMPT.md`

## 절대 깨면 안 되는 원칙

- **공식 RACE timing의 authority는 참가자 Android 폰**이다.
- FINISH는 서버 전송보다 먼저 로컬에 저장되어야 한다.
- 네트워크 단절로 공식 기록이 사라지면 안 된다. durable queue/offline recovery를 유지한다.
- 서버가 공식 elapsed time을 임의 재계산해서 덮어쓰는 구조로 바꾸지 않는다.
- RACE 기능 때문에 원래의 Avinox/Amflow 배터리 개인화 기능을 삭제하지 않는다.
- 실주행 FIT + 실제 배터리 로그를 가장 신뢰하고 Avinox 예상값은 benchmark로 분리한다.
- 현재는 **하나의 최신 APK 채널**을 사용한다. 사용자가 요청하기 전 dev/release 채널을 새로 나누지 않는다.
- 비밀번호/API Key/token 같은 실제 secret은 GitHub에 기록하지 않는다.

## 작업 방식

새 기능을 만들기 전에 관련 class/file이 이미 있는지 검색한다. 특히 RACE, update, diagnostics, server sync는 이미 여러 파일로 구현되어 있으므로 중복 구현을 피한다.

문서와 코드가 다르면 코드를 우선하되, 왜 그런 구조인지 서버 저장소의 `DECISIONS_AND_KNOWN_ISSUES.md`를 확인한다.
