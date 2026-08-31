# v0.30.3 FIX1 validation

- `RoadGranfondoActivity.kt` release compile error fixed.
- Kotlin string interpolation `FIT $ok개` changed to `FIT ${ok}개` so `개` is not parsed as part of the identifier.
- Scanned Kotlin sources for ASCII variable interpolation immediately followed by Hangul; no remaining matches.
- Local Gradle compile could not run because this environment cannot resolve `services.gradle.org`; GitHub Actions should perform the release compile.
