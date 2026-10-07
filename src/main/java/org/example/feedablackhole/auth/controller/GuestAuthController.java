package org.example.feedablackhole.auth.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.feedablackhole.auth.dto.GuestLoginRequest;
import org.example.feedablackhole.auth.dto.GuestLoginResponse;
import org.example.feedablackhole.auth.dto.GuestRegisterResponse;
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

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public GuestRegisterResponse register() {
        return guestAuthService.register();
    }

    @PostMapping("/login")
    public GuestLoginResponse login(@Valid @RequestBody GuestLoginRequest request) {
        return new GuestLoginResponse(guestAuthService.login(request.guestId(), request.guestSecret()));
    }

}
