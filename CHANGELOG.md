# v0.19.2 — Strava Clean FIT Build Fix
- `StravaActivity.kt` Assist ratio 계산에서 nullable 평균 파워를 직접 나누던 Kotlin 컴파일 오류 수정.
- Rider/Motor 평균 파워를 로컬 non-null 값으로 확정한 뒤 Assist ratio를 계산하도록 변경.
- v0.19.1의 Strava 클린 FIT 기능/필드 구성은 그대로 유지.

# v0.19.1 — Strava Clean FIT Full Telemetry
- Avinox 원본 FIT의 GPS/시간/거리/속도/고도/심박/케이던스/Rider Power/Motor Power를 읽어 Strava용 새 FIT으로 재구성.
- 표준 FIT `power`에는 반드시 사람이 낸 Rider Power만 기록하고 Motor Power는 e-bike 전용 `motor_power` 필드로 분리.
- 같은 주행의 Jangsu 앱 로그를 시간 우선으로 자동 매칭해 Avinox FIT에 빠진 실제 BLE Battery SOC와 선택 Assist Mode(ECO/AUTO/TRAIL/TURBO)를 합성.
- FIT 표준/e-bike 필드에 Heart Rate, Cadence, Motor Power, Battery SOC, e-bike Battery Level, Assist Mode를 가능한 범위에서 모두 기록. 없는 센서값은 추정해서 만들지 않음.
- Strava 업로드 전 미리보기에서 Rider/HR/Cadence/Motor/Battery/Mode와 각 필드 기록률을 확인 가능.
- `클린 FIT → STRAVA 업로드`를 기본 경로로 추가하고 `원본 FIT 직접 업로드 (비교)`를 A/B 검증용으로 유지.
- Strava 활동 설명에 Rider 에너지, Motor Wh, Assist ratio, Battery 변화, Mode 사용비율을 자동 작성.
- Client ID 274909 사용. Client Secret/Access Token/Refresh Token은 소스나 채팅에 넣지 않고 사용자 휴대폰의 Android Keystore 암호화 저장소에서만 관리.

# v0.18.5 — Selected Mode Direct Detect + Settings Compact
- 2026-08-27 실내 반복 전환 로그를 기준으로 FFF4 long packet byte[68]을 선택 모드로 직접 매핑: 1=ECO, 2=TRAIL, 3=TURBO, 4=AUTO.
- v0.18.4의 AUTO sticky 해석을 제거해 AUTO 뒤 실제 TRAIL/TURBO 전환이 `AUTO · TRAIL급/TURBO급`으로 잘못 남는 문제 수정.
- AUTO 내부 유효 어시스트 단계는 같은 byte[68]로 추정하지 않고, 별도 BLE 필드가 검증될 때까지 표시/학습에 사용하지 않음.
- 선택 모드 변경이 HIGH 신뢰도로 즉시 기록되어 모드별 클린 학습과 에너지 모델 전환에 반영.
- 설정(3페이지) 최상단 음성안내/화면유지/안내주기 박스를 현재 대비 약 25% 추가 압축.
- 설정(3페이지) 두 번째 테스트 모드 박스도 현재 대비 약 25% 압축해 스크롤을 더 줄임.

# v0.18.4 — One-Screen Polish + AUTO Effective Assist
- 코스/GPX 페이지의 최하단 고도 프로필 박스를 약 20% 축소(220dp → 176dp)해 2페이지 스크롤 최소화.
- 설정 페이지 첫 설정 박스를 약 30% 압축: 스위치/SeekBar/패딩/간격을 줄여 3페이지를 한 화면에 가깝게 정리.
- Avinox BLE 실험실을 진단 핵심만 남긴 컴팩트 레이아웃으로 재구성: 장치 목록은 고정 높이 내부 스크롤, 배터리 입력+대조 한 줄, raw 미리보기 4줄, 로그 저장 한 줄.
- BLE 실험실에도 시스템 상태바/하단 내비게이션 inset을 적용해 화면 겹침 방지.
- AUTO가 raw 2/3 동적 상태를 보일 때 `AUTO · TRAIL급` / `AUTO · TURBO급`으로 표시해 선택 모드와 실제 유효 보조단계를 동시에 표현.
- ECO/TRAIL/TURBO 일반 모드는 기존 단일 모드 표시 유지. AUTO의 ECO급 구분은 아직 BLE 근거가 부족해 성급히 확정하지 않고 추가 로그로 검증.

