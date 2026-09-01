# v0.32.0 실시간 그룹 라이딩 현장 테스트

1. 관리자 메뉴에서 Google Cloud Rider Control Center URL과 기존 Device Token이 연결되어 있는지 확인합니다.
2. ROAD에서 GPX를 불러오고 주행 시작 → 6자리 방 코드 → 그룹 연결을 누릅니다.
3. 화면에 `● 실시간 WebSocket 연결됨`이 표시되는지 확인합니다.
4. PC Rider Control Center → `실시간 그룹`에서 같은 방 코드를 입력하고 연결합니다.
5. 참가자 위치/순위가 약 1~2초 단위로 바뀌는지 확인합니다.
6. LTE↔Wi-Fi 전환 후 자동으로 재연결되는지 확인합니다.
7. 장시간 주행에서는 Cloud Run WebSocket 연결이 끊겨도 앱이 자동 재연결합니다.
