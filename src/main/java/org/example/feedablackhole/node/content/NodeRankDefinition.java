package org.example.feedablackhole.node.content;

import java.util.List;

/**
 * 노드의 Rank 하나: 사는 데 드는 비용(Gold, 0보다 큼)과 효과.
 */
public record NodeRankDefinition(int rank, long cost, List<NodeEffect> effects) {

    public NodeRankDefinition {
        effects = List.copyOf(effects);
    }

}