# v0.18.3
- 주행 첫 화면을 한 화면 중심 HUD로 압축: 10km 상승/하강과 10km 후 배터리를 현재 거리·배터리 카드로 통합.
- 에너지 페이스/부족/구릉·가감속/FIT 목표 안내를 위험 판단 카드 오른쪽의 `주행 어시스트` 카드로 이동.
- 첫 화면 하단 `현재 상태 음성 안내` 버튼 제거. 음성 자동 안내 설정 자체는 유지.
- Android 15/targetSdk 35 edge-to-edge 대응: 상태바/내비게이션바 inset을 루트에 반영하여 상단 시계·상태영역 및 하단 시스템 버튼과 겹치지 않도록 수정.
- 각 ViewFlipper 페이지를 상단 기준으로 정렬하고 하단 안전 여백을 추가.
- 주행 화면 카드/폰트/버튼 높이를 소폭 압축해 스크롤 의존도를 줄임.

# v0.18.2 — BLE HUD + Avinox Mode Auto-Detect Validation

- 주행 상단 큰 `배터리` 숫자를 Avinox BLE 실제 SOC의 단일 대표값으로 변경; 계획 SOC는 하단 보조문구로 분리
- 별도 `AVINOX 실제 배터리` 카드를 숨기고 배터리 판단 카드를 전체 폭으로 정리
- BLE가 끊긴 동안에만 상단 배터리 옆 비상 `수동` 입력 버튼 표시
- Eco / Auto / Trail / Turbo 4개 큰 수동 모드 버튼을 주행 화면에서 숨김
- FFF4 long packet byte[68] 기반 Avinox 모드 자동감지 검증기 추가
- 현장 검증상 1=Eco, 4=Auto 강한 후보; 2/3은 Auto 동적상태와 Trail/Turbo가 겹칠 수 있어 AMBIGUOUS로 명시
- 애매한 후보에서만 `맞음 / 다름` 검증 UI를 노출해 매 모드 변경마다 4개 버튼을 누를 필요를 없앰
- 사용자 확인값은 CONFIRMED, 강한 후보는 HIGH, 애매한 후보는 AMBIGUOUS로 로그에 출처/신뢰도 저장
- AMBIGUOUS 구간은 모드별 개인학습에서 자동 제외해 잘못된 AUTO/TRAIL/TURBO 분류가 학습모델을 오염시키지 않도록 보호
- 계획주행 학습도 기존 통합 학습 대신 검증된 모드별 클린 구간만 사용하도록 변경; 모드 로그/클린 구간이 없으면 학습 차단
- `assist_auto_detect.csv`를 추가해 주행 내내 감지 후보/신뢰도/raw code/FFF4 long packet을 보존, selected_mode 필드 추가 역추적 가능
- HIGH/CONFIRMED 모드가 바뀌면 해당 모드의 학습값으로 계획/페이싱 모델을 즉시 다시 계산

# v0.18.1 — Clean Mode Learning + GPS Ascent Filter

- 임의주행 FIT 학습을 Eco / Auto / Trail / Turbo 모드별로 완전 분리
- 한 SOC 하락 구간 안에 모드 전환이 끼면 해당 구간은 학습에서 제외해 모드 혼합 오염 방지
- 동일 모드라도 assist_profile_id를 함께 저장해 프로필 변경 이력 보존
- 예측 시 현재 선택 모드의 학습값을 우선 사용하고, 다른 모드 학습값은 서로 섞지 않음
- 현재 프로필과 정확히 일치하는 학습값을 우선 사용하고, 프로필이 달라진 같은 모드 데이터도 섞지 않음; 예전 모드미기록 데이터만 안전 fallback
- 완충 직후 BMS 100% plateau 영향 때문에 100→99 및 99→98 SOC 구간은 개인 학습에서 제외
- 휴대폰 GPS 고도 누적 상승 버그 수정: 최근 21개 중앙값 + 4m 히스테리시스 + GPS 정확도 필터 적용
- 오늘 평지 클린 로그 재현 검증에서 기존 약 1,520m 표시가 새 필터 기준 약 39m 수준으로 감소(Avinox FIT 약 37m와 근접)
- 모드별 gpsAscentM 통계에도 같은 필터를 적용해 짧은 고도 흔들림 누적 방지
- 원본 gps_ele_m 값은 CSV/GPX에 그대로 보존하여 사후 분석 가능
- 모드 선택 시 배터리/충전/페이싱 모델을 즉시 다시 만들어 현재 모드 학습값을 화면 계획에 반영
- 모드 로그가 없는 이전 임의주행은 새 개인 학습에 자동 사용하지 않고 검증/참고 데이터로만 보관

