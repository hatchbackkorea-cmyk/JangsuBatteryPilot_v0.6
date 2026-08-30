# v0.28.7 현장 테스트 — 완성된 APK 재설치

1. 모바일 배포에서 새 ZIP을 main에 배포합니다.
2. signed Release APK가 준비되면 배포 직후 설치 팝업으로 1회 설치가 열리는지 확인합니다.
3. 설치 팝업을 닫거나 앱으로 돌아옵니다.
4. 배포 페이지의 `완성된 APK 설치` 버튼을 누릅니다.
5. `Failed to find configured root` 없이 Android 설치 화면이 다시 열리는지 확인합니다.
6. 앱을 재실행한 뒤에도 `완성된 APK 설치`가 저장된 APK 파일이 남아 있는 동안 다시 열리는지 확인합니다.

회귀 확인: `ZIP의 .github/workflows도 업데이트`는 기존처럼 기본 OFF이며 GitHub Release/서명 workflow는 변경하지 않았습니다.
