# v0.31.8 validation

## Static checks
- Android resource XML parse: PASS
- Edited Kotlin brace/parenthesis balance: PASS
- New IDs `rbRoadStrava`, `tvAdminWkg`: PASS
- Pure Kotlin compile smoke test: `StravaPerformanceEstimator`, `RoadPowerPaceEstimator`: PASS

## Feature flow
1. Strava OAuth requests `activity:read_all,profile:read_all`.
2. Detailed athlete profile caches current weight/current FTP.
3. User applies a selected Strava year from the ROAD review.
4. App estimates year FTP from year PR power curve; current Strava FTP is fallback.
5. Weight + effective FTP derive W/kg.
6. Rider Control Center sync receives weight/FTP and selected-year power curve.
7. ROAD main planner can use Strava basis to estimate riding target time from GPX.
8. Race Simulator participant dialog supports time/speed/cutoff/FTP/Wkg/Strava.
9. FTP <-> W/kg calculations use body weight and update bidirectionally.

## Build note
A complete Android Gradle assemble was not run in this container because the Gradle/Android dependency environment is not locally available and outbound dependency download is unavailable. Existing GitHub Actions release workflow should perform the authoritative Android build.
