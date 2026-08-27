# v0.26.6 validation

- VERSION.txt: 0.26.6
- Kakao REST key is read from `KAKAO_REST_API_KEY` environment / Gradle property and exposed only to the built app through BuildConfig; key value is not committed to source.
- Auto Release workflow passes the GitHub Repository Secret to the release build.
- Emergency course return anchor is persisted locally and route progress is frozen at the anchor until GPS returns within 50 m.
- Planned-charge skipping affects both projected SOC and ETA charge-time accumulation.
- Emergency detour remaining travel/charge/return time is added to downstream ETA.
- XML/YAML/R.id/static source checks are run before packaging.
- Full Gradle assemble could not be run in the generation environment because `services.gradle.org` DNS/network access is unavailable. GitHub Actions is the authoritative compile/build test.
