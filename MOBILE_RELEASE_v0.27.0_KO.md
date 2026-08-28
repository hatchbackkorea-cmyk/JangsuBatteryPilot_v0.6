# v0.27.0 모바일 소스 배포

## 이제부터 휴대폰에서 하는 흐름

1. ChatGPT에서 새 `JangsuBatteryPilot_v*.zip` 다운로드
2. Battery Copilot → `설정` → `모바일 소스 배포`
   - 또는 다운로드한 ZIP의 `공유` → `GPX 배터리 코파일럿`
3. 최초 한 번만 GitHub Fine-grained token 입력
4. `GitHub main에 배포`
5. 앱이 ZIP/버전/전체 프로젝트 구조 검증
6. GitHub main에 한 커밋으로 업로드
7. 기존 `auto-release-main.yml`이 고정 keystore로 signed Release APK 생성
8. 앱이 Release 생성을 자동 감지
9. `완성된 APK 다운로드 · 설치` → Android 설치 승인

Android 보안상 마지막 설치 승인 버튼은 사용자가 직접 눌러야 합니다.

## GitHub token 권장 권한

Fine-grained personal access token에서:

- Repository access: BatteryPilot 저장소만 선택
- Contents: `Read and write`

평소 `ZIP의 .github/workflows도 업데이트`는 OFF로 둡니다. 이 상태에서는 현재 정상 작동 중인 사인 Release workflow를 보존합니다.

workflow 자체도 ZIP 버전으로 교체하려는 경우에만:

- Workflows: `Read and write`

권한을 추가하고 체크박스를 켭니다.

## 보안/안전장치

- token은 Android Keystore AES/GCM 키로 암호화 저장
- keystore/jks/apk/aab/local.properties/.git/.gradle/build 업로드 제외
- `VERSION.txt`, Android manifest, Gradle 프로젝트 구조 없으면 배포 차단
- 같은 버전 Release가 이미 존재하면 덮어쓰기 차단
- GitHub main ref 갱신은 force push를 사용하지 않음
- 새 ZIP에서 삭제된 `app/`/`gradle/` 파일은 원격에서도 제거해 오래된 소스 충돌 방지
- workflow 업데이트는 기본 OFF

## 최초 한 번

v0.27.0만 기존 방식(PC에서 ZIP 업로드 → 자동 signed Release)으로 설치하면 됩니다.
그 다음 v0.27.1부터는 위 모바일 배포 화면을 사용할 수 있습니다.
