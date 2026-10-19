package org.example.feedablackhole.node.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * NodeGraph이 만든 쪽의 자료를 복사해 불변으로 보관하는지 확인한다(로더가 건네는 맵과 목록을 나중에 고쳐도 영향이 없다).
 */
class NodeGraphTests {

    private static NodeGraph graphOver(List<String> nodes, Set<String> starts, Map<String, List<String>> neighbors) {
        return new NodeGraph(nodes, starts, neighbors);
    }

    @Test
    void changingTheInputsAfterwardsDoesNotChangeTheGraph() {
        List<String> nodes = new ArrayList<>(List.of("a", "b"));
        Set<String> starts = new HashSet<>(Set.of("a"));
        Map<String, List<String>> neighbors = new HashMap<>();
        neighbors.put("a", new ArrayList<>(List.of("b")));
        neighbors.put("b", new ArrayList<>(List.of("a")));

        NodeGraph graph = graphOver(nodes, starts, neighbors);

        nodes.add("c");
        starts.add("b");
        neighbors.get("a").clear();
        neighbors.put("c", List.of("a"));

        assertThat(graph.nodes()).containsExactly("a", "b");
        assertThat(graph.isStart("b")).isFalse();
        assertThat(graph.neighborsOf("a")).containsExactly("b");
        assertThat(graph.contains("c")).isFalse();
        assertThat(graph.links()).containsExactly(new NodeLink("a", "b"));
    }

    @Test
    void whatTheGraphHandsOutCannotBeModified() {
        NodeGraph graph = graphOver(
                List.of("a", "b"), Set.of("a"), Map.of("a", List.of("b"), "b", List.of("a")));

        assertThatThrownBy(() -> graph.nodes().add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> graph.neighborsOf("a").add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> graph.links().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void anUnknownNodeHasNoNeighbors() {
        NodeGraph graph = graphOver(List.of("a"), Set.of("a"), Map.of("a", List.of()));

        assertThat(graph.neighborsOf("ghost")).isEmpty();
        assertThat(graph.contains("ghost")).isFalse();
    }

}
