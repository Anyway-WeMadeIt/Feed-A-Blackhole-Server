package org.example.feedablackhole.sync.log;

/**
 * 동기화 로그의 종류(docs/server-sync-contracts.md 3.3). JSON의 type 글자와 이름이 같다.
 */
public enum LogType {
    RUN_START,
    SETTLEMENT,
    NODE_PURCHASE,
    RESET
}
