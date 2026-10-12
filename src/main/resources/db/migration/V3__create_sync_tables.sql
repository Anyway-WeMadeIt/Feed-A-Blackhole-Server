-- 동기화(로그 적용)를 위한 스키마. 계약: docs/server-sync-contracts.md 4.2.

-- 진행도: 마지막으로 반영한 로그의 seq, 열린 판, 의심 표시 수.
-- last_applied_seq: 이 값 이하의 seq는 이미 반영됐다. 다음 로그는 last_applied_seq + 1이어야 한다.
-- revision은 옛 저장 API를 없앨 때(W1-7) 함께 지운다.
ALTER TABLE player_progress
    ADD COLUMN last_applied_seq BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN open_run_id      BIGINT NULL,
    ADD COLUMN suspicious_count INT    NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_player_progress_last_applied_seq CHECK (last_applied_seq >= 0),
    ADD CONSTRAINT ck_player_progress_suspicious_count CHECK (suspicious_count >= 0);

-- 판 하나. RUN_START로 열고, SETTLEMENT로 결산하거나 다음 RUN_START·RESET이 포기로 닫는다.
-- start_growth_stage, start_gold, settled_gold, next_growth_stage는 서버 진행도와 서버 계산 기준이다(클라이언트가 보낸 값이 아님).
-- run_id는 계정 안에서 유일하다(클라이언트가 만든 UUID).
-- suspicious_reasons: RUN_START가 서버 계산과 다를 때의 사유 코드(쉼표로 구분). 없으면 NULL.
CREATE TABLE battle_run (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    account_id         BIGINT       NOT NULL,
    run_id             CHAR(36)     NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    seed               INT          NOT NULL,
    start_growth_stage INT          NOT NULL,
    start_gold         BIGINT       NOT NULL,
    started_seq        BIGINT       NOT NULL,
    ended_seq          BIGINT       NULL,
    settled_gold       BIGINT       NULL,
    next_growth_stage  INT          NULL,
    suspicious_reasons VARCHAR(255) NULL,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_battle_run_account_run UNIQUE (account_id, run_id),
    CONSTRAINT fk_battle_run_account FOREIGN KEY (account_id) REFERENCES account (id)
);

ALTER TABLE player_progress
    ADD CONSTRAINT fk_player_progress_open_run FOREIGN KEY (open_run_id) REFERENCES battle_run (id);

-- 받은 로그(감사·진단). 같은 seq가 여러 번 올 수 있다(재전송, 거부 뒤 재사용)라 seq에 유니크를 걸지 않는다.
-- 적용 여부의 기준은 잠금 아래의 player_progress.last_applied_seq다.
-- type, content_version, build_version: 받은 글자. 읽지 못했거나 길이를 넘으면 ''.
-- result: APPLIED, REJECTED, SKIPPED(이미 반영됨), NOT_PROCESSED(거부된 로그 뒤라 처리하지 않음).
CREATE TABLE sync_log (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    account_id      BIGINT      NOT NULL,
    seq             BIGINT      NOT NULL,
    type            VARCHAR(32) NOT NULL,
    result          VARCHAR(16) NOT NULL,
    reject_code     VARCHAR(40) NULL,
    content_version VARCHAR(64) NOT NULL,
    build_version   VARCHAR(32) NOT NULL,
    raw_json        JSON        NOT NULL,
    received_at     DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX ix_sync_log_account_seq (account_id, seq),
    CONSTRAINT fk_sync_log_account FOREIGN KEY (account_id) REFERENCES account (id)
);
