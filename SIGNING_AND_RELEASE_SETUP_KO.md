# v0.16.0 고정 서명 + 자동 업데이트 1회 설정

이 설정은 **한 번만** 하면 됩니다. 이후 v0.16.1, v0.17.0... APK는 같은 서명으로 만들어져 기존 앱 위에 업데이트됩니다.

## 1. 고정 서명키 만들기

Windows에서 `tools/create-signing-key.ps1`을 실행합니다.

생성되는 파일:
- `gpxbattery-release.jks` : **절대 공개 저장소에 올리지 말 것**
- `ANDROID_KEYSTORE_BASE64.txt` : GitHub Secret 등록용. 등록 후 안전하게 보관/삭제

`.jks`와 비밀번호를 잃어버리면 기존 사용자 앱을 같은 패키지로 업데이트할 수 없습니다. 반드시 별도 백업하세요.

## 2. GitHub Repository Secrets 4개 등록

저장소 → Settings → Secrets and variables → Actions → New repository secret

- `ANDROID_KEYSTORE_BASE64` = txt 파일의 한 줄 전체
- `ANDROID_KEYSTORE_PASSWORD` = keystore 비밀번호
- `ANDROID_KEY_ALIAS` = `gpxbattery`
- `ANDROID_KEY_PASSWORD` = key 비밀번호

비밀키/비밀번호는 소스 ZIP이나 GitHub 커밋에 넣지 않습니다.

## 3. 업데이트 저장소

앱은 GitHub Releases를 조회합니다. 지인들이 로그인 없이 업데이트하려면 **릴리스가 있는 저장소는 Public**이어야 합니다.

기본은 빌드한 저장소 자체(`owner/repo`)가 업데이트 저장소가 됩니다.

별도 공개 배포 저장소를 쓰고 싶다면 Actions의 Repository variable `UPDATE_REPOSITORY`에 `owner/repo`를 지정할 수 있습니다. 단, 릴리스 APK도 그 저장소에 실제로 게시되어 있어야 합니다.

## 4. 첫 기준판 v0.16.0 릴리스

Secrets 등록 후 `v0.16.0` 태그를 push하면 `release-apk.yml`이:
1. 고정키 복원
2. Release APK 서명
3. APK + SHA-256 생성
4. GitHub Release 게시

을 자동 실행합니다.

태그 예:
- 안정판: `v0.16.0`, `v0.16.1`, `v0.17.0`
- 테스트판: `v0.16.1-beta1`, `v0.17.0-rc1`

## 5. 승재님 폰의 1회 전환

기존 v0.15 이하가 GitHub 임시 Debug 키로 설치되어 있다면 새 고정 서명 APK가 그 위에 설치되지 않을 수 있습니다.

따라서 **v0.16.0에서 한 번만 기존 앱 제거 → 고정 서명 v0.16.0 설치**가 필요할 수 있습니다.

현재 중요한 FIT/로그 ZIP은 먼저 외부에 보관하세요. 그 뒤 버전부터는 앱 내부 업데이트로 데이터를 유지한 채 교체됩니다.

## 6. 지인에게 배포

지인은 GitHub Release의 최신 안정판 APK를 **처음 한 번만 수동 설치**합니다.
그 뒤에는 앱 설정의 `최신 업데이트 확인` 또는 하루 1회 자동 확인으로 업데이트할 수 있습니다.

기본 채널은 `안정판`입니다. `테스트판(Beta/RC)도 업데이트 대상에 포함`은 시험 사용자가 직접 켜야 합니다.

## 보안/개인정보 원칙

- 각 사용자의 GPX/FIT/위치/배터리/학습 데이터는 각 휴대폰 로컬에 저장됩니다.
- 업데이트 확인은 GitHub의 공개 Release 메타데이터만 읽습니다.
- 주행 데이터는 업데이트 서버/GitHub로 전송하지 않습니다.
- 업데이트 APK는 Android 패키지 서명 검증을 통과해야 기존 앱 위에 설치됩니다.
- GitHub가 Release asset의 SHA-256 digest를 제공하면 앱이 다운로드 후 해시도 추가 검증합니다.
