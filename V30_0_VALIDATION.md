# v0.30.0 Validation

## Source base
- Base: v0.29.7 build-fix source ZIP.
- Version: 0.30.0.
- Existing `.github/workflows` are byte-for-byte unchanged.

## Battery forensics v2
- FFF4 capture changed from one latest packet to an 8-second pre-buffer + 20-second post-window.
- Every captured raw packet stores timestamp, length, full hex and BLE address.
- Capture start/end store SOC so fast charging during the test is preserved rather than treated as noise.
- Readable GATT characteristics are swept once per capture. The new code does not write characteristics; the existing CCCD notification subscription remains the only BLE descriptor write.
- Existing `battery_forensics_v1` preferences and `files/battery_forensics` path are preserved, so an open v0.29.7 session can continue after update.

## ROAD Granfondo
- Launcher now opens a mode chooser: eMTB or ROAD.
- ROAD accepts a GPX, optional FIT files, optional 1/5/20/60-minute rider power and a target finish duration.
- A standalone pacing engine test on a synthetic 100 km course verified an exact 6:00:00 target and FINISH interpolation.
- Road FIT profile, pacing engine and group client passed isolated Kotlin type compilation with local stubs for Android/project classes.
- Live road screen uses the existing guarded RouteMatcher for GPX progress and shows target delta, next checkpoint clock time and predicted finish.

## Group ride beta
- App sends room/nickname/course-key/route-km/GPS/speed approximately every 10 seconds while group mode is enabled.
- Same-course riders updated within 60 seconds are shown as ahead/behind in route km.
- `group_relay_server.js` passed `node --check` and local HTTP smoke test with two riders in one room.
- A public HTTPS relay URL is still required for real multi-phone use outside the same local network.

## Static validation
- Android XML files: 39; parse errors: 0.
- Resource IDs: 303; unique `R.id` references: 300; missing IDs: 0.
- Duplicate IDs inside layouts: 0.
- Manifest contains one launcher (`BikeModeChooserActivity`) and ROAD/eMTB activities.
- Kotlin parser fatal-pattern scan (`expecting`, `syntax error`, `unexpected tokens`, `unclosed`): 0.

## Gradle
`./gradlew :app:assembleDebug` was attempted in this environment, but Gradle distribution download could not start because DNS could not resolve `services.gradle.org`:

```
Gradle 8.9 다운로드 중...
curl: (6) Could not resolve host: services.gradle.org
```

Therefore this validation does **not** claim a full Android APK compilation. GitHub Actions remains the authoritative full compile check.