# v0.18.0 — Adaptive Energy Trip Planner

- GPX 등록 충전지점을 그대로 사용하면서 `앱 권장 충전량`과 `사용자 목표 충전량`을 분리
- 앱 권장값은 GPX·개인 학습·현재 실측 소비계수로 재계산하지만 사용자 목표값을 자동으로 덮어쓰지 않음
- 다음 충전지점 도착 예상 SOC와 그 이후 구간 소비를 이용해 출발 권장 SOC 계산
- 사용자 목표가 앱 권장보다 낮으면 경고만 하고 최종 출발 판단은 사용자에게 유지
- 100% 충전으로도 다음 구간 안전목표를 못 맞추는 경우 추가 충전지점/절약 모드 검토 경고
- Avinox 800Wh 현장 기준 충전곡선 적용: 0→80% 약 90분, 80→100% 추가 약 60분
- 충전 전 도착 예상 SOC 기준으로 앱 권장/사용자 목표까지의 예상 충전시간 표시
- 실제 충전 중 BLE SOC를 이용해 권장/사용자 목표까지 남은 충전시간을 실시간 갱신
- 충전 완료 시 실제 충전시간을 주행 이벤트 로그에 보존
- 주행 중 실제 SOC 소비가 계획과 달라지면 기존 AdaptiveBatteryPlan 소비계수로 다음 충전 권장량을 동적 보정
- 완충 직후 100→99% 표시가 비선형일 수 있어 100% 출발 시 98% 도달 전에는 소비계수 보정을 시작하지 않도록 보호
- Eco / Auto / Trail / Turbo 4모드만 유지; MIN/BOOST는 주행 UI/프로필/통계 대상에서 제외
- 실시간 rider/motor power는 아직 BLE 필드가 검증되지 않았으므로 예측에 거짓 적용하지 않고 FIT 사후학습만 유지

# v0.17.2

- 사용자 커스텀 MIN 모드를 주행 UI/프로필/통계 대상에서 제거
- 현장 테스트 모드를 Eco / Auto / Trail / Turbo 4개로 단순화
- 기존 저장값이 MIN이더라도 새 버전에서는 Eco 기본값으로 안전하게 복귀
- BOOST 제거 정책(v0.17.1)은 그대로 유지

# v0.17.1

- BOOST를 지속 주행 모드 학습/통계 대상에서 제거
- 주행 화면 BOOST 버튼 제거
- BOOST 프로필 설정값 및 BOOST 전용 로그 라벨 제거
- MIN / Eco / Auto / Trail / Turbo만 모드 프로필 대상으로 유지
- BOOST 사용 중 소비도 전체 SOC 감소에는 포함되지만 별도 모드 학습에는 사용하지 않음

# Changelog

