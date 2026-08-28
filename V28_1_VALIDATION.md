# v0.28.1 validation

- `VERSION.txt = 0.28.1`
- Android resource XML 36개 전체 파싱 정상
- Kotlin/Java `R.id` 참조 258건 검사, 누락 0
- `MainActivity.kt` 괄호/대괄호/중괄호 기본 구조 균형 정상
- `PlCarbonGearAdvisor.kt` 최소 타입 스텁과 함께 `kotlinc` 컴파일 정상
- Rain Touch Lock: 잠금 시 `dispatchTouchEvent()`가 앱 내부 터치를 전부 소비하고 서비스/렌더 갱신은 유지
- VOL- 1.5초 길게 = 잠금/해제, 잠금 중 VOL+ 짧게 = 다음 페이지, VOL- 짧게 = 이전 페이지
- 잠금 해제 상태에서 VOL- 짧게는 `STREAM_MUSIC` 볼륨을 한 단계 낮추고 VOL+는 Android 기본 처리
- 하단 페이지 인디케이터에 잠금 상태 표시 및 잠금/해제 진동 피드백
- VIBRATE permission 명시
- 어시스트 표기: 권장속도 + `권장기어 N단` 문구 반영
- 전체 Android Gradle compile은 실행 환경 DNS가 `services.gradle.org`를 해석하지 못해 wrapper 다운로드 단계에서 중단됨
