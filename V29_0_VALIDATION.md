# v0.29.0 Validation — Rain / Bad GPS Guard

## Scope
- Base: v0.28.9 Avinox context reanalysis.
- Functional source changes only in `RideService.kt` and `RouteMatcher.kt`.
- `.github/workflows` unchanged.

## Static checks
- Android XML parsed: 36 / 36 OK.
- `R.id` references: 261 refs / 255 unique / missing 0.
- VERSION.txt: 0.29.0.

## RouteMatcher executable tests
Pure Kotlin test harness compiled and ran successfully:
- One bad first fix at 134 km does not teleport a fresh ride.
- Stable mid-course position is accepted only after repeated confirmation.
- Persisted corrupted 134 km progress automatically recovers to the stable actual 40 km position.
- A far off-course fix cannot advance the route window endpoint.

Result: `RouteMatcher v0.29.0 tests OK`.

## GPS guard behavior
- Accuracy > 60 m: location tick rejected from planned-route progress/learning log.
- Implausible raw movement > 90 km/h with distance allowance: rejected.
- Stale location older than 30 s: rejected.
- When GPS provider is enabled, network provider fixes are not mixed into ride tracking.
- Large route relocation requires repeated stable confirmation.
- Rejected/held fixes keep last trusted route progress and do not write uncertain location samples.
- BLE/SOC/charge service remains active because only the location tick is discarded.

## Gradle build
`./gradlew :app:compileDebugKotlin` was attempted. The wrapper could not download Gradle because `services.gradle.org` DNS resolution is unavailable in this environment (`curl: (6) Could not resolve host`). Therefore a full Android build could not be completed locally.