## v0.17.0 — Avinox Assist Profile Field Validation
- MIN / Eco / Auto / Trail / Turbo / BOOST 6개 모드를 주행 화면에서 직접 표식 가능
- 자전거에서 모드를 바꾼 직후 앱의 같은 모드 버튼을 누르면 시간·거리·GPS 고도·BLE SOC와 함께 모드 전환 이벤트 저장
- 모드별 커스텀 설정값을 별도 프로필로 저장: 어시스트 최소/최대, 최대토크, 최대파워, 오버런, 스타트 어시스트, 연속 어시스트, BOOST 활성/지속시간/로직강화
- 제공된 2026-08-26 Avinox 화면을 사진 기준 초기 프로필로 등록; 숫자가 표시되지 않는 3개 반응 슬라이더는 0~4 상대 위치로만 기록
- 프로필 내용이 달라지면 결정론적 profileId가 달라져 같은 Trail이라도 다른 세팅의 주행 데이터가 섞이지 않도록 분리
- track.csv에 assist_mode / assist_profile_id 추가
- 모드 선택 직후 12초 동안 FFF4 BLE 원시 Notify를 raw_ble_probe.csv에 저장해 Avinox 내부 모드 필드 역추적 가능
- assist_profiles.jsonl에 실제 주행에 사용된 프로필 스냅샷 저장
- 종료 JSON에 assistModeStats 추가: 모드/프로필별 사용시간·거리·GPS 상승·검증된 SOC 하락량·800Wh 환산 에너지 저장
- SOC 소비량은 모드 전환이 없었던 연속 BLE SOC 하락만 해당 모드에 귀속해 혼합 구간 오염 방지
- 모드 데이터는 현장 검증용이며 개인 배터리 학습에는 자동 반영하지 않음

## v0.16.3 — Avinox BLE SOC 실주행 통합
- 66%→0x42, 65%→0x41, 64%→0x40 3회 독립 검증 결과를 바탕으로 FFF0/FFF4 Notify의 SOC 필드를 실주행 자동 입력으로 승격
- 계획주행/임의주행 시작 시 RideService가 Avinox를 자동 검색·연결·재연결
- SOC는 동일값 2회 연속 수신 후 확정하며 1% 변화마다 실제 배터리 관측값으로 자동 저장
- 실제 배터리 기록에 source(BLE_AVINOX/MANUAL/CHARGE/IMPORTED)를 추가해 학습 데이터 출처를 감사 가능하게 보존
- 충전 세션 중 BLE 상승값은 RIDING 관측으로 저장하지 않아 충전량과 소비 구간이 섞이지 않도록 보호
- 충전 세션 없이 SOC가 상승하면 학습 오염 방지를 위해 RIDING 저장을 차단하고 이벤트 로그에 기록
- 주행 화면의 마이크 배터리 입력 버튼 제거, 실제 배터리 패널을 'AVINOX 실제 배터리' + BLE 연결 상태 중심으로 재설계
- BLE 장애 시에만 쓰는 작은 '수동' 비상 입력 버튼 유지
- 충전 시작/완료 입력창은 최근 Avinox BLE SOC가 있으면 자동으로 채움

# v0.16.2

- 실제 메인 3번째 설정 페이지에 `Avinox BLE 진단 시작` 버튼을 연결했습니다.
- 실제 메인 설정 페이지에 업데이트 채널 선택과 `최신 업데이트 확인` 버튼을 추가했습니다.
- v0.16.1에서 사용되지 않는 별도 SettingsActivity에만 BLE/업데이트 메뉴가 있던 연결 오류를 수정했습니다.

# v0.16.1

- BLE Lab GitHub CI compile fix.
- Avinox-name prioritization helper corrected to Boolean semantics.
- BLE transport constant corrected to BluetoothDevice.TRANSPORT_LE.
- Candidate score calculation rewritten to avoid Kotlin sumOf overload ambiguity.
- No change to BLE diagnostic data isolation: BLE values remain excluded from battery learning.


