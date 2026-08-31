# v0.30.3 validation

## 자동 배속
- 30배속/60배속 버튼 및 Activity 참조 제거
- 가장 늦은 참가자의 finishRaceSec / 50초로 자동 multiplier 계산
- multiplier 범위 1x~2400x 안전 제한
- 참가자 계획 재계산 및 재생 시작 시 multiplier 재계산
- 시뮬레이션 종료 조건은 기존과 동일하게 마지막 참가자의 finishRaceSec

## UI
- 수동 배속 컨트롤 없음
- `대회 경과 ... · 자동 N배속 · 약 XX초` 표시
