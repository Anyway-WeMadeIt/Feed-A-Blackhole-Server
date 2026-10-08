package org.example.feedablackhole.node.content;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 노드 트리 = 배치(layout.json) + 콘텐츠(시트). 노드 ID로 짝짓는다.
 * 배치는 칸·선·시작 노드를, 콘텐츠는 노드 ID·Rank·비용·효과를 가진다. 클라이언트의 NodeTreeLoader와 같은 규칙이다.
 *
 * <p>세 단계에 걸쳐 불러온다(앞 단계에 문제가 있으면 거기서 멈춘다).
 * <ol>
 * <li>배치 노드 하나씩: 콘텐츠(Nodes 시트)에 있는 노드.
 * <li>노드 사이: ID 유일, 선이 가리키는 노드가 배치에 있고 자기 자신이 아님, 한 칸에 노드 하나(서버가 더한 규칙).
 * <li>그래프 전체: 배치된 노드가 있으면 시작 노드가 하나 이상이고, 모든 배치 노드가 시작 노드에서 선을 따라 닿음.
 * </ol>
 * 콘텐츠에만 있는 노드(아직 배치하지 않음)는 오류가 아니다 — 트리에서 빠지고 unplaced로 알린다.
 */
public final class NodeTreeLoader {

    private NodeTreeLoader() {
    }

    public static NodeTreeLoadResult load(List<NodePlacement> placements, NodeContent content) {
        List<ContentDiagnostic> diagnostics = new ArrayList<>();
        List<NodeDefinition> nodes = new ArrayList<>(placements.size());

        for (int i = 0; i < placements.size(); i++) {
            NodeDefinition node = content.node(placements.get(i).id()).orElse(null);
            if (node == null) {
                diagnostics.add(new ContentDiagnostic(at(i, placements.get(i).id()),
                        "노드 콘텐츠(Nodes 시트)에 없는 노드다: '" + placements.get(i).id() + "'."));
            } else {
                nodes.add(node);
            }
        }
        if (!diagnostics.isEmpty()) {
            return new NodeTreeLoadResult(null, diagnostics);
        }

        NodeGraph graph = loadGraph(placements, diagnostics);
        if (!diagnostics.isEmpty()) {
            return new NodeTreeLoadResult(null, diagnostics);
        }

        verifyReachable(graph, diagnostics);
        if (!diagnostics.isEmpty()) {
            return new NodeTreeLoadResult(null, diagnostics);
        }

        Set<String> placed = new HashSet<>(graph.nodes());
        List<String> unplaced = new ArrayList<>();
        for (NodeDefinition node : content.nodes()) {
            if (!placed.contains(node.id())) {
                unplaced.add(node.id());
            }
        }

        String nodeContentVersion = NodeContentVersion.of(content, placements, graph);
        return new NodeTreeLoadResult(new NodeTree(graph, nodes, placements, content, nodeContentVersion, unplaced), diagnostics);
    }

    // 그래프 칸만 읽는다. 선은 방향이 없다: 한쪽에만 적어도, 양쪽에 적어도 같은 선 하나다.
    private static NodeGraph loadGraph(List<NodePlacement> placements, List<ContentDiagnostic> into) {
        List<String> ids = new ArrayList<>(placements.size());
        Set<String> starts = new LinkedHashSet<>();
        Map<String, Set<String>> neighbors = new HashMap<>();
        Map<String, String> byCell = new HashMap<>();

        for (int i = 0; i < placements.size(); i++) {
            NodePlacement placement = placements.get(i);
            String id = placement.id();

            if (neighbors.containsKey(id)) {
                into.add(new ContentDiagnostic("Nodes[" + i + "]", "노드 ID '" + id + "'가 중복됐다."));
                continue;
            }

            String cell = placement.x() + "," + placement.y();
            String other = byCell.putIfAbsent(cell, id);
            if (other != null) {
                into.add(new ContentDiagnostic(at(i, id),
                        "칸 (" + placement.x() + ", " + placement.y() + ")에 노드 '" + other + "'가 이미 있다."));
            }

            ids.add(id);
            neighbors.put(id, new HashSet<>());
            if (placement.start()) {
                starts.add(id);
            }
        }

        for (int i = 0; i < placements.size(); i++) {
            String id = placements.get(i).id();
            List<String> links = placements.get(i).links();

            for (int k = 0; k < links.size(); k++) {
                String at = at(i, id) + ".Links[" + k + "]";
                String link = links.get(k);

                if (link.isBlank()) {
                    into.add(new ContentDiagnostic(at, "이을 노드 ID가 비어 있다."));
                } else if (link.equals(id)) {
                    into.add(new ContentDiagnostic(at, "자기 자신과 이을 수 없다."));
                } else if (!neighbors.containsKey(link)) {
                    into.add(new ContentDiagnostic(at, "정의되지 않은 노드 ID다: '" + link + "'."));
                } else {
                    neighbors.get(id).add(link);
                    neighbors.get(link).add(id);
                }
            }
        }

        // 이웃은 배치 순서로 정렬해, 같은 입력이면 항상 같은 순서가 되게 한다.
        Map<String, Integer> order = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            order.put(ids.get(i), i);
        }
        Map<String, List<String>> ordered = new HashMap<>();
        neighbors.forEach((id, set) -> {
            List<String> list = new ArrayList<>(set);
            list.sort((left, right) -> Integer.compare(order.get(left), order.get(right)));
            ordered.put(id, List.copyOf(list));
        });

        return new NodeGraph(ids, starts, ordered);
    }

    private static void verifyReachable(NodeGraph graph, List<ContentDiagnostic> into) {
        if (graph.nodes().isEmpty()) {
            return;
        }

        boolean anyStart = graph.nodes().stream().anyMatch(graph::isStart);
        if (!anyStart) {
            into.add(new ContentDiagnostic("Nodes", "시작 노드가 하나 이상 필요하다."));
            return;
        }

        for (String id : graph.unreachable()) {
            into.add(new ContentDiagnostic("Nodes[" + id + "]", "시작 노드에서 선을 따라 닿지 않는다."));
        }
    }

    private static String at(int index, String id) {
        return id == null || id.isBlank() ? "Nodes[" + index + "]" : "Nodes[" + id + "]";
    }

}
