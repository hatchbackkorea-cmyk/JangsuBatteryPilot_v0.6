# v0.29.3 Validation

## 변경 범위
- Rain Touch Lock 토글 키: `VOL- 길게` → `VOL+ 1.5초 길게`
- 잠금 중 `VOL+ 짧게 = 다음 페이지`, `VOL- 짧게 = 이전 페이지` 유지
- 잠금 해제 중 `VOL+ 짧게 = 미디어 음량 증가`, `VOL- = Android 기본 음량 감소`
- v0.29.2의 HUD/고도그래프/카카오맵/GPS/BLE/SOC 로직은 변경하지 않음

## 정적 검증
- Android main XML: 36개 파싱, 오류 0
- `R.id` 참조: 256개, 누락 0
- 구형 `volumeDownPressedAtMs` / `volumeDownLongHandled`: 잔존 0
- VOL+ long-press toggle 상태/콜백: 확인
- 잠금 중 VOL+ next / VOL- previous: 확인
- 잠금 해제 VOL+ media-volume raise: 확인
- VERSION.txt: 0.29.3

## Gradle
- `./gradlew :app:assembleDebug --offline` 시 Gradle wrapper가 Gradle 8.9 다운로드를 시도함.
- 실행 환경 DNS에서 `services.gradle.org`를 해석하지 못해 전체 Android 빌드는 수행하지 못함.
