# v0.27.3 validation

## 변경 검증
- 설정 페이지의 `switchPageTestMode`, `tvPageTestKm`, `seekPageTestKm` 제거
- 주행 첫 페이지에 `switchRideTestMode`, `rideMiniProfileView`, `seekRideRoute`, `tvRideRouteScale` 추가
- 기존 배터리 SOC `progressBattery` 제거
- 거리 SeekBar는 전체 GPX km를 기준으로 진행하며 등록 계획 충전소를 노란 눈금으로 표시
- 테스트 모드일 때만 사용자가 SeekBar를 드래그할 수 있고 실제 주행에서는 read-only GPS 진행 표시
- 테스트 주행 중에는 위치 SeekBar는 계속 조작 가능, 테스트 스위치는 주행 종료 전까지 잠금
- mini elevation profile은 compact 54dp, 현재 위치 + 다음 10km 음영 + 계획 충전소 표시
- 임의주행은 GPX 기준 UI를 숨김

## 정적 검사
- Android resource XML 31개 parse 오류 0
- activity_main.xml id 중복 0
- Kotlin/Java `R.id.*` 참조 누락 0
- 제거한 legacy id의 MainActivity 참조 0
- Kotlin parser-level `expecting`/`unexpected tokens` 오류 0 (Android SDK classpath가 없어 unresolved reference는 정적 parser 검사에서 제외)

## Gradle assemble
- 현재 실행환경에는 Gradle 실행파일/Gradle wrapper JAR/Android SDK classpath가 없어 APK assemble은 실행하지 못함.
- GitHub Actions signed release에서 최종 Android 의존성 컴파일을 확인할 것.
