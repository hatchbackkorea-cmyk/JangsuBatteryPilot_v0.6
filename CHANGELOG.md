# v0.34.36
- AVINOX SYSTEM에서 eMTB 주행·코스·설정·학습·피드백·배터리 센터를 바로 열 수 있도록 완전 통합했습니다.
- 모바일 소스 배포의 eMTB 페이지·구형 설정 바로가기·MainActivity/MtbHud 런타임 코드를 완전히 제거했습니다.
- eMTB 내부 페이지는 배포 페이지 제거 후 6개로 정리하고 직접 페이지 진입 인덱스를 안정화했습니다.
- 메뉴 기능 감사에서 기존 주행·코스·학습·피드백·배터리 기능 연결을 유지한 상태로 정리했음을 확인했습니다.

# v0.32.7 — PC Server Health + Sync Visibility
- 앱 첫 화면에서 Rider Control Center PC `/api/health`를 직접 확인해 서버 연결 상태와 버전을 표시.
- 새 PC entrypoint(`pc_entry=true`)는 정상 연결로, 예전 서버는 `구버전 · 업데이트 권장`으로 구분.
- 서버 응답이 없거나 설정되지 않았을 때 `앱 단독 사용 가능`을 명확히 표시.
- Rider Control Center 동기화 대기 건수를 첫 화면에 표시하고 자동동기화 완료 후 다시 갱신.
- 관리자폰 등록 성공 직후 서버 상태를 다시 확인.
- v0.32.6의 ROAD 공개 그룹방, 이름만으로 게스트 참가, 방 GPX 자동 적용, 일반 사용자 안정판 업데이트 기능 유지.
- PR마다 Android debug APK를 실제 빌드하는 CI 검증 추가.

# v0.32.6 — Granfondo Room Hub + Public Updater
- ROAD 그룹방 참가 허들을 낮췄습니다. Rider Server를 별도로 연결하지 않아도 이름만 입력하면 공개 그룹방에 게스트로 참가할 수 있습니다.
- ROAD 첫 화면에 현재 PC 서버 + 공개된 참가 가능 방을 모아 보여주는 그룹방 허브를 추가했습니다.
- 서버가 방 생성/갱신 시 `public_rooms.json`을 GitHub raw에 자동 게시해 외부 참가자가 행사장 밖에서도 방 목록을 찾을 수 있게 했습니다.
- 그룹방 참가 시 서버에 저장된 GPX 코스를 자동 다운로드/적용하고, 방 목표 시간/출발 시각/컷오프를 ROAD 계획에 바로 반영합니다.
- 그룹방 상태는 5초마다 자동 갱신되며 참가자 수, 공개/잠금 여부, 시작/종료 상태를 반영합니다.
- 일반 사용자도 설정 화면에서 최신 안정판 APK를 확인하고 직접 업데이트할 수 있도록 공개 업데이트 경로를 복원했습니다.
- 관리자 기능은 기존처럼 관리자폰 인증 후에만 열립니다.

# v0.32.5 — ROAD Group Ride Server
- ROAD 그란폰도 모드에 Rider Control Center 기반 그룹방 기능을 추가했습니다.
- 그룹방 생성 시 PC에서 방 이름, 이벤트 코드, 출발 시각, 목표 주행시간, 컷오프, 공개 여부를 설정할 수 있습니다.
- 앱에서 참가 가능한 그룹방 목록을 불러오고 참가/나가기, 참가자 수 확인이 가능합니다.
- 그룹방 참가자의 진행 거리, 속도, 예상 완주시간을 서버에 동기화합니다.
- PC 관리자 화면에서 방 생성·삭제·시작·종료와 참가자 상태를 관리할 수 있습니다.
- 그룹방 데이터는 PC 서버 재시작 후에도 유지됩니다.

