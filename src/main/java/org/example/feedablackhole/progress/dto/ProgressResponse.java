package org.example.feedablackhole.progress.dto;

import java.time.Instant;
import java.util.List;
import org.example.feedablackhole.progress.entity.PlayerNodeRank;
import org.example.feedablackhole.progress.entity.PlayerProgress;

/**
 * 계정의 진행 상태. 불러오기, 저장, 초기화가 모두 이 형식으로 응답한다.
 *
 * @param revision    진행 상태를 바꿀 때마다 1 오르는 버전. 저장할 때 baseRevision으로 되돌려 보낸다.
 * @param gold        Gold
 * @param growthStage 블랙홀의 성장도
 * @param nodes       산 노드와 Rank(처음 산 순서)
 * @param updatedAt   마지막으로 바뀐 시각(UTC)
 */
public record ProgressResponse(
        long revision,
        long gold,
        int growthStage,
        List<NodeRankDto> nodes,
        Instant updatedAt) {

    public static ProgressResponse of(PlayerProgress progress, List<PlayerNodeRank> nodes) {
        return new ProgressResponse(
                progress.getRevision(),
                progress.getGold(),
                progress.getGrowthStage(),
                nodes.stream().map(node -> new NodeRankDto(node.getNodeId(), node.getRank())).toList(),
                progress.getUpdatedAt());
    }

}
