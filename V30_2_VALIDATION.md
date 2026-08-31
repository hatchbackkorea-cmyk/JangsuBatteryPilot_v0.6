# v0.30.2 validation

Base: v0.30.1 full source.

## Added
- RoadRaceSimulationActivity.kt
- RoadRaceSimulationEngine.kt
- RoadRaceSimulationView.kt
- activity_road_race_simulation.xml
- GRANFONDO_SIMULATION_INPUT_v0.30.2_KO.md
- FIELD_TEST_v0.30.2_KO.md

## Static checks
- XML files under app/src/main: 40
- XML parse errors: 0
- unique Kotlin R.id references: 301
- missing resource IDs: 0
- RoadRaceSimulationActivity is declared in AndroidManifest.xml.
- Changed Kotlin files have balanced raw braces/parentheses and no `expecting`/syntax-error diagnostics in parser-level kotlinc check.
- RoadRaceSimulationEngine.kt compiled successfully with project-model stubs.
- Synthetic engine test passed: aid-station pause state, finish time, checkpoint ranking, and aid-station checkpoint arrival-before-rest behavior.
- `.github` has no differences from v0.30.1.

## Gradle
Attempted:
`./gradlew :app:compileDebugKotlin --offline`

The wrapper could not download Gradle 8.9 because the environment could not resolve `services.gradle.org`:
`curl: (6) Could not resolve host: services.gradle.org`

Therefore this environment did not complete an Android Gradle compile. GitHub Actions should be used for the real Android compile/release check.

## Package
- ZIP integrity: `unzip -t` passed with no errors.
