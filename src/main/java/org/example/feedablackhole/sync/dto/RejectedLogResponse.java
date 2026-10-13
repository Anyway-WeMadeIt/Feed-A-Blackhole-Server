package org.example.feedablackhole.sync.dto;

/**
 * 거부된 로그 하나(docs/server-sync-contracts.md 3.7).
 *
 * @param type    받은 type 글자. 형식 오류로 알 수 없으면 ""
 * @param code    RejectCode 이름
 * @param message 사람이 읽는 설명(디버깅용)
 */
public record RejectedLogResponse(long seq, String type, String code, String message) {
}
