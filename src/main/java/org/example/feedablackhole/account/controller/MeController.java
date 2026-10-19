package org.example.feedablackhole.account.controller;

import org.example.feedablackhole.account.dto.MeResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 액세스 토큰으로 인증된 사용자 자신에 대한 API.
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    /**
     * 토큰이 가리키는 계정. 클라이언트가 토큰이 유효한지 확인하는 용도로도 쓴다.
     * 인증되지 않은 요청은 필터에서 걸러 401로 돌려주므로, 컨트롤러 안에서 jwt가 null인 경우는 없다.
     */
    @GetMapping
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        return new MeResponse(Long.parseLong(jwt.getSubject()));
    }

}