## v0.16.0 — 배포/업데이트 기준판 + Avinox BLE Lab
- 설정에 `Avinox BLE 실험실` 추가: 주변 BLE 기기 검색, GATT 서비스/Characteristic 열람, READ/NOTIFY/INDICATE 원시값 수집
- Avinox/AMFLOW/DJI로 보이는 장치를 목록 상단에 우선 표시하되 이름이 숨겨진 장치도 놓치지 않도록 전체 BLE 주변기기 표시
- Avinox 앱/계기판의 현재 배터리 %를 입력하면 raw byte, uint16, 0~255 스케일, /100 스케일 및 표준 Battery Level UUID를 자동 대조해 SOC 후보 점수화
- 광고 패킷의 manufacturer/service data도 함께 기록해 GATT 연결이 막혀도 배터리 후보 탐색 가능
- 읽힌 BLE 데이터는 **실험 전용**이며 BatteryPlan/개인학습/실시간 배터리 보정에 절대 반영하지 않음
- 진단 세션을 텍스트 파일로 내보내 다른 배터리 % 시점의 값 변화 비교 가능
- 설정 페이지에 `최신 업데이트 확인` 추가
- GitHub Releases의 새 APK를 앱 안에서 확인 → 다운로드 → Android 설치 화면으로 연결
- 하루 1회 자동 확인: 새 버전이 있을 때만 알림
- 기본 `안정판` 채널, 선택 시 Beta/RC 테스트판도 확인
- 업데이트 확인 과정에서 GPX/FIT/위치/배터리/학습 데이터는 전송하지 않음
- GitHub Release asset SHA-256 digest가 제공되면 다운로드 파일 추가 검증
- v0.16.0부터 고정 서명키를 GitHub Secrets로 관리하는 Release workflow 추가
- 지인 배포를 위한 `SIGNING_AND_RELEASE_SETUP_KO.md`, `DISTRIBUTION_GUIDE_KO.md` 추가
- 새 설치 사용자의 로컬 데이터/학습은 다른 사용자와 공유되지 않음

## 0.15.0 — Free Ride + Post-Ride Benchmark
- 주행 시작 시 `계획주행 / 임의주행` 선택 추가
- 임의주행은 업로드 GPX, RouteMatcher, 코스 이탈, 체크포인트, 종점 예측과 완전히 분리
- 임의주행 GPS 누적 거리·상승·속도 및 실제 배터리 음성 입력 기록
- 첫 실제 배터리 입력을 기준으로 충전량을 더해 실제 누적 소비량 계산
- 임의주행 종료 후 실제 GPS 트랙 GPX/CSV/JSON/ZIP 저장
- 최근 주행에 Avinox FIT 사후 연결 및 FIT 코스 기준 우리 모델의 학습 전 예상 총소비량 저장
- 최근 주행에 Avinox ECO/AUTO/TRAIL/TURBO 전체 예상 소비량 사후 입력
- 실제 누적 소비 / 우리 모델 학습 전 사후예측 / Avinox 선택 모드의 오차 비교
- Avinox 데이터는 학습에서 완전 제외
- FIT 연결 후 사용자가 선택할 때만 임의주행 실제 배터리 체크포인트를 개인 학습에 반영

# CHANGELOG

## 0.14.0 — Independent Avinox Benchmark + Live Energy Comparison
- 앱 첫 화면/첫 페이지를 `주행`으로 변경하고 실제 ViewFlipper 순서도 `주행 → 코스 → 설정 → 학습 → 피드백`으로 재배치
- Avinox ECO/AUTO/TRAIL/TURBO 데이터가 BatteryPlan 예측과 개인 학습에 들어가던 경로를 제거해 완전한 외부 benchmark로 분리
- Avinox 선택 모드의 의미를 `주행 계획에 적용`에서 `실시간 화면에서 비교할 모드`로 변경
- 주행 대시보드에 `누적 에너지 비교` 카드 추가: 실제 누적 소비 / 우리 실시간 보정 모델 / Avinox 선택 모드
- 실제 누적 소비량을 `100% + 누적 충전량 - 최신 실제 잔량`으로 계산해 중간 충전 후에도 전체 주행 소비량 비교 유지
- 자체 모델은 실제 배터리 입력으로 얻은 실시간 소비계수를 남은 코스에 적용해 종점 누적 소비량을 갱신
- Avinox 전체 소비량은 자체 GPX 소비곡선의 진행 비중을 사용해 현재 위치까지의 비교값으로 환산하되 자체 예측에는 역으로 영향을 주지 않음
- 주행 로그의 AVINOX_BENCHMARK 이벤트도 적용/가중치 대신 비교 모드와 `자체예측/개인학습 미적용`을 기록
- 100% 초과 Avinox 입력(예: 148%, 254%) 지원은 그대로 유지

