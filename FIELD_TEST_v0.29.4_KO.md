# v0.29.4 현장 테스트 — GitHub 422 tree 복구

## 수정
- 모바일 증분 배포에서 GitHub `HTTP 422 · Invalid tree info`가 발생하던 Git tree 생성 방식을 교체.
- 변경 파일 blob만 업로드하는 증분 방식은 유지.
- 삭제 파일을 `sha:null` 엔트리로 보내지 않고, 원격 leaf + 새 ZIP leaf를 합친 **최종 tree**를 생성.
- `.github/workflows` 업데이트 OFF 시 기존 workflow는 최종 tree에 그대로 보존.
- 변경 파일이 0개인 재시도도 기존 tree SHA로 새 커밋을 만들 수 있게 처리.

## 현장 확인
1. v0.29.4 ZIP 선택
2. workflows OFF 확인
3. `GITHUB MAIN에 백그라운드 배포`
4. 로그에서 `안전한 최종 Git tree 생성` 이후 push 완료 여부 확인
