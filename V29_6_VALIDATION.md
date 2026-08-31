# v0.29.6 validation

## 구현 확인
- 기존 실제 v0.29.4 전체 소스에서 분기해 v0.29.6 배터리 정밀 분석/분할 세션 기능을 추가했습니다.
- 메인 ViewFlipper에 기존 6개 페이지 뒤 `배터리` 페이지를 추가해 총 7페이지를 유지합니다.
- `BatteryForensicsActivity`와 `BatteryForensicsStore`를 추가했습니다.
- 기존 Avinox `cloud_ride_rec_*.proto`를 다시 읽어 SOC/온도/충전 증가/관측 소비량을 집계합니다.
- 배터리 정밀 캡처 세션은 `시작 / 이어하기` 후 앱을 닫았다 다시 열어도 같은 열린 세션 ID를 이어 씁니다. 사용자가 `세션 종료`를 누르기 전까지 상태별 캡처를 나눠서 추가할 수 있습니다.
- FFF4 최신 RAW notification을 영속 저장해 상태 캡처 ZIP에 포함합니다.
- 미확인 BMS 실제 사이클/SOH/팩 전압·전류/셀 전압·밸런싱/충전제한·보호/FW·시리얼/좌우 스위치 배터리는 추정값을 만들지 않고 null/미해독으로 유지합니다.

## 정적 검사
- Android/XML 파일: 37개
- XML parse 오류: 0
- resource ID: 281개
- Kotlin/Java `R.id.*` 참조 누락: 0
- layout 내 중복 ID: 0
- 메인 ViewFlipper 직접 child: 7개
- Manifest에 `BatteryForensicsActivity` 등록 확인
- 수정 Kotlin 파일 parser-level syntax error 없음. Android SDK/project classpath가 없는 단독 `kotlinc` 환경에서 발생하는 unresolved reference는 이 검사에서 컴파일 성공으로 간주하지 않습니다.

## 기존 배포 workflow 보존
- v0.29.4 기준 `.github/workflows`와 v0.29.6 `.github/workflows` 디렉터리 `diff -qr` 차이 없음.

## Android Gradle assemble
`./gradlew :app:assembleDebug`를 실제 실행했으나, Gradle 8.9 배포본을 받는 단계에서 실행환경 DNS가 `services.gradle.org`를 해석하지 못해 중단되었습니다.

오류:
```
curl: (6) Could not resolve host: services.gradle.org
```
따라서 이 환경에서는 APK assemble 성공을 확인하지 못했습니다. GitHub Actions 또는 Android Studio에서 최종 Android 의존성 컴파일/assemble을 확인해야 합니다.