## 0.13.1 — Avinox 100%+ Total Course Energy
- Avinox ECO / AUTO / TRAIL / TURBO 값을 `배터리 잔량`이 아니라 `배터리 1팩=100% 기준 전체 코스 누적 소비량`으로 명확히 정의
- 148%, 254%처럼 100%를 초과하는 Avinox 전체 코스 소비량 입력·저장·재로딩 지원
- AvinoxReferenceStore의 100% 상한 제거, 양의 유한값(0.1% 이상)은 그대로 보존
- BatteryPlan의 Avinox 혼합 전체 소비량 및 최종 계획 전체 소비량에 있던 100% 상한 제거
- 전체 누적 소비량이 100%를 넘더라도 충전소 사이의 실제 배터리 잔량은 기존처럼 0~100% 범위에서 계산하고, 충전 시 설정한 충전 목표 %에서 다음 구간을 다시 시작
- Avinox 외부 기준은 내부 GPX 소비곡선의 전체 스케일을 보정해 구간별 소비량으로 역산 적용
- 기존 안전장치인 Avinox 가중치 감소(개인 학습 누적 시 45%→8%)와 전체 스케일 0.70~1.35배 제한은 유지
- 입력창과 코스 화면 문구를 `전체 코스 예상 소비량`으로 변경하고 `254%=배터리 2.54팩 분량` 안내 추가

## 0.13.0 — Avinox Benchmark Fusion
- 코스별 DJI Avinox GPX 분석 예상 소비율을 ECO / AUTO / TRAIL / TURBO 4개 모드로 별도 저장
- Avinox 예상값은 실제 FIT/배터리 학습 샘플과 완전히 분리된 `외부 benchmark` 데이터로 관리
- 기본값은 `비교만`이며 사용자가 특정 모드를 선택한 경우에만 배터리 계획의 제한된 prior로 적용
- 외부 기준 가중치는 개인 실제 학습이 쌓일수록 자동 감소: 학습 0개 45% → 1~2개 30% → 3~5개 20% → 6~11개 12% → 12개 이상 8%
- Avinox 기준으로 인한 전체 소비 스케일 변화는 0.70~1.35배로 제한해 외부 예상값 하나가 모델을 과도하게 지배하지 않도록 보호
- 코스 화면에서 `내부 모델 예상 → 최종 계획 기준`, 적용 모드, Avinox 가중치를 함께 표시해 출처와 영향도를 투명하게 확인
- Avinox 모드별 예상값만 저장하고 예측에는 쓰지 않는 `비교만 · 예측에 미적용` 옵션 지원
- 실제 주행 중 입력한 배터리 보정은 기존 AdaptiveBatteryPlan에서 Avinox prior보다 우선해 실측 소비율로 계속 보정
- 충전소 자동 추천 및 충전 구간별 도착 예상에도 선택한 Avinox 외부 기준이 동일하게 반영
- 코스 삭제 시 해당 코스에 저장된 Avinox benchmark도 함께 삭제, 개인 학습 초기화 시에는 benchmark를 보존해 데이터 출처를 분리
- 주행 시작 시 적용/비적용된 Avinox 기준값, 가중치, 내부모델→최종계획 값을 `AVINOX_BENCHMARK` 이벤트로 주행 로그에 기록해 사후 재현 가능

## 0.12.0 — GPX Energy Pacing Assist
- 선택한 GPX의 앞으로 약 1km 지형을 과거 FIT 학습 프로필과 매칭해 실질적인 에너지 페이싱 목표 제시
- Avinox FIT의 모터 출력은 0W 코스팅 시간을 포함한 전체 평균과 별도로 `모터가 실제 작동한 시간의 평균 출력`을 계산해 목표 모터 W에 사용
- 케이던스 목표는 20rpm 미만 정지/코스팅 값을 제외한 실제 페달링 구간만 사용
- 학습 지형별 목표 모터 출력(W) · 케이던스(rpm) · 속도(km/h)를 범위로 표시
- 실시간 Avinox 파워/케이던스를 현재값으로 가장하지 않고 `FIT 학습 목표`로만 사용
- 주행 중 실시간 피드백은 휴대폰 GPS 속도와 실제 배터리 여유/부족을 사용해 속도 절약 조언 제공
- 배터리가 목표보다 부족하면 과거 학습 목표를 자동으로 보수적으로 낮추고, 여유가 있으면 정상 학습 페이스 유지
- 학습 샘플 수와 데이터 품질에 따라 목표 범위를 넓히고 신뢰도 낮음/보통/높음 표시
- 다운힐은 모터 0W 우선 구간으로 별도 안내하되 속도는 안전을 우선하고 고정 목표를 제시하지 않음
- 정기 음성 안내와 수동 현재상태/다음 업힐 안내에 학습 기반 에너지 페이스 목표 추가
- 기존 거리·배터리 예측 모델은 유지하고 페이싱 코치는 별도 레이어로 추가

