package org.example.feedablackhole.auth.dto;

/**
 * 게스트 등록 응답. guestSecret 원본은 이 응답에서만 볼 수 있다(서버에는 해시만 남는다).
 */
public record GuestRegisterResponse(String guestId, String guestSecret) {
}
