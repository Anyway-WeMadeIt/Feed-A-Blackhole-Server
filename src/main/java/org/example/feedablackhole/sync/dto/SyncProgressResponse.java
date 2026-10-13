package org.example.feedablackhole.sync.dto;

import java.time.Instant;
import java.util.List;
import org.example.feedablackhole.progress.dto.NodeRankDto;
import org.example.feedablackhole.progress.entity.PlayerNodeRank;
import org.example.feedablackhole.progress.entity.PlayerProgress;

/**
 * 동기화 응답에 담는 진행도(API 명세 "진행 데이터"). GET /me/progress도 W1-7에서 이 형식이 된다.
 *
 * @param nodes 산 노드와 Rank(처음 산 순서)
 */
public record SyncProgressResponse(
        long lastAppliedSeq,
        long gold,
        int growthStage,
        List<NodeRankDto> nodes,
        Instant updatedAt) {

    public SyncProgressResponse {
        nodes = List.copyOf(nodes);
    }

    public static SyncProgressResponse of(PlayerProgress progress, List<PlayerNodeRank> nodes) {
        return new SyncProgressResponse(
                progress.getLastAppliedSeq(),
                progress.getGold(),
                progress.getGrowthStage(),
                nodes.stream().map(node -> new NodeRankDto(node.getNodeId(), node.getRank())).toList(),
                progress.getUpdatedAt());
    }

}
