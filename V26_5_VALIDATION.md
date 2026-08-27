# v0.26.5 validation

- VERSION.txt = 0.26.5
- Android resource XML: 28 files parsed, 0 errors
- Kotlin R.id references: missing 0
- RideFormatter.kt standalone Kotlin compile: PASS
- MainActivity.kt parser smoke-check: no `expecting` / `unexpected tokens` syntax diagnostics (Android unresolved refs are expected outside Android build classpath)
- Auto Release workflow retained unchanged from v0.26.4
- ETA implementation reads actual battery/charge session once per screen render via EtaChargeContext to avoid point-list performance regression

## ETA rules
1. Arrival ETA excludes charging at the target station itself.
2. Arrival ETA includes every uncompleted planned charging stop before the target.
3. Active charge uses live BLE SOC; without BLE it subtracts elapsed charging time from planned charge duration.
4. POST_CHARGE stations are not counted again.
5. Departure ETA at a charging station = arrival ETA + that station's remaining planned charge duration.
6. Main next checkpoint, finish ETA, next GPX POI, and all point ETAs share the same charge-aware calculation.
