package org.example.feedablackhole.sync.handler;

import lombok.RequiredArgsConstructor;
import org.example.feedablackhole.node.content.NodeDefinition;
import org.example.feedablackhole.node.content.NodeTree;
import org.example.feedablackhole.sync.log.LogType;
import org.example.feedablackhole.sync.log.RejectCode;
import org.example.feedablackhole.sync.log.payload.NodePurchasePayload;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * NODE_PURCHASE(노드 구매) 로그의 처리기. 구매 규칙(클라이언트 NodePurchase)을 서버에서 다시 검사하고,
 * 모두 통과하면 Gold를 쓰고 Rank를 올린다. 클라이언트가 보낸 payload(nodeId, rank, cost)는 믿지 않고 서버 값과 비교한다.
 *
 * <p>검사 순서는 계약 3.6 표의 순서이고, 처음 걸린 것 하나로 거부한다. 거부할 때는 진행도를 바꾸지 않고,
 * 검사를 모두 마친 뒤에만 spendGold, setNodeRank를 부른다(거부하면서 바꾸면 엔진이 요청 전체를 되돌린다).
 */
@Component
@RequiredArgsConstructor
public final class NodePurchaseHandler implements LogHandler<NodePurchasePayload> {

    // 서버가 읽은 노드 콘텐츠(배치, 선, 노드별 Rank와 비용). 서버 시작 때 한 번 만든 불변 객체다
    private final NodeTree nodeTree;

    // 이 처리기가 맡는 로그 타입. 엔진이 이 값으로 처리기를 찾는다
    @Override
    public LogType type() {
        return LogType.NODE_PURCHASE;
    }

    // 이 로그의 payload 타입. 계약(3.3)의 타입과 다르면 서버가 시작하지 않는다
    @Override
    public Class<NodePurchasePayload> payloadType() {
        return NodePurchasePayload.class;
    }

    // 노드 구매 하나를 검사하고 반영한다. 반영하면 applied, 규칙을 어기면 거부 코드와 함께 rejected를 돌려준다
    @Override
    public HandleResult handle(NodePurchasePayload payload, SyncContext context) {
        // 1) 서버 콘텐츠에 있고 배치된 노드인가(UNKNOWN_NODE)
        Optional<NodeDefinition> found = nodeTree.node(payload.nodeId());

        if (found.isEmpty()) {
            return HandleResult.rejected(RejectCode.UNKNOWN_NODE, "서버 콘텐츠에 없는 노드다: " + payload.nodeId());
        }

        // 지금까지 산 Rank(산 적 없으면 0). nodeRanks()는 산 노드만 담은 읽기 전용 맵이다
        NodeDefinition node = found.get();
        int current = context.nodeRanks().getOrDefault(payload.nodeId(), 0);

        // 2) 이미 최대 Rank인가(MAX_RANK_REACHED). rankAt(current + 1)이 범위 밖이 되지 않게 노출·비용 확인보다 먼저 본다
        if (current >= node.maxRank()) {
            return HandleResult.rejected(RejectCode.MAX_RANK_REACHED, "이미 최대 Rank다: " + payload.nodeId());
        }

        // 3) 드러난 노드인가(NODE_HIDDEN). 시작 노드이거나, 선으로 이어진 노드 중 하나를 이미 샀으면 드러난다
        boolean revealed = nodeTree.graph().isRevealed(payload.nodeId(), context.nodeRanks()::containsKey);
        if (!revealed) {
            return HandleResult.rejected(RejectCode.NODE_HIDDEN, "아직 드러나지 않은 노드다: " + payload.nodeId());
        }

        // 4) Gold가 다음 Rank의 비용 이상인가(NOT_ENOUGH_GOLD). 비용은 클라이언트가 보낸 값이 아니라 서버 콘텐츠의 값이다
        long nextCost = node.rankAt(current + 1).cost();
        if (context.gold() < nextCost) {
            return HandleResult.rejected(RejectCode.NOT_ENOUGH_GOLD, "Gold가 부족하다: 필요 " + nextCost + ", 보유 " + context.gold());
        }

        // 클라이언트가 보낸 rank와 cost
        int rank = payload.rank();
        long cost = payload.cost();

        // 5) 보낸 rank가 다음 Rank(현재 + 1)인가(RANK_MISMATCH). 한 번에 한 Rank씩만 살 수 있다
        if (rank != current + 1) {
            return HandleResult.rejected(RejectCode.RANK_MISMATCH, "Rank가 맞지 않는다: 다음 Rank " + (current + 1) + ", 받은 값 " + rank);
        }

        // 6) 보낸 cost가 서버의 다음 Rank 비용과 같은가(COST_MISMATCH). 비용을 깎아서 보내는 조작을 막는다
        if (cost != nextCost) {
            return HandleResult.rejected(RejectCode.COST_MISMATCH, "비용이 맞지 않는다: 서버 " + nextCost + ", 받은 값 " + cost);
        }

        // 모든 검사를 통과했다. 서버가 계산한 비용으로 Gold를 쓰고 Rank를 올린다
        context.spendGold(nextCost);
        context.setNodeRank(payload.nodeId(), current + 1);

        return HandleResult.applied();
    }

}
