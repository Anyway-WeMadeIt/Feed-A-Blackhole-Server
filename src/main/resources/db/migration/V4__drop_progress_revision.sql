-- 옛 저장 API(PUT /me/progress, POST /me/progress/reset)를 없앴으므로(W1-7) revision 열을 지운다.
-- 낙관적 버전 검사는 last_applied_seq(V3)가 대신한다. 계약: docs/server-sync-contracts.md 4.2, C21.
ALTER TABLE player_progress DROP COLUMN revision;