## 0.11.2 — Multi-FIT Session Integrity
- Avinox 절전/전원 OFF로 한 실제 라이딩이 여러 FIT으로 나뉘는 경우 `FIT 여러 개 가져오기` 지원
- 선택한 FIT들을 기록 시작시각 기준으로 자동 정렬하고 파일 사이 시간 공백·GPS 위치차·기록 겹침 검사
- 파일 사이 휴식/전원 OFF 구간은 이동시간·평속·모터/라이더 에너지 적분에서 제외하고 별도 gap 메타데이터로 보존
- FIT 경계 첫 포인트를 SENSOR_GAP으로 표시해 서로 다른 FIT 사이를 연속 파워 데이터처럼 적분하지 않도록 방지
- 결합 코스의 거리·획득고도·손실고도·이동시간은 각 FIT의 검증된 구간 통계를 합산
- FIT 연결 위치가 5km 이상 떨어지면 서로 다른 라이딩 혼입 가능성으로 결합 차단
- 250m 이상 위치차, 30초 초과 기록 겹침, 6시간 초과 공백은 데이터 품질 점수에 감점 및 경고 표시
- 결합 세션 원본 FIT을 `original_01.fit`, `original_02.fit` 형태로 모두 보존
- `session_manifest.csv`, `session_gaps.csv` 추가 저장으로 향후 지도/그래프 피드백에서 파일 경계·휴식 구간 재구성 가능
- 학습 목록에 결합 FIT 개수와 휴식/전원 OFF 횟수 표시

## 0.11.1 — GPX Select + Swipe UX
- 장수280 고정처럼 보이던 흐름을 수정하고 메인 코스 페이지에 현재 GPX 선택 박스와 GPX 바로 불러오기 추가
- 새 GPX를 가져오면 자동으로 현재 코스로 선택하고 저장된 개인 학습 데이터를 적용해 BatteryPlan 재구성
- 주행 화면 핵심 카드 4개를 2열 2행으로 재배치해 세로 공간 절약 및 글자 크기 확대
- 스와이프 페이지를 코스 · 주행 · 설정 · 학습 · 피드백 5페이지로 분리
- 설정을 별도 메뉴 버튼이 아니라 2번째 우측 페이지에서 직접 조작하도록 변경
- 학습을 별도 설정 하위 메뉴가 아니라 3번째 우측 페이지로 분리하고 FIT/GPX 학습 바로가기 추가
- 설정 SeekBar 조작 중 가로 스와이프가 오작동하지 않도록 터치 충돌 방지

## 0.11.0 — Battery Model 2.0 + Data Integrity
- 장수280 사전 배터리 학습/고정 소비 계획을 예측에서 제거하고 중립 초기 모델로 전환
- v0.11.0 첫 실행에서 기존 개인/과거 학습 데이터만 1회 초기화
- 실제 주행 일반 배터리 입력: 직전 RIDING 입력 후 10초 이내 재입력 시 직전값 자동 무효화, 새 입력의 현재 km/시간으로 교체
- 충전 시작/완료 단일 버튼과 확인/취소 배터리 입력 추가
- 충전 도착/완료는 동일 코스 km에 고정하여 GPS 드리프트가 소비 구간에 섞이지 않도록 처리
- Avinox FIT의 GPS/거리/고도/속도/케이던스/라이더파워/모터파워 시계열 보존, 심박 제외
- 원본 FIT/GPX + telemetry.csv + battery_events.csv 보존으로 향후 피드백 재분석 기반 마련
- 모터 파워가 충분한 과거 FIT은 소비량의 공간 배분에 모터 출력 에너지 75% + 중립 지형 25%를 사용
- motor_power=0을 다운힐로 단정하지 않고 이동/지속시간/거리/고도하강을 함께 사용해 무동력 다운힐과 코스팅/정차/결측/이상치를 구분
- 분석 결과 화면은 거리/획득고도/손실고도/이동시간/평속만 표시
- 주행 화면 핵심 글자 확대, 카드 재배치, 화면 여유 시 세로 중앙 정렬
- 좌우 스와이프 4페이지: 지도/코스 · 주행 · 설정/정보 · 피드백
- 코스 메뉴 하단의 장수280 Stage1 기본 중복 카드를 제거하고 상단 현재 코스 박스 탭으로 전체 코스 선택

