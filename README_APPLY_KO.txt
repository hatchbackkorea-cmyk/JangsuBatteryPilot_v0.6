TimeGate v0.34.80 적용 방법

1. 이 ZIP의 내용을 기존 JangsuBatteryPilot_v0.6 저장소 폴더에 모두 덮어씁니다.
2. APPLY_APP_03480.bat 를 한 번 실행합니다.
3. GitHub Desktop으로 돌아갑니다.
4. 변경 파일을 확인합니다.
5. Commit to main -> Push origin 합니다.
6. GitHub Actions에서 아래 3개가 모두 초록색인지 확인합니다.
   - Auto Release signed APK from main
   - Build Android APK
   - Timing accuracy regression tests

이번 패치는 앱 로직 오류 수정이 아니라, 새 START+FINISH 규칙과 오래된 테스트 파일의 불일치를 고치는 패치입니다.
