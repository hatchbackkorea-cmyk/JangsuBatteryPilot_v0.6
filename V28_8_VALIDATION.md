# v0.28.8 Validation

- VERSION: 0.28.8
- XML parse: 36 files OK
- `R.id` source references: 259, missing 0
- `ReleaseGitHubApi.java` + `ReleaseZipPackage.java`: javac syntax/type check with minimal Android/JSON stubs OK
- Git blob SHA algorithm verified against `git hash-object`: exact match
- v0.28.7 → v0.28.8 local simulation with workflows OFF: 191 managed paths, 8 upload, 183 identical skip, 0 delete
- ReleaseGitHubApi: remote Git tree blob sha/mode comparison added
- Identical files: skipped without `POST /git/blobs`
- Changed/new files: uploaded and written to new tree
- Stale app/gradle/root build files: deletion behavior preserved
- `.github/workflows` default OFF preservation unchanged
- PushResult/log: uploaded + skipped + deleted counts
- Release trigger: commit/ref update path preserved even if upload count is 0
- Full Gradle assemble attempted but wrapper could not resolve `services.gradle.org` in this environment (`curl: (6) Could not resolve host`).
