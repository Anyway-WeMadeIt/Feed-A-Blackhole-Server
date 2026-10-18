package org.example.feedablackhole.progress.dto;

import java.time.Instant;
import java.util.List;
import org.example.feedablackhole.progress.entity.PlayerNodeRank;
import org.example.feedablackhole.progress.entity.PlayerProgress;

/**
 * 계정의 진행 상태. GET /me/progress의 응답이고, 동기화 응답의 progress(SyncProgressResponse)와 같은 형식이다.
 *
 * @param lastAppliedSeq 서버가 마지막으로 반영한 동기화 로그의 seq. 클라이언트는 이 값 다음 번호부터 로그를 보낸다.
 * @param gold           Gold
 * @param growthStage    블랙홀의 성장도
 * @param nodes          산 노드와 Rank(처음 산 순서)
 * @param updatedAt      마지막으로 바뀐 시각(UTC)
 */
public record ProgressResponse(
        long lastAppliedSeq,
        long gold,
        int growthStage,
        List<NodeRankDto> nodes,
        Instant updatedAt) {

    public static ProgressResponse of(PlayerProgress progress, List<PlayerNodeRank> nodes) {
        return new ProgressResponse(
                progress.getLastAppliedSeq(),
                progress.getGold(),
                progress.getGrowthStage(),
                nodes.stream().map(node -> new NodeRankDto(node.getNodeId(), node.getRank())).toList(),
                progress.getUpdatedAt());
    }

}
