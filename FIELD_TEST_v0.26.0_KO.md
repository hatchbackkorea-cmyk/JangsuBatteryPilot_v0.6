# v0.26.0 Avinox 원본 A+ 자동학습 현장 테스트

## 목표
Avinox 앱이 동기화해 저장한 `cloud_ride_rec_*.proto` 원본을 Shizuku(shell 권한)로 읽어 앱 내부에 복사하고, 실제 SOC + 모드 + Rider/Motor Power + Torque + Cadence + Gear + GPS/고도를 같은 시간축으로 A+ 학습한다.

## 최초 1회
1. Shizuku 설치
2. 개발자 옵션의 무선 디버깅으로 Shizuku 시작
3. GPX 배터리 코파일럿 → 설정 → 배터리 학습 데이터
4. `Shizuku 권한` → 허용
5. `원본 지금 동기화`

## 정상 표시
- `Avinox 원본 자동동기화 준비됨`
- 첫 동기화: `Avinox 원본 N개 A+ 동기화 · 학습 N구간`
- 이후 새 라이딩이 없으면: `새 Avinox 원본 없음 · 동기화 완료`

## 일상 사용
1. Avinox 앱에서 주행 동기화
2. GPX 배터리 코파일럿 실행/복귀
3. 새 원본 최대 8개를 자동 수집/학습

## 데이터 안전장치
- `cloud_ride_rec_*_<양수ID>.proto`만 읽음
- Avinox 원본 v8만 학습. 형식 버전이 바뀌면 자동 거부
- 배터리/시간/GPS 커버리지를 검사하고 품질 불량 파일은 학습하지 않음
- 100→98% 완충 plateau는 기존 규칙대로 제외
- 한 SOC 구간 안에 모드가 바뀌거나 알 수 없는 assist code가 있으면 그 구간 제외
- 30초 이상 전원 OFF/센서 공백을 모드 window에서 분리해 공백을 가로지르는 SOC 학습 제외
- 중간 충전(SOC 상승)은 소비 학습에서 자동 제외하고, 총 사용량은 하락분만 합산
- 과거 원본에는 현재 Assist 세팅(profileId)을 억지로 붙이지 않음
- FIT 자동학습은 Shizuku 원본 동기화가 불가능할 때만 B급 백업으로 동작

## 검증에 사용한 실제 원본
`cloud_ride_rec_1292.proto`
- 형식 v8 / Ride ID 1292
- 4,413 samples
- 32.045 km
- SOC 100% → 41%
- 상승 409 m / 하강 405 m
- 파서 품질 100
- SOC 변화 체크포인트 60개
- 모드 window 32개
