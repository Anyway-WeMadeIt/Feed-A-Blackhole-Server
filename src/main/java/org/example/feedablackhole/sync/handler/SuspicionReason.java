package org.example.feedablackhole.sync.handler;

/**
 * RUN_START가 서버 계산과 다를 때 남기는 의심 사유(docs/server-sync-contracts.md 4.4). 거부하지 않고 기록만 한다.
 */
public enum SuspicionReason {
    START_GROWTH_STAGE_MISMATCH,
    START_GOLD_MISMATCH,
    START_LEVEL_MISMATCH,
    /** |클라이언트 − 서버| > 0.001초. */
    TIME_LIMIT_MISMATCH,
    /** 종류·수·순서 중 하나라도 다름. */
    START_SUPPLY_MISMATCH
}
