package org.example.feedablackhole.sync.log;

/**
 * 로그 거부 코드의 전체 목록(docs/server-sync-contracts.md 3.6). 응답의 rejected.code에 이름 그대로 나간다.
 * 위의 셋은 엔진이, 나머지는 타입별 처리기가 낸다.
 */
public enum RejectCode {
    // ---- 엔진 ----
    SEQ_GAP,
    MALFORMED_LOG,
    CONTENT_VERSION_MISMATCH,

    // ---- NODE_PURCHASE ----
    UNKNOWN_NODE,
    MAX_RANK_REACHED,
    NODE_HIDDEN,
    NOT_ENOUGH_GOLD,
    RANK_MISMATCH,
    COST_MISMATCH,

    // ---- RUN_START ----
    DUPLICATE_RUN_ID,

    // ---- SETTLEMENT ----
    RUN_NOT_OPEN,
    SETTLEMENT_INVALID_KILLS,
    SETTLEMENT_EXP_MISMATCH,
    SETTLEMENT_GOLD_MISMATCH,
    SETTLEMENT_TIME_EXCEEDED,
    SETTLEMENT_RESULT_MISMATCH
}
