package org.example.feedablackhole.auth.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.feedablackhole.auth.dto.RefreshTokenRequest;
import org.example.feedablackhole.auth.dto.TokenResponse;
import org.example.feedablackhole.auth.service.TokenService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인 수단과 무관한 토큰 API(갱신, 로그아웃).
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final TokenService tokenService;

    /**
     * 유효한 리프레시 토큰으로 새 토큰 쌍을 발급받는다.
     * 기존의 리프레시 토큰은 폐기된다.(회전)
     */
    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return tokenService.refresh(request.refreshToken());
    }

    /**
     * 리프레시 토큰을 폐기한다.
     * JWT는 서버가 기억하지 않으므로 명시적으로 토큰을 폐기할 수 없다.
     *      JWT가 유효한 동안은 계속 요청을 보낼 수 있다. 즉시 재로그인할 것을 강제하지 못한다.
     *      대신, JWT 수명은 짧게 둔다.
     * 이미 만료/폐기된 토큰으로 시도해도 결과가 동일하다.(멱등)
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshTokenRequest request) {
        tokenService.logout(request.refreshToken());
    }

}
