# v0.31.4 validation

- ROAD 화면에 Strava 분석/검토 진입 카드 추가
- OAuth callback activity 추가
- Strava 읽기 전용 scope: activity:read_all
- 분석 후보와 활성 연동 프로필을 SharedPreferences에서 분리
- 불러오기만으로 active profile이 바뀌지 않음
- 긴 휴식 분류 규칙:
  - 최대 정차 >= 15분 또는
  - 총 정차 >= 30분 또는
  - 정차 비율 >= 20%
  => PARTIAL (장거리 지속능력 제외, 구간/파워 사용)
- ENDURANCE 후보:
  - 50km 이상, 이동 2시간 이상
  - 최대 정차 < 10분
  - 총 정차 < 25분
  - 정차 비율 < 15%
- 1시간 이상 파워 PR은 ENDURANCE 활동만 사용
- XML/manifest parsing, ID binding scan, ZIP integrity를 생성 후 검사