# v0.32.3 — Android Release + Admin Mobile Pairing
- 고정 서명키 기반 signed Release APK 자동 배포를 복구했습니다.
- Android 앱에서 서버의 1회용 8자리 코드를 입력해 관리자폰으로 등록할 수 있습니다.
- 관리자폰은 이후 비밀번호 재입력 없이 Admin Center를 사용할 수 있습니다.
- 관리자폰 등록 코드 발급, 회수, 상태 조회 API를 Rider Control Center에 추가했습니다.
- 앱 업데이트/배포/실험 기능은 관리자폰에서만 사용할 수 있도록 제한했습니다.
- 일반 사용자는 앱 실행 및 주행 기능만 사용할 수 있습니다.

# v0.32.2 — Fixed Signing + Admin Hardening
- 앱 릴리스 APK를 고정 Android 서명키로 서명해 기존 설치본 위에 업데이트 설치가 가능하도록 했습니다.
- Release workflow가 서명키/비밀번호 누락 시 즉시 실패하도록 검증 단계를 추가했습니다.
- 관리자센터에서만 앱 업데이트/배포/실험 기능에 접근하도록 정리했습니다.
- 일반 설정에서 서버 연결 패널과 관리자 기능을 숨겼습니다.

# v0.32.1 — Rider Control Center
- PC용 Rider Control Center 서버를 추가했습니다.
- 라이더 프로필, FTP/Wkg, GPX, ROAD 페이스 계획, 주행 로그를 PC와 자동 동기화합니다.
- 오프라인일 때 변경사항을 폰에 큐잉하고 다음 연결 시 재전송합니다.
- 관리자센터에서 서버 URL, 라이더 정보, 자동동기화, 업데이트 채널을 관리합니다.

# v0.31.7 — Admin-only Update Flow
- 앱 자동 업데이트 팝업을 제거하고 관리자센터에서만 업데이트 확인/설치가 가능하도록 변경했습니다.
- 일반 설정의 Rider Server 연결 및 모바일 배포 버튼을 관리자센터로 이동했습니다.
- ReleaseUploaderActivity의 외부 직접 진입을 차단하고 관리자센터 내부에서만 열도록 변경했습니다.

# v0.31.6 — Admin Security
- Android에서 관리자 암호를 변경하면 PC 관리자 로그인 암호도 동시에 변경됩니다.
- 암호 변경 시 기존 PC 세션을 즉시 만료시킵니다.
- 관리자 비밀번호 정책과 재인증 흐름을 강화했습니다.

# v0.31.5 — Mobile Release Deployment
- 전체 소스 ZIP을 Android에서 GitHub main으로 업로드하고 signed Release 생성까지 이어지는 모바일 배포 기능을 추가했습니다.
- GitHub Fine-grained PAT을 Android Keystore로 암호화 저장합니다.
- 배포 중 앱이 백그라운드로 가도 foreground service가 작업을 계속합니다.

# v0.30.9 — AVINOX Context Learning v2
- Avinox 원본 상황기반 재해석 모델 v2를 적용했습니다.
- 실제 SOC, assist mode, Rider/Motor Power, torque, cadence, gear 데이터를 상황별 학습에 분리 반영합니다.
- 기존 원본 데이터가 있으면 앱 시작 시 새 상황모델로 자동 재해석합니다.

# v0.30.8 — AVINOX Learning Integrity
- Avinox `.proto` 원본을 A+ 학습 소스로 우선 사용합니다.
- FIT 단독 데이터는 B급 보조학습으로 분리합니다.
- 테스트 주행과 불확실 모드 구간은 개인 학습에서 제외합니다.

# v0.30.7 — Battery Copilot Refinement
- 계획주행과 임의주행을 분리했습니다.
- 임의주행은 GPX 없이 GPS·고도·속도·실제 배터리를 기록할 수 있습니다.
- 주행 종료 후 FIT/Avinox 소비량을 피드백 페이지에서 연결할 수 있습니다.

