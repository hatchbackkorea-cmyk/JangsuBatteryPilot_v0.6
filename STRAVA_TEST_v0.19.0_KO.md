# v0.19.0 Strava 1차 검증

1. Strava 자동연동은 꺼 둔다.
2. 앱 설정 → STRAVA → Strava 연결 / FIT 업로드.
3. Strava API 페이지의 Client Secret을 휴대폰 앱에만 붙여넣고 `Secret 저장`.
4. `Strava 연결` → Strava 승인.
5. `Avinox FIT 선택` → Rider/Motor 미리보기 확인.
6. `STRAVA에 업로드`.
7. Strava에서 평균/최대 파워, 케이던스, 거리/고도 확인.

중요: Client Secret, Access Token, Refresh Token은 채팅/스크린샷에 올리지 않는다.
이번 버전은 Avinox 원본 FIT를 변형하지 않고 Strava에 직접 전송하여 Avinox 클라우드 자동연동의 가공 영향을 먼저 분리 검증한다.
