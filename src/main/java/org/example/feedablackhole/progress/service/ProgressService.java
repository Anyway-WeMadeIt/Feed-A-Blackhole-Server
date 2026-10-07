package org.example.feedablackhole.progress.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.example.feedablackhole.common.exception.ApiException;
import org.example.feedablackhole.common.exception.ErrorCode;
import org.example.feedablackhole.progress.dto.NodeRankDto;
import org.example.feedablackhole.progress.dto.ProgressResponse;
import org.example.feedablackhole.progress.dto.SaveProgressRequest;
import org.example.feedablackhole.progress.entity.PlayerNodeRank;
import org.example.feedablackhole.progress.entity.PlayerProgress;
import org.example.feedablackhole.progress.repository.PlayerNodeRankRepository;
import org.example.feedablackhole.progress.repository.PlayerProgressRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계정의 진행 상태(Gold, 성장도, 산 노드)를 불러오고, 저장하고, 처음으로 되돌린다.
 *
 * <p>저장(save)은 클라이언트가 진행 상태를 올려 보내는 과도기 방식이다. 서버가 노드 구매와 전투 결산을 직접 처리하게 되면
 * 이 방식은 없어진다. 그 전까지 서버가 할 수 있는 검사는 값의 범위, 중복, "줄지 않음"과 버전 확인뿐이다.
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

    /**
     * 진행 상태 전체를 요청의 값으로 교체한다. 같은 계정의 변경은 행 잠금으로 한 번에 하나씩 처리된다.
     *
     * <p>거부하는 경우: 형식이 틀림(중복 노드, 400), 읽은 버전이 낡음(409 STALE_PROGRESS),
     * 성장도나 산 노드가 줄어듦(409 PROGRESS_REGRESSION). 거부하면 아무것도 바뀌지 않는다.
     */
    @Transactional
    public ProgressResponse save(long accountId, SaveProgressRequest request) {
        Map<String, Integer> requested = toRankMap(request.nodes());

        PlayerProgress progress = lockProgress(accountId);

        if (progress.getRevision() != request.baseRevision()) {
            throw new ApiException(ErrorCode.STALE_PROGRESS);
        }
        if (request.growthStage() < progress.getGrowthStage()) {
            throw new ApiException(ErrorCode.PROGRESS_REGRESSION,
                    "growthStage는 줄어들 수 없습니다. 서버 " + progress.getGrowthStage() + ", 요청 " + request.growthStage());
        }

        List<PlayerNodeRank> saved = nodeRankRepository.findAllByAccountIdOrderByIdAsc(accountId);
        for (PlayerNodeRank existing : saved) {
            Integer rank = requested.get(existing.getNodeId());
            if (rank == null || rank < existing.getRank()) {
                throw new ApiException(ErrorCode.PROGRESS_REGRESSION,
                        "산 노드가 빠지거나 Rank가 줄어들 수 없습니다: " + existing.getNodeId());
            }
        }

        progress.update(request.gold(), request.growthStage());

        for (PlayerNodeRank existing : saved) {
            existing.changeRank(requested.remove(existing.getNodeId()));
        }
        // 이미 있는 노드를 뺀 나머지가 새로 산 노드다. 요청의 순서대로 저장되어 "처음 산 순서"가 된다.
        requested.forEach((nodeId, rank) ->
                nodeRankRepository.save(PlayerNodeRank.of(progress.getAccount(), nodeId, rank)));

        return respond(progress);
    }

    /**
     * 새 게임: Gold, 성장도, 산 노드를 처음 상태로 되돌린다. revision은 오르므로, 되돌리기 전의 버전으로 온 저장은 거부된다.
     */
    @Transactional
    public ProgressResponse reset(long accountId) {
        PlayerProgress progress = lockProgress(accountId);
        progress.reset();
        nodeRankRepository.deleteAllByAccountId(accountId);
        return respond(progress);
    }

    /**
     * 진행도를 조회하면서 동시에 해당 행을 잠근다.
     * 한 번에 한 요청만 진행도를 수정할 수 있게 만든다.
     */
    private PlayerProgress lockProgress(long accountId) {
        return progressRepository.findForUpdateByAccountId(accountId)
                .orElseThrow(() -> missingProgress(accountId));
    }

    private ProgressResponse respond(PlayerProgress progress) {
        return ProgressResponse.of(progress, nodeRankRepository.findAllByAccountIdOrderByIdAsc(progress.getAccountId()));
    }

    // 요청의 노드 목록을 nodeId → rank로 바꾼다(요청 순서 유지). 같은 nodeId가 두 번 있으면 형식 오류다.
    private static Map<String, Integer> toRankMap(List<NodeRankDto> nodes) {
        Map<String, Integer> ranks = new LinkedHashMap<>();
        for (NodeRankDto node : nodes) {
            if (ranks.put(node.nodeId(), node.rank()) != null) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "nodes: nodeId가 중복되었습니다: " + node.nodeId());
            }
        }
        return ranks;
    }

    // 모든 계정은 만들어질 때(또는 V2 마이그레이션으로) 진행 상태가 있어야 한다. 없다면 클라이언트의 잘못이 아니라 서버의 문제다.
    private static IllegalStateException missingProgress(long accountId) {
        return new IllegalStateException("진행 상태가 없는 계정입니다. accountId=" + accountId);
    }

}
