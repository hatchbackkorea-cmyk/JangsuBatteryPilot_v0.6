# v0.28.6 Validation

- VERSION.txt: `0.28.6`
- Android resource XML + manifest parse: 36/36 OK
- Kotlin `R.id` references: 240, missing resource IDs: 0
- `MainActivity.kt` delimiter balance after lexical stripping of strings/comments/chars: OK
- Charging strategy projection now stores arrival clock, arrival SOC, charge target, stop minutes, departure clock per mode.
- Charging rows render `도착시각 / SOC→목표 / 정차 N분 / 출발 HH:mm`; non-charging rows remain compact.
- Active charging uses remaining charge minutes; completed/skipped charge rows do not add duplicate stop time.
- Full Android assemble attempted with Gradle wrapper but could not start because `services.gradle.org` DNS resolution is unavailable in this environment.
