# v0.30.1 Validation

## Source base
- Base: verified v0.30.0 ROAD/forensics source tree.
- Version: 0.30.1.
- Existing `.github/workflows` are byte-for-byte unchanged from v0.30.0.

## ROAD / Group changes
- Startup chooser keeps eMTB and ROAD modes separated.
- ROAD keeps GPX + optional FIT + optional 1/5/20/60-minute power + target finish duration pacing.
- Group relay room capacity is capped at 20 riders. A 21st new rider receives HTTP 409 `room_full`; an existing rider can continue updating.
- ROAD UI adds a six-digit room-code generator and explicit max-20 messaging.
- Group client maps HTTP 409 `room_full` to a Korean capacity message.
- `group_relay_server.js` passed `node --check`.
- Local relay smoke test: riders 1-20 HTTP 200, rider 21 HTTP 409, room query returned 20 riders, existing rider update still HTTP 200.

## Battery forensics v2 preserved
- State capture keeps the v0.30.0 8-second pre-buffer + 20-second post-window FFF4 capture.
- Start/end SOC and readable GATT sweep per capture remain enabled.
- Existing `battery_forensics_v1` preferences/session path are unchanged so v0.29.7 sessions remain continuable.

## Static validation
- Android XML files: 39; parse errors: 0.
- Resource IDs: 304; unique `R.id` references: 301; missing IDs: 0.
- Duplicate IDs inside layouts: 0.
- Manifest launcher count: 1; BikeModeChooser, RoadGranfondo and BatteryForensics activities present.
- `GroupRideClient.kt` isolated Kotlin compile with JSON stubs: success (warnings only).
- `RoadGranfondoActivity.kt` parser fatal-pattern scan (`expecting`, `syntax error`, `unexpected tokens`, `unclosed`): 0. Android/project unresolved references are expected without Android SDK classpath.

## Gradle
`./gradlew :app:compileDebugKotlin --offline` was attempted, but the wrapper distribution is not cached and this environment cannot resolve `services.gradle.org`:

```
Gradle 8.9 다운로드 중...
curl: (6) Could not resolve host: services.gradle.org
```

Therefore this validation does **not** claim a full Android APK compilation. GitHub Actions remains the authoritative full compile check.
