# v0.31.8 현장 테스트

1. 기존 v0.31.7 위에 업데이트 설치합니다. Rider Control Center 토큰/IP는 다시 발급하지 않습니다.
2. ROAD → Strava 분석을 엽니다.
3. 기존 Strava 연결에 `profile:read_all`이 없다면 `Strava 연결/재연결`을 한 번 실행해 권한을 승인합니다.
4. 전체 ROAD 분석에서 기준연도를 선택하고 `이 분석을 연동`합니다.
5. 화면에서 연도 PR 추정 FTP, 현재 Strava FTP(있는 경우), 체중, W/kg가 표시되는지 봅니다.
6. ROAD 목표 페이스 계획에서 `Strava 기준`을 선택합니다. GPX 기준 예상 순수주행시간/평속이 나타나는지 확인합니다.
7. Race Simulator → 참가자 추가 → FTP 기준: 체중+FTP 입력 시 W/kg 자동 계산 확인.
8. W/kg 기준: 체중+W/kg 입력 시 FTP 자동 계산 확인.
9. Strava 기준: 기준연도 선택 시 체중/FTP/Wkg가 자동 채워지고 예상 주행시간이 생성되는지 확인합니다.
10. PC Rider Control Center에서 해당 라이더 체중/FTP/파워커브가 갱신되는지 확인합니다.

※ `연도 PR 추정 FTP`는 Strava가 제공하는 과거 FTP 기록이 아니라, 해당 연도의 파워 PR을 이용한 앱 추정치입니다.
