# v0.29.1 검증

- 기준 소스: v0.29.0 rain GPS guard 전체 ZIP.
- VERSION.txt: 0.29.1.
- Android XML 36개 파싱 성공.
- `activity_main.xml` ID 106개, 중복 ID 0.
- 전체 Java/Kotlin `R.id` 참조 255개, 누락 0.
- 주행 페이지에서 누적 에너지 비교 카드 제거 확인.
- 누적 에너지 비교 ID 4개를 피드백 페이지로 이동 확인.
- 테스트 스위치/GPX 위치 슬라이더를 주행 페이지에서 제거하고 설정 페이지로 이동 확인.
- 속도/종점 ETA/10km 고도/10km 후 배터리 4개 안내뷰는 주행 HUD에서 GONE 처리 확인.
- 주행 고도 프로필 높이 50dp → 178dp, compact mode true → false 확인.
- `.github/workflows` 3개는 v0.29.0과 SHA-256 동일(변경 없음).
- 변경 파일: MainActivity.kt, activity_main.xml, VERSION.txt, README.md, CHANGELOG.md + 신규 FIELD_TEST/V29_1 문서.
- 전체 Gradle 빌드 시도: Gradle 8.9 배포파일 다운로드 단계에서 `services.gradle.org` DNS 해석 실패로 중단. 소스 컴파일 오류로 인한 실패는 아님.
