package org.example.feedablackhole.sync.controller;

import lombok.RequiredArgsConstructor;
import org.example.feedablackhole.auth.security.CurrentAccountId;
import org.example.feedablackhole.sync.dto.SyncResponse;
import org.example.feedablackhole.sync.service.SyncRequestReader;
import org.example.feedablackhole.sync.service.SyncService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 동기화: 클라이언트가 쌓은 로그를 제출하고 서버 진행도를 받는다(API 명세 "동기화", 계약 3장).
 * 본문은 글자로 받아 직접 읽는다. 로그 하나의 형식 오류는 요청 전체의 400이 아니라 그 로그의 거부이기 때문이다.
 */
@RestController
@RequestMapping("/api/v1/me/sync")
@RequiredArgsConstructor
public class SyncController {

    private final SyncRequestReader reader;
    private final SyncService syncService;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public SyncResponse sync(@CurrentAccountId Long accountId, @RequestBody String body) {
        return syncService.sync(accountId, reader.read(body));
    }

}
