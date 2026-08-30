# v0.28.5 validation

- `VERSION.txt = 0.28.5`
- 2페이지 strategy table is a dynamic `TableLayout` with point + ECO/AUTO/TRAIL/TURBO columns.
- Every mode projection calls mode-explicit `BatteryLearningStore.estimateConsumption(course, ..., mode)`; no Avinox benchmark percentage enters the calculation.
- ETA speed uses `learnedSpeedKphForMode(bucket, mode)`, which excludes FIT-only B-grade and other-mode speed from the primary mode-specific value.
- Missing mode-speed data falls back visibly to current moving average / all-learning speed and is marked in the basis text.
- Planned charge targets remain common user plan inputs; arrival SOC and charge duration are recalculated independently per mode.
- Avinox original Proto remains A+ primary learning path; FIT-only remains B-grade fallback.

## 실행한 검사
- Android resource XML + Manifest 36개 XML parse 통과.
- Kotlin/Java의 `R.id` 참조 253개 검사, 누락 0개.
- `VERSION.txt` 0.28.5 확인.
- 전략표 코드가 ECO/AUTO/TRAIL/TURBO 네 모드에 대해 명시적 mode 인자를 사용하고 `learnedSpeedKphForMode`를 호출하는지 정적 검사 통과.
- 전체 Android `assembleDebug`는 실행을 시도했지만 Gradle 8.9 wrapper를 내려받는 단계에서 `Could not resolve host: services.gradle.org`로 중단되어 이 환경에서는 APK 빌드를 완료하지 못했습니다.
