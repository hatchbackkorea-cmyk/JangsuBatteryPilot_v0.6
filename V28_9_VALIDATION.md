# v0.28.9 Validation

- VERSION: `0.28.9` (`VERSION.txt`; Gradle derives versionCode/versionName from this file)
- Base: v0.28.8 incremental mobile deploy
- Scope: Avinox original context-v2 reinterpretation + page-2 strategy integration only
- XML parse: 36 manifest/resource XML files OK
- `R.id` source references: 261 occurrences / 255 unique, missing 0
- New `ContextualBatteryLearningStore.kt`: Kotlin type/syntax compile with minimal Android/JSON/project stubs OK
- Modified Kotlin files (`MainActivity`, `HistoricalRideActivity`, `SettingsActivity`, `AvinoxProtoSyncManager`, `BatteryLearningStore`): Kotlin parser scan found no syntax-level errors
- Existing Avinox mode mapping preserved: 1=ECO, 2=TRAIL, 3=TURBO, 4=AUTO
- Context-v2 persistence is separate from legacy v1 (`battery_context_learning_v2.json` / separate prefs)
- Reanalysis scans internally preserved Avinox `.proto`, SHA-256 deduplicates sources, rebuilds v2 only, and leaves v1 untouched
- Automatic reinterpretation is one-shot per v2 schema; manual `기존 Avinox 원본 전부 재해석` remains available
- New Proto synchronization trains legacy v1 and context-v2 together
- Page-2 mode strategy uses context-v2 only above confidence/sample threshold, otherwise falls back block-by-block to v1
- Live ride SOC prediction core continues using existing `estimateConsumption()`; no v2 substitution there
- Context estimator uses 0.5% grade-band cache keyed by model revision to avoid repeated full sample scans during strategy rendering
- `.github/workflows` SHA-256 values exactly unchanged from v0.28.8 (signed Release workflow preserved)
- v0.28.8 → v0.28.9 incremental-deploy simulation with workflows OFF: 194 managed paths, 12 changed/new, 182 identical skipped, 0 deleted
- Full Gradle `:app:assembleDebug --offline` attempted, but local Gradle 8.9 distribution was absent and `services.gradle.org` DNS could not resolve (`curl: (6) Could not resolve host`), so a full Android assemble could not be completed in this environment.