## 0.10.2 — Ride Stats Accuracy Fix
- FIT 분석은 Record 재계산보다 Session/Lap의 `total_distance`, `total_timer_time`, `total_ascent`, `total_descent`를 우선 사용
- FIT 고도는 `enhanced_altitude`를 우선 사용하고 Session 통계가 없을 때만 트랙 고도로 보완
- GPX 획득/손실고도 계산에서 1m 단위 필터를 제거해 완만한 상승이 누락되던 문제 수정
- GPX는 타임스탬프와 GPS 이동구간으로 이동시간을 계산하고 거리/이동시간으로 평속 계산
- 과거 라이딩 분석 화면에서 심박/케이던스/파워 표시 제거
- 분석 결과를 거리 / 획득고도 / 손실고도 / 이동시간 / 평속 중심으로 단순화
- 기존에 같은 FIT/GPX를 학습했어도 새 분석값으로 교체 학습 가능

## 0.10.1 — Historical Ride Build Fix
- `HistoricalRideActivity`의 중간 배터리 목록에서 TextView 지역 변수 `text`가 Button의 `text` 속성을 가린 컴파일 오류 수정
- 기능 변경 없이 v0.10.0의 과거 FIT/GPX 학습 기능 유지

## 0.10.0 — Historical Ride Learning
- 설정 메뉴에 `과거 라이딩 학습 가져오기 (FIT / GPX)` 추가
- FIT 파일 분석: GPS/거리/고도/시간/속도/심박/케이던스/파워(기록된 항목만)
- GPX 파일 분석: GPS/거리/고도/시간 및 확장 심박/케이던스/파워(있는 경우)
- 시작/종료 배터리 또는 총 사용 배터리 %를 입력해 과거 라이딩을 개인 배터리 학습에 반영
- 중간 배터리 지점 및 중간 충전 지점 입력 지원
- 같은 파일을 SHA-256으로 식별해 중복 학습 방지
- 학습된 과거 FIT/GPX 목록 확인 및 개별 학습 삭제
- 전체 개인 학습 초기화 시 과거 파일에서 만든 학습 기록도 함께 초기화
- FIT 디코딩에 Garmin 공식 FIT Java SDK 사용

## 0.9.2 — Learning Safety Patch
- 주행 로그 저장과 배터리 학습을 분리
- 주행 종료 후 학습 사용 여부를 직접 선택
- 테스트 모드 주행은 학습에서 자동 제외
- 설정 메뉴에 개인 배터리 학습 데이터 확인/초기화 추가

## 0.9.1 — Build Fix
- CourseActivity Kotlin 문자열 보간 컴파일 오류 수정

## 0.9.0 — GPX Charging Planner
- GPX 웨이포인트/주소/km/현재 위치 기반 충전소 계획
- 다음 충전소 우선 배터리 판단

## v0.19.0
- Strava 개인 계정 OAuth 연결(Client ID 274909) 추가
- Client Secret 및 OAuth 토큰을 Android Keystore 기반 AES-GCM으로 기기 내 암호화 저장
- Avinox 원본 FIT를 선택해 Rider/Motor 파워·에너지·케이던스 사전 분석
- Avinox 클라우드 자동연동을 우회해 원본 FIT를 Strava `EMountainBikeRide`로 직접 업로드
- Strava 활동 설명에 Rider/Motor 평균·최대 파워, Rider kJ, Motor Wh, Assist ratio 자동 작성
- 이번 단계는 원본 FIT 직접 업로드 비교용. 직접 업로드에서도 파워가 이상할 때만 다음 단계에서 새 FIT 생성/정제
