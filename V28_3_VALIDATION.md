# v0.28.3 validation

- `VERSION.txt = 0.28.3`
- Android resource XML 35개 + AndroidManifest.xml 파싱 정상 (총 36)
- Kotlin/Java `R.id` 참조 258건 검사, 누락 0
- `MainActivity.kt`, `KakaoBicycleNavigator.kt`, `RideReplanStore.kt` 괄호/대괄호/중괄호 기본 구조 균형 정상
- `KakaoBicycleNavigator.kt`를 `kotlinc 1.9.0`으로 실제 컴파일 정상
- 공식 Kakao Map bicycle URL scheme 생성값 확인: `kakaomap://route?sp=...&ep=...&by=bicycle`
- 앱 scheme 실패 시 REST bicycle `landingUrl`, 없으면 mobile web bicycle scheme으로 폴백
- 비상 후보 확정 시 OUTBOUND 세션 저장 후 Kakao bicycle route 실행
- 비상 충전 완료 시 RETURN 전환 후 원래 GPX 이탈점 Kakao bicycle route 자동 실행
- Battery Copilot RideService는 foreground location service + `START_STICKY`; 외부 Kakao Map이 전면에 있어도 GPS/BLE/SOC/로그 처리를 계속 유지
- 전체 Android Gradle assemble은 이 실행 환경에서 `services.gradle.org` DNS 해석이 되지 않아 수행하지 못함
