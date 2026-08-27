# v0.26.0 validation

## 실제 Avinox 원본 검증
개발 중 제공된 `cloud_ride_rec_1292.proto` 원본으로 내장 파서를 호스트 JVM에서 직접 실행해 검산했다.

- format version: 8
- ride id: 1292
- declared/parsed samples: 4,413 / 4,413
- distance: 32.045 km
- ascent/descent: +409 m / -405 m
- start/end SOC: 100% / 41%
- consumed SOC: 59%
- detected charging: 0%
- battery checkpoints: 60
- assist windows: 32
- telemetry points: 4,413
- parser quality: 100/100

## 안전장치
- `cloud_ride_rec_*_<positive id>.proto`만 읽기
- 검증된 Avinox v8만 A+ 학습
- 파일 크기, 레코드 길이, sample count, 시간, 거리, SOC/GPS coverage 검사
- 100→98% BMS plateau 제외
- 미확인 assist code(예: 5) 구간은 모드 학습에서 제외
- 30초 이상 timestamp gap은 모드 window 강제 분리
- SOC 상승(중간 충전)은 소비 학습에서 제외, 총 소비량은 SOC 하락분 합계로 계산
- 원본 파일은 읽기 전용이며 Avinox 폴더에 WRITE/DELETE 하지 않음

## 정적 검사
- Android resource XML 28개 parse: 오류 0
- Kotlin의 `R.id.*` 참조 191개: 누락 ID 0
- `AvinoxProtoParser.kt`: Kotlin/JVM stub compile 통과
- `AvinoxProtoSyncManager.kt`: Kotlin/JVM stub compile 통과
- `AvinoxFileUserService.kt`: Kotlin/JVM stub compile 통과

## 미실행 검사
이 작업환경은 `services.gradle.org` DNS 접근이 차단되어 Gradle 8.9 배포본을 다운로드할 수 없으므로 Android APK assemble은 실행하지 못했다. GitHub Actions 빌드에서 Android/Shizuku 실제 의존성을 포함한 최종 컴파일을 확인한다.
