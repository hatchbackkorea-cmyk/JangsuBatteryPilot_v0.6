# v0.28.7 Validation

- VERSION.txt: `0.28.7`
- FileProvider XML now covers all three APK locations used by the app:
  - `external-files/updates/` — normal app update download
  - `cache/release_apk/` — immediate/manual Release download
  - `files/release_apk/` — background ReleaseDeployService download
- Background deployment source still writes ready APK to `getFilesDir()/release_apk` and the install button still opens the saved path via the same FileProvider authority.
- No GitHub workflow/signing or ride/battery logic changed.
- Android resource XML + manifest parse: 36/36 OK.
- Java/Kotlin `R.id` references: missing resource IDs 0.
- `.github/workflows/*` hashes are identical to v0.28.6.
- Full Android assemble was attempted offline; Gradle wrapper distribution is not cached in this environment, so the wrapper attempted network resolution and could not complete here.
