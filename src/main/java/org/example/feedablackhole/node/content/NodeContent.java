package org.example.feedablackhole.node.content;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 불러온 노드 콘텐츠(시트 4개): 수치 정의와 노드 정의. 시트 순서를 지킨다.
 * 배치(칸, 시작 노드, 선)는 가지지 않는다. 그것은 NodeTree가 노드 ID로 짝지어 가진다.
 */
public final class NodeContent {

    private final List<UpgradeStatDefinition> stats;
    private final List<NodeDefinition> nodes;
    private final Map<String, UpgradeStatDefinition> statsById = new HashMap<>();
    private final Map<String, NodeDefinition> nodesById = new HashMap<>();

    NodeContent(List<UpgradeStatDefinition> stats, List<NodeDefinition> nodes) {
        this.stats = List.copyOf(stats);
        this.nodes = List.copyOf(nodes);
        stats.forEach(stat -> statsById.put(stat.statId(), stat));
        nodes.forEach(node -> nodesById.put(node.id(), node));
    }

    public List<UpgradeStatDefinition> stats() {
        return stats;
    }

    public List<NodeDefinition> nodes() {
        return nodes;
    }

    public Optional<UpgradeStatDefinition> stat(String statId) {
        return Optional.ofNullable(statsById.get(statId));
    }

    public Optional<NodeDefinition> node(String nodeId) {
        return Optional.ofNullable(nodesById.get(nodeId));
    }

}
