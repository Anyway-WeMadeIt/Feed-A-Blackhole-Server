package org.example.feedablackhole.auth.dto;

/**
 * 게스트 로그인 응답. 토큰 발급(4단계) 전까지의 임시 형태로, 인증된 계정의 ID만 돌려준다.
 */
public record GuestLoginResponse(long accountId) {
}
