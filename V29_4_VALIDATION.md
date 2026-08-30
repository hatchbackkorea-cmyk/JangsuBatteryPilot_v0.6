# v0.29.4 Validation

- 대상: 모바일 증분 GitHub 배포의 HTTP 422 `Invalid tree info`
- 변경 blob만 업로드하는 기존 속도 최적화 유지
- base_tree + sha:null 삭제 엔트리 방식을 제거
- 원격 최종 leaf 집합을 보존/갱신해 base_tree 없는 완전한 최종 tree 생성
- workflow OFF 시 원격 `.github/workflows/*` leaf 보존
- 변경 0개일 때 기존 tree SHA 재사용 후 커밋 가능
