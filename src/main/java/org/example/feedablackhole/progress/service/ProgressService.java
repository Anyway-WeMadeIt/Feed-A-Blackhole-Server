package org.example.feedablackhole.progress.service;

import lombok.RequiredArgsConstructor;
import org.example.feedablackhole.progress.dto.ProgressResponse;
import org.example.feedablackhole.progress.entity.PlayerProgress;
import org.example.feedablackhole.progress.repository.PlayerNodeRankRepository;
import org.example.feedablackhole.progress.repository.PlayerProgressRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계정의 진행 상태(Gold, 성장도, 산 노드)를 조회한다.
 * 진행 상태는 동기화 API(POST /me/sync)로만 바뀌고, 이 서비스는 읽기만 한다.
 */
@Service
@RequiredArgsConstructor
public class ProgressService {

    private final PlayerProgressRepository progressRepository;
    private final PlayerNodeRankRepository nodeRankRepository;

    /**
     * 진행도를 조회한다.
     */
    @Transactional(readOnly = true)
    public ProgressResponse get(long accountId) {
        PlayerProgress progress = progressRepository.findById(accountId)
                .orElseThrow(() -> missingProgress(accountId));
        return respond(progress);
    }

    private ProgressResponse respond(PlayerProgress progress) {
        return ProgressResponse.of(progress, nodeRankRepository.findAllByAccountIdOrderByIdAsc(progress.getAccountId()));
    }

    // 모든 계정은 만들어질 때(또는 V2 마이그레이션으로) 진행 상태가 있어야 한다. 없다면 클라이언트의 잘못이 아니라 서버의 문제다.
    private static IllegalStateException missingProgress(long accountId) {
        return new IllegalStateException("진행 상태가 없는 계정입니다. accountId=" + accountId);
    }

}
