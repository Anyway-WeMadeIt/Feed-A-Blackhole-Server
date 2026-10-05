-- 전투 한 판의 요약. 받은 JSON 원문을 그대로 두고, 조회에 쓰는 값만 칼럼으로 뺀다.
-- 같은 battle_id는 한 번만 저장한다(재전송은 서버가 200으로 답한다).
CREATE TABLE battle_summary (
                                id              BIGINT       NOT NULL AUTO_INCREMENT,
                                battle_id       CHAR(36)     NOT NULL,
                                install_id      CHAR(36)     NOT NULL,
                                schema_version  INT          NOT NULL,
                                build_version   VARCHAR(32)  NOT NULL,
                                content_version VARCHAR(64)  NOT NULL,
                                battle_index    INT          NOT NULL,
                                started_at      DATETIME(6)  NOT NULL,
                                received_at     DATETIME(6)  NOT NULL,
                                raw_json        JSON         NOT NULL,
                                PRIMARY KEY (id),
                                CONSTRAINT uk_battle_summary_battle_id UNIQUE (battle_id)
);
