package org.example.feedablackhole.sync.entity;

/**
 * 받은 로그 하나의 처리 결과.
 */
public enum SyncLogResult {
    /** 검사를 통과해 반영했다. */
    APPLIED,
    /** 거부했다. 이 로그에서 처리를 멈췄다. */
    REJECTED,
    /** 이미 반영된 seq라 건너뛰었다(재전송). */
    SKIPPED,
    /** 앞의 로그가 거부되어 처리하지 않았다. */
    NOT_PROCESSED
}
