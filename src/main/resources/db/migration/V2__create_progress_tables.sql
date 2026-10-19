-- 계정의 게임 진행 상태(Gold, 블랙홀 성장도). 계정당 한 행이고, 계정 생성 때 함께 만든다.
-- account_id가 PK이면서 FK다(account와 1:1).
-- revision: 진행 상태를 바꿀 때마다 1 오른다. 저장 요청이 "내가 읽은 버전"을 보내 다른 저장을 덮어쓰지 않게 하는 데 쓴다.
CREATE TABLE player_progress (
    account_id   BIGINT      NOT NULL,
    gold         BIGINT      NOT NULL DEFAULT 0,
    growth_stage INT         NOT NULL DEFAULT 0,
    revision     BIGINT      NOT NULL DEFAULT 0,
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (account_id),
    CONSTRAINT fk_player_progress_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT ck_player_progress_gold CHECK (gold >= 0),
    CONSTRAINT ck_player_progress_growth_stage CHECK (growth_stage >= 0)
);

-- 계정이 산 노드와 그 Rank. 아직 사지 않은 노드는 행이 없다.
-- id가 자동 증가라 id 순서가 곧 "처음 산 순서"다.
-- node_id: 게임 콘텐츠의 노드 ID(예: timer-01). 서버에 노드 테이블이 생기기 전이라 FK 없이 문자열로 둔다.
-- (RANK는 MySQL 예약어라 컬럼 이름은 node_rank)
CREATE TABLE player_node_rank (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    account_id BIGINT      NOT NULL,
    node_id    VARCHAR(64) NOT NULL,
    node_rank  INT         NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_player_node_rank_account_node UNIQUE (account_id, node_id),
    CONSTRAINT fk_player_node_rank_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT ck_player_node_rank_rank CHECK (node_rank >= 1)
);

-- 이미 만들어진 계정에도 처음 상태의 진행 상태를 만든다.
INSERT INTO player_progress (account_id, created_at, updated_at)
SELECT id, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) FROM account;
