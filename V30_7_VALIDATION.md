# v0.30.7 Validation

- [x] ROAD Strava/FTP/체중/W/kg 문자열/입력 없음
- [x] 목표 주행시간: 시/분 Spinner
- [x] 목표 평속 기준 선택 및 시간 상호 환산
- [x] 출발시각: 시/분 Spinner
- [x] 보급 정차: 0~60분, 1분 단위 Spinner
- [x] 최종 계획시간 = 순수 주행시간 + 보급 정차 합계
- [x] 구간별 절대 시각 표시
- [x] PDF SAF 저장 + 고도그래프 + 일정표
- [x] 시뮬레이션 이름 36px + 외곽선
- [x] 시뮬레이션 보급시간 별도 합산
- [x] VERSION.txt = 0.30.7

로컬 환경은 services.gradle.org DNS 접근이 차단되어 Gradle 실제 빌드는 GitHub Actions에서 최종 확인한다. XML/정적 검사 및 ZIP 무결성 검사를 별도로 수행한다.
