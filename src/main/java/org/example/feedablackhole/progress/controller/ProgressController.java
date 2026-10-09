package org.example.feedablackhole.progress.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.feedablackhole.auth.security.CurrentAccountId;
import org.example.feedablackhole.progress.dto.ProgressResponse;
import org.example.feedablackhole.progress.dto.SaveProgressRequest;
import org.example.feedablackhole.progress.service.ProgressService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인한 사용자 자신의 진행 상태. 인증된 계정의 것만 다룬다(계정 ID는 요청이 아니라 액세스 토큰에서 온다).
 */
@RestController
@RequestMapping("/api/v1/me/progress")
@RequiredArgsConstructor
public class ProgressController {

    private final ProgressService progressService;

    @GetMapping
    public ProgressResponse get(@CurrentAccountId Long accountId) {
        return progressService.get(accountId);
    }

    @PutMapping
    public ProgressResponse save(
            @CurrentAccountId Long accountId, @Valid @RequestBody SaveProgressRequest request) {
        return progressService.save(accountId, request);
    }

    @PostMapping("/reset")
    public ProgressResponse reset(@CurrentAccountId Long accountId) {
        return progressService.reset(accountId);
    }

}
