package org.example.feedablackhole.sync.handler;

import lombok.RequiredArgsConstructor;
import org.example.feedablackhole.node.content.NodeDefinition;
import org.example.feedablackhole.node.content.NodeTree;
import org.example.feedablackhole.sync.log.LogType;
import org.example.feedablackhole.sync.log.RejectCode;
import org.example.feedablackhole.sync.log.payload.NodePurchasePayload;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public final class NodePurchaseHandler implements LogHandler<NodePurchasePayload> {

    private final NodeTree nodeTree;

    @Override
    public LogType type() {
        return LogType.NODE_PURCHASE;
    }

    @Override
    public Class<NodePurchasePayload> payloadType() {
        return NodePurchasePayload.class;
    }

    @Override
    public HandleResult handle(NodePurchasePayload payload, SyncContext context) {
        Optional<NodeDefinition> found = nodeTree.node(payload.nodeId());

        if (found.isEmpty()) {
            return HandleResult.rejected(RejectCode.UNKNOWN_NODE, "서버 콘텐츠에 없는 노드다: " + payload.nodeId());
        }

        NodeDefinition node = found.get();
        int current = context.nodeRanks().getOrDefault(payload.nodeId(), 0);

        if (current >= node.maxRank()) {
            return HandleResult.rejected(RejectCode.MAX_RANK_REACHED, "이미 최대 Rank다: " + payload.nodeId());
        }

        boolean revealed = nodeTree.graph().isRevealed(payload.nodeId(), context.nodeRanks()::containsKey);
        if (!revealed) {
            return HandleResult.rejected(RejectCode.NODE_HIDDEN, "아직 드러나지 않은 노드다: " + payload.nodeId());
        }

        long nextCost = node.rankAt(current + 1).cost();
        if (context.gold() < nextCost) {
            return HandleResult.rejected(RejectCode.NOT_ENOUGH_GOLD, "Gold가 부족하다: 필요 " + nextCost + ", 보유 " + context.gold());
        }

        int rank = payload.rank();
        long cost = payload.cost();

        if (rank != current + 1) {
            return HandleResult.rejected(RejectCode.RANK_MISMATCH, "Rank가 맞지 않는다: 다음 Rank " + (current + 1) + ", 받은 값 " + rank);
        }

        if (cost != nextCost) {
            return HandleResult.rejected(RejectCode.COST_MISMATCH, "비용이 맞지 않는다: 서버 " + nextCost + ", 받은 값 " + cost);
        }

        context.spendGold(nextCost);
        context.setNodeRank(payload.nodeId(), current + 1);

        return HandleResult.applied();
    }

}
