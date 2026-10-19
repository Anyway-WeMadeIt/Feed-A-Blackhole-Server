package org.example.feedablackhole.node.content;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 노드 트리의 그래프: 노드 ID, 시작 노드, 선. "여기까지 어떻게 도달하는가"만 안다. 가격·Gold·업그레이드는 모른다.
 *
 * <p>선은 방향이 없고 순환을 허용한다. 드러남 = 시작 노드이거나, 산 노드와 선으로 이어짐(묻는 쪽이 산 노드를 넘겨 확인한다).
 * 순서는 배치 순서다(같은 입력이면 항상 같은 순서).
 */
public final class NodeGraph {

    private final List<String> nodes;
    private final Set<String> starts;
    private final Map<String, List<String>> neighbors;
    private final List<NodeLink> links;

    NodeGraph(List<String> nodes, Set<String> starts, Map<String, List<String>> neighbors) {
        this.nodes = List.copyOf(nodes);
        this.starts = Set.copyOf(starts);
        // 맵과 그 안의 목록까지 복사한다. 만든 쪽이 나중에 원본을 고쳐도 그래프는 변하지 않는다.
        this.neighbors = neighbors.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));

        // 각 선을 한 번만, 배치 순서가 앞선 노드를 a로 낸다.
        List<NodeLink> distinct = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String id : nodes) {
            for (String neighbor : neighbors.get(id)) {
                if (seen.contains(neighbor)) {
                    distinct.add(new NodeLink(neighbor, id));
                }
            }
            seen.add(id);
        }
        this.links = List.copyOf(distinct);
    }

    /** 노드 ID(배치 순서). */
    public List<String> nodes() {
        return nodes;
    }

    /** 선 목록. 한 선은 한 번만 나온다. */
    public List<NodeLink> links() {
        return links;
    }

    public boolean contains(String id) {
        return id != null && neighbors.containsKey(id);
    }

    public boolean isStart(String id) {
        return id != null && starts.contains(id);
    }

    /** 선으로 이어진 노드(배치 순서). 없는 노드면 빈 목록이다. */
    public List<String> neighborsOf(String id) {
        return neighbors.getOrDefault(id, List.of());
    }

    /** 시작 노드이거나, owns가 true인 노드와 선으로 이어졌는가. 없는 노드는 false다. */
    public boolean isRevealed(String id, Predicate<String> owns) {
        if (!contains(id)) {
            return false;
        }
        if (starts.contains(id)) {
            return true;
        }
        for (String neighbor : neighbors.get(id)) {
            if (owns.test(neighbor)) {
                return true;
            }
        }
        return false;
    }

    /** 시작 노드에서 선을 따라 닿지 않는 노드. 로더의 연결 검사가 사용한다. */
    List<String> unreachable() {
        Set<String> reached = new HashSet<>(starts);
        Deque<String> frontier = new ArrayDeque<>(starts);

        while (!frontier.isEmpty()) {
            for (String neighbor : neighbors.get(frontier.poll())) {
                if (reached.add(neighbor)) {
                    frontier.add(neighbor);
                }
            }
        }

        List<String> unreachable = new ArrayList<>();
        for (String id : nodes) {
            if (!reached.contains(id)) {
                unreachable.add(id);
            }
        }
        return unreachable;
    }

}
