# v0.31.5 validation

- 전체 ROAD 활동 목록: 고정 30개 제한 제거, page/per_page 반복 후 빈 페이지 또는 마지막 페이지에서 종료.
- 활동 스트림 분석: 후보 프로필에 활동별 로컬 PR/경사 구간 결과 캐시.
- 재개: 기존 후보에서 streamAnalyzed=true인 activity id 재사용.
- API 429: 후보를 incomplete로 저장하고 연동 비활성; 다음 실행에서 이어서 처리.
- PR: 15s/1m/2m/5m/10m/20m/40m/1h/2h/4h, 연도별 및 역대 계산.
- PR 원본: 날짜/활동명 표시.
- 1h 이상 PR: ENDURANCE 활동만 사용.
- 연도 선택 후 명시적 승인 연동.
