# TimeGate v0.34.80

- Fix timing regression test compilation after START+FINISH policy simplification.
- Replace the obsolete completion-policy test with the current rule:
  - explicit DNF remains DNF
  - measured START + FINISH is VALID
  - legacy REVIEW / INVALID with START + FINISH becomes VALID
  - CP / GPS quality do not invalidate a completed lap
- Use the same pure completion-policy function from RaceDataStore so production code and tests share one rule.
- If an older completed record has START + FINISH but elapsed_ms is zero, restore elapsed_ms from FINISH - START.
