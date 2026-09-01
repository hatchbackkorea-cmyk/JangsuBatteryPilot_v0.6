# v0.31.3 validation

- 참가자 1명 = 카드 1개 UI로 변경.
- 카드별 수정 버튼 동작 연결.
- 보급 목록 기본 접힘, 펼침/접힘 토글 구현.
- 펼친 보급 목록은 보급소 1곳당 한 행, 정차시간 우측 정렬.
- 긴 보급소 이름은 singleLine + ellipsize 처리.
- activity_road_race_simulation.xml 및 전체 Android XML 파싱 오류 0.
- RoadRaceSimulationActivity의 R.id 참조가 해당 레이아웃 ID와 일치함.
- Android 의존성이 없는 로컬 환경에서는 전체 APK Gradle 컴파일을 수행하지 못하므로 GitHub Actions가 최종 컴파일 기준.
