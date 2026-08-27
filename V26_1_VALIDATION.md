# v0.26.1 validation

## UI 변경 검증
- 누적 에너지 비교 `우리 예측` / `Avinox` 보조 예상값을 둘째 줄 `(...)`로 강제 분리
- 비교 TextView를 `maxLines=2`, `lines=2`, `match_parent`로 변경해 3분할 셀 내 잘림 방지
- 메인 상단 버전 표기 9sp → 12sp bold

## Proto A+ 학습 메뉴
- `과거 라이딩 학습` 최상단에 `Avinox 원본 A+ 학습` 패널 추가
- Shizuku 권한 허용
- 새 Proto 동기화(최근 미처리 최대 8개)
- 과거 Proto 전체 동기화(미처리 전체)
- 원본 수 / A+ 채택 수 / 학습구간 없음 / 총 학습거리 / 총 A+ 학습구간 표시
- 처리 이력 보존 500 → 5,000개

## 실제 Avinox 원본 회귀 검증
`cloud_ride_rec_1292.proto`:
- format v8 / ride 1292
- parsed samples 4,413
- distance 32.045 km
- SOC 100 → 41%
- consumed 59%
- charged 0%
- battery checkpoints 60
- assist windows 32
- telemetry 4,413
- quality 100/100

## 정적 검사
- Android resource XML 28개 parse 오류 0
- resource id 197개 수집
- Kotlin `R.id.*` 참조 202개 / 누락 0
- 수정 Kotlin 파일 괄호/중괄호 balance 정상
- Kotlin parser-level syntax error 없음(로컬 Android SDK classpath 미탑재 상태의 unresolved reference는 제외)

## Android Gradle assemble
이 실행환경은 `services.gradle.org` DNS가 차단되어 Gradle 8.9 배포본을 받을 수 없으므로 Android APK assemble은 실행하지 못함.
GitHub Actions/로컬 Android Studio 빌드에서 최종 Android 의존성 컴파일을 확인할 것.
