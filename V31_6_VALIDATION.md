# v0.31.6 Rider Control Center 자동동기화 검증

## 추가된 연결
- 설정 화면에 Rider Control Center 서버 주소 / 1회 Device Token / 라이더 프로필 / 자동동기화 추가
- Device Token은 Android Keystore AES/GCM 저장
- 앱 복귀 시 자동 동기화
- GPX 가져오기 시 서버 업로드 대기열 등록
- 로드 페이스 계획 생성 시 현재 GPX를 먼저 등록하고 계획 업로드
- 주행 종료 ZIP 자동 업로드 대기열 등록
- Strava ROAD 프로필 갱신 후 서버 프로필/파워커브 동기화
- 네트워크 실패 시 로컬 durable queue 유지 후 다음 연결 시 재시도
- COURSE / PACE_PLAN / RIDE client_key 기반 서버 중복 방지

## 정적 검증
- Android resources XML parse OK
- 신규 Settings view ID ↔ Kotlin 참조 일치
- AndroidManifest INTERNET + ALPHA용 cleartext LAN 통신 허용 확인
- 서버 Python py_compile OK
- 기존 server smoke_test OK
- 신규 mobile sync regression test OK

## 제한
이 작업환경에는 Android SDK/Gradle wrapper JAR가 없어 실제 APK assemble은 수행하지 못했다. 프로젝트의 기존 GitHub Actions APK 빌드 workflow를 그대로 사용해 컴파일 검증한다.
