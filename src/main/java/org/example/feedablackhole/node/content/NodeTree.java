package org.example.feedablackhole.node.content;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 불러온 노드 트리: 배치(칸, 시작 노드, 선)와 노드 정의(콘텐츠)를 노드 ID로 이은 것. 불변이며 서버가 시작할 때 한 번 만든다.
 * 구매는 이 트리에 있는 노드만 할 수 있다. 콘텐츠에만 있고 배치되지 않은 노드는 트리에서 빠지고 unplaced로 알린다.
 *
 * <p>nodeContentVersion은 콘텐츠 파일 내용으로 계산한 버전이다. 파일이 한 글자라도 바뀌면 달라진다.
 */
public final class NodeTree {

    private final NodeGraph graph;
    private final List<NodeDefinition> nodes;
    private final List<NodePlacement> placements;
    private final NodeContent content;
    private final String nodeContentVersion;
    private final List<String> unplaced;
    private final Map<String, NodeDefinition> nodesById = new HashMap<>();
    private final Map<String, NodePlacement> placementsById = new HashMap<>();

    NodeTree(NodeGraph graph, List<NodeDefinition> nodes, List<NodePlacement> placements, NodeContent content,
            String nodeContentVersion, List<String> unplaced) {
        this.graph = graph;
        this.nodes = List.copyOf(nodes);
        this.placements = List.copyOf(placements);
        this.content = content;
        this.nodeContentVersion = nodeContentVersion;
        this.unplaced = List.copyOf(unplaced);
        nodes.forEach(node -> nodesById.put(node.id(), node));
        placements.forEach(placement -> placementsById.put(placement.id(), placement));
    }

    public NodeGraph graph() {
        return graph;
    }

    /** 배치된 노드의 정의(배치 순서). */
    public List<NodeDefinition> nodes() {
        return nodes;
    }

    /** 배치(배치 순서). */
    public List<NodePlacement> placements() {
        return placements;
    }

    /** 이 트리를 만든 콘텐츠: 수치 정의와 배치되지 않은 노드까지 모든 노드. */
    public NodeContent content() {
        return content;
    }

    public String nodeContentVersion() {
        return nodeContentVersion;
    }

    /** 콘텐츠에는 있지만 배치되지 않아 살 수 없는 노드의 ID. */
    public List<String> unplaced() {
        return unplaced;
    }

    /** 트리에 있는(배치된) 노드의 정의. */
    public Optional<NodeDefinition> node(String id) {
        return Optional.ofNullable(nodesById.get(id));
    }

    public Optional<NodePlacement> placementOf(String id) {
        return Optional.ofNullable(placementsById.get(id));
    }

}
