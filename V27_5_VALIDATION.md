# v0.27.5 validation

- Android resource XML 35개 parse 오류 0
- resource id 248개 수집 / Kotlin·Java R.id 참조 누락 0
- MainActivity.kt 괄호/중괄호 balance 정상
- ElevationProfileView.kt 괄호/중괄호 balance 정상
- 새 warning drawable 3개 존재 확인
- Kotlin parser 실행 시 Android SDK classpath 부재에 따른 unresolved reference만 발생, parser-level syntax 오류 패턴 없음
- 이 실행환경에는 Android SDK/Gradle Android 플러그인 캐시가 없어 assembleRelease는 실행하지 못함. GitHub Actions에서 실제 Android compile/sign 검증 필요.
