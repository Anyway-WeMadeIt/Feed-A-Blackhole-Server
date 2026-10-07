package org.example.feedablackhole.auth.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.feedablackhole.auth.dto.GuestLoginRequest;
import org.example.feedablackhole.auth.dto.GuestRegisterResponse;
import org.example.feedablackhole.auth.dto.TokenResponse;
import org.example.feedablackhole.auth.service.GuestAuthService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/guest")
@RequiredArgsConstructor
public class GuestAuthController {

    private final GuestAuthService guestAuthService;

    /**
     * 새로운 게스트 사용자를 등록한다.
     * @return 생성된 게스트 id와 secret을 돌려준다. DB에는 해시된 secret만 저장된다.
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public GuestRegisterResponse register() {
        return guestAuthService.register();
    }

    /**
     * 게스트로 로그인한다.
     * 성공 시 유효한 새 토큰 쌍을 발급해 돌려준다.
     */
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody GuestLoginRequest request) {
        return guestAuthService.login(request.guestId(), request.guestSecret());
    }

}
