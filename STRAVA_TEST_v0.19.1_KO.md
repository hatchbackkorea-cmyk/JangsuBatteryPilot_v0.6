# v0.19.1 Strava 클린 FIT 테스트

## 최초 1회 연결
1. 앱 `설정 → STRAVA`로 이동합니다.
2. Strava API 화면의 **Client Secret**을 휴대폰 앱에만 붙여넣고 `Secret 저장`을 누릅니다.
3. `Strava 연결` → 승인합니다.
4. Client Secret / Access Token / Refresh Token은 채팅, 캡처, 로그에 공유하지 않습니다.

## 주행 후 업로드
1. Avinox에서 해당 주행의 원본 FIT를 휴대폰에 가져옵니다.
2. `Avinox FIT 선택`을 누르고 FIT를 선택합니다.
3. 앱이 같은 주행의 Jangsu BLE 로그를 자동으로 찾습니다.
4. 미리보기에서 다음 항목을 확인합니다.
   - Rider 평균/최대 Power
   - Heart Rate 평균/최대
   - Cadence 평균
   - Motor 평균/최대 Power 및 Wh
   - Battery 시작→종료
   - ECO/AUTO/TRAIL/TURBO 사용비율
   - 각 FIT 필드 기록률
5. `클린 FIT → STRAVA 업로드`를 누릅니다.

## 원칙
- Strava 표준 power = **사람이 낸 Rider Power**만 사용합니다.
- Motor Power는 별도의 e-bike 필드와 활동 설명으로 보존합니다.
- Avinox FIT에 없는 Battery/Mode는 같은 주행의 BLE 로그가 확실히 매칭될 때만 추가합니다.
- Heart Rate나 기타 센서값이 원본에 없으면 임의로 만들지 않습니다.
- `원본 FIT 직접 업로드 (비교)`는 A/B 확인용입니다. 같은 활동은 Strava 중복 판정이 날 수 있습니다.
