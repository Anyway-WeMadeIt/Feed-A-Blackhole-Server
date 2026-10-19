package org.example.feedablackhole.auth.dto;

/**
 * 로그인·토큰 갱신 응답.
 *
 * @param accessToken  API 호출에 쓰는 짧은 수명의 JWT. Authorization: Bearer 헤더로 보낸다.
 * @param expiresIn    액세스 토큰이 몇 초 뒤 만료되는지. 클라이언트가 갱신 시점을 정하는 데 쓴다.
 * @param refreshToken 새 액세스 토큰을 받는 데 쓰는 긴 수명의 토큰. 한 번 쓰면 폐기되므로 응답의 새 값으로 교체해 저장해야 한다.
 */
public record TokenResponse(String accessToken, long expiresIn, String refreshToken) {
}
