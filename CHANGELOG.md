# CHANGELOG

## 0.10.2 — Ride Stats Accuracy Fix
- FIT 분석은 Record 재계산보다 Session/Lap의 `total_distance`, `total_timer_time`, `total_ascent`, `total_descent`를 우선 사용
- FIT 고도는 `enhanced_altitude`를 우선 사용하고 Session 통계가 없을 때만 트랙 고도로 보완
- GPX 획득/손실고도 계산에서 1m 단위 필터를 제거해 완만한 상승이 누락되던 문제 수정
- GPX는 타임스탬프와 GPS 이동구간으로 이동시간을 계산하고 거리/이동시간으로 평속 계산
- 과거 라이딩 분석 화면에서 심박/케이던스/파워 표시 제거
- 분석 결과를 거리 / 획득고도 / 손실고도 / 이동시간 / 평속 중심으로 단순화
- 기존에 같은 FIT/GPX를 학습했어도 새 분석값으로 교체 학습 가능

## 0.10.1 — Historical Ride Build Fix
- `HistoricalRideActivity`의 중간 배터리 목록에서 TextView 지역 변수 `text`가 Button의 `text` 속성을 가린 컴파일 오류 수정
- 기능 변경 없이 v0.10.0의 과거 FIT/GPX 학습 기능 유지

## 0.10.0 — Historical Ride Learning
- 설정 메뉴에 `과거 라이딩 학습 가져오기 (FIT / GPX)` 추가
- FIT 파일 분석: GPS/거리/고도/시간/속도/심박/케이던스/파워(기록된 항목만)
- GPX 파일 분석: GPS/거리/고도/시간 및 확장 심박/케이던스/파워(있는 경우)
- 시작/종료 배터리 또는 총 사용 배터리 %를 입력해 과거 라이딩을 개인 배터리 학습에 반영
- 중간 배터리 지점 및 중간 충전 지점 입력 지원
- 같은 파일을 SHA-256으로 식별해 중복 학습 방지
- 학습된 과거 FIT/GPX 목록 확인 및 개별 학습 삭제
- 전체 개인 학습 초기화 시 과거 파일에서 만든 학습 기록도 함께 초기화
- FIT 디코딩에 Garmin 공식 FIT Java SDK 사용

## 0.9.2 — Learning Safety Patch
- 주행 로그 저장과 배터리 학습을 분리
- 주행 종료 후 학습 사용 여부를 직접 선택
- 테스트 모드 주행은 학습에서 자동 제외
- 설정 메뉴에 개인 배터리 학습 데이터 확인/초기화 추가

## 0.9.1 — Build Fix
- CourseActivity Kotlin 문자열 보간 컴파일 오류 수정

## 0.9.0 — GPX Charging Planner
- GPX 웨이포인트/주소/km/현재 위치 기반 충전소 계획
- 다음 충전소 우선 배터리 판단
