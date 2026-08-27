# v0.19.2 Strava 클린 FIT 테스트

## 수정
- v0.19.1 `StravaActivity.kt`의 Assist ratio 계산에서 nullable Double을 직접 나누던 Kotlin 컴파일 오류 수정.

## 테스트 순서
1. GitHub Build Android APK가 초록불인지 확인
2. Signed Release v0.19.2 생성 후 기존 앱 위에 설치
3. 설정 → STRAVA → Secret 저장 → Strava 연결
4. Avinox FIT 선택
5. Rider Power / HR / Cadence / Motor / Battery / Mode 미리보기 확인
6. 클린 FIT → STRAVA 업로드