# v0.30.6 — Charge Session Guidance
- 충전 시작/완료 기록과 충전 목표 도달 알림을 추가했습니다.
- 계획주행에서는 충전소별 계획 %를 기준으로 알림합니다.
- 임의주행에서는 기본 충전 목표를 사용합니다.

# v0.30.5 — Adaptive Battery Plan
- 실제 배터리 입력과 계획 오차를 반영하는 적응형 배터리 계획을 추가했습니다.
- 다음 충전소/종점 도착 예상 잔량과 필요한 충전량을 재계산합니다.

# v0.30.4 — Ride HUD Polish
- 주행 HUD의 배터리/거리/위험 상태 가독성을 개선했습니다.
- 지도 미리보기와 다음 포인트 안내를 정리했습니다.

# v0.30.3 — Rider / Motor Analysis
- Rider Power와 Motor Power를 분리해 사람의 운동능력과 모터 기여도를 별도로 분석합니다.
- 파워커브와 W/kg 요약을 추가했습니다.

# v0.29.7 — Avinox Original Sync
- Shizuku를 통해 Avinox 앱의 `cloud_ride_rec_*.proto` 원본을 자동 수집할 수 있도록 했습니다.
- 원본과 FIT를 별도 등급으로 관리합니다.

# v0.29.1 — HUD Simplification
- 주행 화면에서 보조 안내값을 줄이고 핵심 배터리/거리/위험 정보 중심으로 정리했습니다.

# v0.29.0 — Battery Learning
- 실제 주행 구간을 기반으로 개인 배터리 소비 학습 모델을 추가했습니다.
- 코스/모드별 예측 보정을 지원합니다.

# v0.28.9 — Ride Log Archive
- 주행 GPS, 배터리, 이벤트를 GPX/CSV/JSON/ZIP으로 저장합니다.
- 주행 종료 후 학습 사용 여부를 선택할 수 있습니다.

# v0.28.6 — Charging Stations
- 코스 충전소와 충전 계획을 관리합니다.
- 다음 충전소 도달 가능성과 필요한 충전량을 계산합니다.

# v0.28.5 — Course ETA
- 코스 포인트별 ETA 계산을 개선했습니다.

# v0.28.4 — Assist Mode Learning
- ECO/AUTO/TRAIL/TURBO 모드별 배터리 모델을 분리했습니다.

# v0.28.2 — GPX Course Tools
- GPX 코스 가져오기/저장/활성화 기능을 정리했습니다.

# v0.28.1 — Rain Touch Lock
- 우천 시 터치 오작동을 막기 위한 터치 잠금 기능을 추가했습니다.
- 잠금 상태에서는 볼륨 버튼으로 페이지 이동이 가능합니다.

# v0.27.9 — Ride UI
- 주행 화면의 페이지 전환과 상태 표시를 개선했습니다.

# v0.27.8 — Background Mobile Deploy
- 모바일 소스 배포가 화면 잠금/백그라운드에서도 계속되도록 foreground service를 추가했습니다.

# v0.27.7 — Release Flow
- 앱 내부 업데이트와 릴리스 다운로드 경로를 개선했습니다.

# v0.27.6 — Settings Cleanup
- 설정/학습/진단 메뉴를 정리했습니다.

# v0.27.5 — BLE Diagnostics
- Avinox BLE 서비스/특성 탐색 및 진단 도구를 추가했습니다.

# v0.27.3 — Voice Guidance
- 거리/시간 기반 자동 음성 안내를 추가했습니다.

# v0.27.2 — Screen Controls
- 주행 화면 항상 켜기 옵션을 추가했습니다.

# v0.27.1 — Update Manager
- 앱 내부에서 GitHub Release를 확인하고 APK 업데이트를 설치할 수 있도록 했습니다.

# v0.27.0 — Initial Ride Copilot Foundation
- GPX 기반 배터리 예측, 주행 기록, 설정 화면의 초기 통합 구조를 만들었습니다.
