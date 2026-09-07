# Ride Copilot Android · 프로젝트 기억 백업

작성 기준: **2026-09-07**

이 문서는 ChatGPT/AI가 과거 대화를 모두 잊었을 때 Android 앱의 목적과 중요한 결정을 복구하기 위한 문서다. 서버 쪽 전체 기억은 `hatchbackkorea-cmyk/RiderControlCenter`의 기억 백업 문서를 함께 읽어야 한다.

---

## 1. 앱 정체성

- Repository: `hatchbackkorea-cmyk/JangsuBatteryPilot_v0.6`
- Branch: `main`
- Package: `com.seungjae.jangsu280battery`
- 2026-09-07 기준 `VERSION.txt`: **0.34.29**
- Android target SDK는 코드상 API 35 계열

앱은 단순 RACE 앱이 아니다.

원래 출발점은 **DJI Avinox / Amflow PL 800Wh 전기 MTB의 실제 주행 데이터를 이용한 배터리 예측/주행 전략 앱**이다. 이후 Rider Control Center 서버와 연결되는 RACE timing/참가/관리 기능이 크게 추가됐다.

---

## 2. 가장 중요한 두 축

### A. Avinox / Battery / Ride Copilot

목표:

- 실제 라이딩 FIT 데이터
- 실제 battery SOC/로그
- 실제 사용자 파워/주행 특성
- GPX 코스

를 조합해 개인화된 배터리 소모/주행 전략을 만든다.

사용자의 실제 자전거는 **Amflow PL 800Wh**.

데이터 원칙:

- 실주행 데이터가 최우선
- 실제 FIT + 실제 battery log는 학습/검증 핵심
- Avinox가 보여주는 예상치는 benchmark/reference
- 검증되지 않은 예상값을 실제 관측과 섞어 개인화 모델을 오염시키지 않는다.

대표 코드 범주:

- `AdaptiveBatteryPlan.kt`
- `AvinoxBleSocClient.kt`
- `AvinoxBleStateStore.kt`
- `AvinoxAssistModeDetector.kt`
- `AvinoxAssistProfileStore.kt`
- `AutoFitImportManager.kt`
- 기타 battery/ride insight/model 관련 class

### B. RACE

목표:

- 참가자폰 자체 GPS/GNSS로 START/SECTOR/FINISH 판정
- 서버에 결과/실시간 상태 전송
- 서버 관리자/대형중계 화면과 결합
- 네트워크가 끊겨도 공식 기록을 잃지 않음

대표 코드:

- `RaceTimingService.kt`
- `RaceServerClient.kt`
- `RaceDataStore.kt`
- `RaceActivity.kt`
- `RaceBroadcastActivity.kt`
- `RaceSavedCoursesActivity.kt`
- `RaceSavedCourseBackfill.kt`
- `RaceCoursePublisher.kt`
- `RaceTrackDraftAutoSync.kt`
- `RaceTrackBuilderActivity*`
- `docs/RACE_PROTOCOL_V1.md`

---

## 3. RACE authority 원칙

**공식 기록의 원본은 참가자 Android 폰이다.**

### 이유

- 네트워크 latency를 공식 기록에 넣지 않기 위해
- 현장 인터넷/LAN 장애에도 기록을 남기기 위해
- 실제 GNSS 측정 시점과 서버 수신시점을 분리하기 위해

### 구현상 지켜야 할 것

- `RaceTimingService`가 실제 GNSS를 사용
- FINISH 결과를 서버 업로드 전에 로컬 저장
- SECTOR/FINISH 전송은 durable queue 유지
- 서버 장애/오프라인 후 재전송 가능
- 서버가 elapsed time을 다시 만들어 덮어쓰지 않음

이 원칙을 깨는 변경은 매우 큰 설계 변경이므로 사용자 승인 없이 하지 않는다.

---

## 4. 참가/프로필/서버 연결

RACE public profile은 사용자가 다루는 값과 내부 identity를 분리한다.

- 사용자가 입력/보는 값: 이름, 닉네임 등
- 앱 내부: hidden `profile_id`
- event join 시 서버가 participant identity/token 생성
- 사용자가 내부 ID/token을 직접 입력하게 만들지 않는다.

RACE event QR 또는 join flow가 event server base URL을 지정할 수 있다.

관련:

- `RaceServerClient.kt`
- `docs/RACE_PROTOCOL_V1.md`

---

## 5. 서버 저장소

Android와 반드시 같이 봐야 하는 서버:

- `hatchbackkorea-cmyk/RiderControlCenter`
- branch `master`

2026-09-07 기준 운영 구조:

- **데스크탑 PC = 메인 서버**
- 노트북 = 브라우저 운영 클라이언트
- 앱 테스트 Emulator/scrcpy도 데스크탑에서 수행

서버는 RACE 이벤트, 선수명단, 규칙, 세션, 배번, 결과저장, 중계, AI commentary, APK 제공, 진단을 담당한다.

---

## 6. APK 업데이트/배포 원칙

현재 사용자는 혼자 개발/테스트한다.

확정된 선호:

- 아직 dev build / release build를 별도 설치하지 않는다.
- **단일 latest APK 흐름** 유지
- 서버의 Android test lab에서 최신 APK 자동 다운로드 → 설치 → 실행
- 실제 휴대폰에 매번 수동 APK upload하는 흐름을 기본으로 만들지 않는다.

버전은 `VERSION.txt`를 중심으로 Gradle이 읽는 구조다.

관련:

- `VERSION.txt`
- `app/build.gradle.kts`
- `UpdateManager.kt`

---

## 7. 앱 테스트 구조

서버 PC에서 실제 Android Emulator를 사용한다.

현재 AVD 방향:

- AVD: `RideCopilot_API_35`
- Pixel 6 profile
- API 35

테스트 방법 두 가지:

### Browser Android lab

- 실제 ADB screenshot
- 실제 tap/swipe/key 전달
- logcat
- debug recorder와 결합
- screenshot polling 때문에 느릴 수 있음

### scrcpy realtime window

- 빠른 실제 조작
- 창 크기 자유 조절
- 수동 테스트에 적합
- browser debug event와 1:1로 모든 입력이 기록되지는 않을 수 있음

노트북에서 Emulator를 돌렸을 때 PC가 매우 느려져서 **데스크탑 테스트로 이동**했다.

---

## 8. 과거 중요 UI 버그

대표 사례:

- RACE START 화면에서 왼쪽 swipe
- monitoring 화면으로 넘어가야 함
- 상단 Android/logo처럼 보이는 부분만 있고 실제 monitoring content가 보이지 않는 증상

이 문제 때문에 서버 측 Android test lab/debug agent를 강화했다.

이 버그가 다시 나오면:

1. 최신 APK인지 확인
2. Emulator에서 실제 swipe 재현
3. screenshot 전/후
4. focused activity/window
5. UI hierarchy
6. logcat
7. RaceActivity/render state

순서로 본다.

---

## 9. 진단 철학

사용자는 다음 workflow를 싫어한다.

- AI: “명령어 입력하세요”
- 사용자: 결과 캡처
- AI: 또 다른 명령어
- 반복

따라서 앱은 서버 진단 시스템과 연결해 가능한 많은 정보를 자동 수집하는 방향이다.

관련 Android:

- `SystemDiagnosticsClient.kt`

관련 서버:

- `android_debug_agent.py`
- `system_diagnostics.py`
- `diagnostic_bridge.py`
- `system_diagnostics_publish_check.py`
- `system_diagnostics_retry.py`

목표는 자동 증거수집이지, 검토 없이 AI가 임의 코드를 실행해 패치하는 것이 아니다.

---

## 10. RACE 코스 편집

RACE 코스에는 START / FINISH / SECTOR gate 개념이 있다.

중요한 사용자 선호:

- 관리자가 지정한 gate 좌표/방향/폭을 존중
- 시스템이 몰래 snap/correct해서 위치를 바꾸지 않음

저장 코스의 POI에서 RACE gate로 backfill하는 코드가 있으므로 코스 format 변경 시 기존 저장코스 호환을 확인한다.

관련:

- `RaceSavedCourseBackfill.kt`
- `RaceSavedCoursesActivity.kt`
- server `race_trap_free_editor.py`

---

## 11. Offline/Network 원칙

현장 핵심 RACE는 LAN에서 돌아갈 수 있어야 한다.

- 인터넷 없음 → 외부 tunnel/OpenAI 등은 안 될 수 있음
- 같은 LAN → 서버와 참가자폰 핵심 통신은 유지 목표
- 폰이 순간적으로 서버 연결을 잃어도 로컬 결과 보존

앱이 네트워크 실패 시 기록 자체를 실패처리하는 구조로 바꾸지 않는다.

---

## 12. Admin phone / 서버 동기화

Android 앱에는 Rider Control Center와 일반 mobile sync/admin 기능도 있다.

대표:

- `RiderServerSync.kt`
- `AdminCenterActivity.kt`
- `SettingsActivity.kt`

서버에는 관리자폰 등록, mobile device token, sync state 등이 저장된다.

관리자폰 인증 흐름을 변경할 때 서버 DB/token registry와 Android 양쪽을 같이 본다.

---

## 13. Current user workflow

현재 개발 흐름은 대략:

1. AI가 GitHub 코드 수정
2. CI/build 검증
3. 데스크탑 Rider Control Center `업데이트 + 재시작`
4. 서버 앱 테스트 화면에서 최신 APK 설치/실행
5. scrcpy 또는 browser lab으로 확인
6. 문제 시 debug/diagnostics

Android 코드를 수정했다고 사용자 PC/폰에 즉시 적용됐다고 말하면 안 된다. 실제 build/release/latest APK가 생성되고 설치되어야 한다.

---

## 14. 새 AI가 먼저 검색해야 할 키워드

RACE:

- `RaceTimingService`
- `RaceServerClient`
- `RaceDataStore`
- `RaceActivity`
- `RACE_PROTOCOL_V1`
- `START`, `FINISH`, `SECTOR`, `gate`

Battery:

- `AvinoxBleSocClient`
- `AdaptiveBatteryPlan`
- `battery`
- `soc`
- `assist`
- `FIT`

Server sync:

- `RiderServerSync`
- `AdminCenterActivity`
- `SystemDiagnosticsClient`

Update:

- `UpdateManager`
- `VERSION.txt`

---

## 15. 절대 문서에 저장하지 않는 것

- 실제 OpenAI API key
- 관리자 암호
- device token
- private tunnel credential
- 개인 access token

코드에는 secret의 **위치/이름/필요성**만 기록한다.

---

## 16. 기억 상실 시 복구 순서

1. 이 저장소 `RECOVERY_PROMPT.md`
2. 이 문서
3. `docs/RACE_PROTOCOL_V1.md`
4. `CHANGELOG.md`
5. 최신 실제 Android 코드
6. 서버 저장소 `RECOVERY_PROMPT.md`
7. 서버 `docs/PROJECT_MEMORY_BACKUP.md`
8. 서버 `docs/ARCHITECTURE_AND_API.md`
9. 서버 `docs/DECISIONS_AND_KNOWN_ISSUES.md`

이 순서를 지키면 대화 기록 없이도 프로젝트의 핵심 의도와 기술구조를 상당 부분 복구할 수 있다.
