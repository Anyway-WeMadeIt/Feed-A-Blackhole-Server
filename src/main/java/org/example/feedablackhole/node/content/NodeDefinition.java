package org.example.feedablackhole.node.content;

import java.util.List;

/**
 * 노드 하나의 정의: ID와 Rank 1부터 마지막 Rank까지의 비용과 효과. Rank는 차례로 하나씩 산다.
 */
public record NodeDefinition(String id, List<NodeRankDefinition> ranks) {

    public NodeDefinition {
        ranks = List.copyOf(ranks);
    }

    public int maxRank() {
        return ranks.size();
    }

    /** 1 ≤ rank ≤ maxRank. */
    public NodeRankDefinition rankAt(int rank) {
        if (rank < 1 || rank > maxRank()) {
            throw new IllegalArgumentException("Rank는 1부터 " + maxRank() + "까지다: " + rank);
        }
        return ranks.get(rank - 1);
    }

}
