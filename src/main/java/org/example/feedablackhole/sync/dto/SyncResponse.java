package org.example.feedablackhole.sync.dto;

/**
 * POST /api/v1/me/sync의 응답(docs/server-sync-contracts.md 3.7). 거부가 있어도 200이고 진행도가 항상 담긴다.
 *
 * @param appliedThroughSeq 처리 후 lastAppliedSeq. 이 값 이하의 로그는 모두 반영되었다
 * @param rejected          거부된 로그. 없으면 null
 * @param contentVersion    서버 콘텐츠 묶음의 버전
 * @param progress          응답 시점의 서버 진행도
 */
public record SyncResponse(
        long appliedThroughSeq,
        RejectedLogResponse rejected,
        String contentVersion,
        SyncProgressResponse progress) {
}
