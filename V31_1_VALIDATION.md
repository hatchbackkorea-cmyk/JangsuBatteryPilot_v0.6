# v0.31.1 Validation

- 참가자 추가/수정 목표 기준: 목표시간 / 목표평속 / 컷오프 페이스 자동 평속 3종.
- 컷오프 참가자는 ROAD 화면에 저장된 대회 컷오프 시각을 사용하고 참가자 출발지연 및 개별 보급정차를 반영해 목표 주행시간을 역산.
- 참가자 설정 다이얼로그는 스크롤 영역과 하단 고정 버튼바로 분리.
- XML 39개 파싱 오류 0.
- RoadGranfondoEngine + RoadRaceSimulationEngine 순수 Kotlin 컴파일 통과.
- 컷오프 계산 샘플 및 SimulationRiderPlan 생성 통과.
- Android 전체 Gradle 컴파일은 현재 실행환경에 gradle 실행기가 없어 GitHub Actions에서 최종 확인 필요.
