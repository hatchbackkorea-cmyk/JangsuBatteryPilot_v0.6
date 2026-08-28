# v0.26.9 validation

- VERSION.txt = 0.26.9
- 공통 `EmergencyCandidateDialog` 추가
- AlertDialog `setMessage()` + `setItems()` 동시 사용 제거
- 시뮬레이터 후보 선택 UI가 명시적 버튼 목록으로 표시됨
- 실제 주행 긴급충전 후보 선택 UI도 같은 공통 UI 사용
- 시뮬레이터 상태: NONE / OUTBOUND / CHARGING / RETURN / COMPLETE
- RETURN 완료 시 후보/앵커를 즉시 삭제하지 않고 COMPLETE 화면을 유지
- COMPLETE 화면에서 복귀 SOC/시각 및 남은 GPX 시간축 재계산 결과 확인 가능
- 시뮬레이터의 저장 격리 정책 유지
