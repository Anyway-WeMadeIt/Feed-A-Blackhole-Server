package org.example.feedablackhole.node.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.feedablackhole.node.content.NodeContentFixture.assertDiagnostic;
import static org.example.feedablackhole.node.content.NodeContentFixture.diagnosticsOf;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 배치(layout.json)의 형식과 트리 규칙(콘텐츠와의 짝, 선, 시작 노드, 연결)을 확인한다.
 */
class NodeTreeLoaderTests {

    private static NodeTree load(String layout) {
        return NodeTreeFactory.load(NodeContentFixture.withLayout(layout));
    }

    // ---------- 유효한 배치 ----------

    @Test
    void validLayoutBuildsTheGraphInPlacementOrder() {
        NodeTree tree = NodeTreeFactory.load(NodeContentFixture.valid());

        assertThat(tree.graph().nodes()).containsExactly("timer-01", "speed-01", "count-01");
        assertThat(tree.nodes()).extracting(NodeDefinition::id).containsExactly("timer-01", "speed-01", "count-01");
        assertThat(tree.graph().isStart("timer-01")).isTrue();
        assertThat(tree.graph().isStart("speed-01")).isFalse();
        assertThat(tree.placementOf("count-01").orElseThrow())
                .satisfies(placement -> {
                    assertThat(placement.x()).isEqualTo(2);
                    assertThat(placement.y()).isZero();
                });
        assertThat(tree.unplaced()).isEmpty();
    }

    @Test
    void aLinkWrittenOnOneOrBothSidesIsOneLink() {
        String bothSides = """
                {"Nodes": [
                  {"Id": "timer-01", "Start": true, "X": 0, "Y": 0, "Links": ["speed-01"]},
                  {"Id": "speed-01", "X": 1, "Y": 0, "Links": ["timer-01", "count-01"]},
                  {"Id": "count-01", "X": 2, "Y": 0}
                ]}
                """;

        NodeGraph graph = load(bothSides).graph();

        assertThat(graph.links()).containsExactly(
                new NodeLink("timer-01", "speed-01"), new NodeLink("speed-01", "count-01"));
        assertThat(graph.neighborsOf("speed-01")).containsExactly("timer-01", "count-01");
    }

    @Test
    void neighborsAreOrderedByPlacementNotByTheOrderLinksWereWritten() {
        String layout = """
                {"Nodes": [
                  {"Id": "timer-01", "Start": true, "X": 0, "Y": 0, "Links": ["count-01", "speed-01"]},
                  {"Id": "speed-01", "X": 1, "Y": 0},
                  {"Id": "count-01", "X": 2, "Y": 0}
                ]}
                """;

        assertThat(load(layout).graph().neighborsOf("timer-01")).containsExactly("speed-01", "count-01");
    }

    @Test
    void contentNodeWithoutPlacementIsUnplacedAndNotBuyable() {
        String layout = """
                {"Nodes": [
                  {"Id": "timer-01", "Start": true, "X": 0, "Y": 0, "Links": ["speed-01"]},
                  {"Id": "speed-01", "X": 1, "Y": 0}
                ]}
                """;

        NodeTree tree = load(layout);

        assertThat(tree.unplaced()).containsExactly("count-01");
        assertThat(tree.node("count-01")).as("트리에서 빠진 노드").isEmpty();
        assertThat(tree.content().node("count-01")).as("콘텐츠에는 남아 있다").isPresent();
        assertThat(tree.graph().contains("count-01")).isFalse();
    }

    @Test
    void revealedMeansStartOrNextToAnOwnedNode() {
        NodeGraph graph = NodeTreeFactory.load(NodeContentFixture.valid()).graph();

        // 아무것도 사지 않았으면 시작 노드만 드러난다.
        assertThat(graph.isRevealed("timer-01", id -> false)).isTrue();
        assertThat(graph.isRevealed("speed-01", id -> false)).isFalse();
        assertThat(graph.isRevealed("count-01", id -> false)).isFalse();

        // 이웃을 샀으면 드러난다. 선은 방향이 없다.
        Set<String> owned = Set.of("speed-01");
        assertThat(graph.isRevealed("timer-01", owned::contains)).isTrue();
        assertThat(graph.isRevealed("count-01", owned::contains)).isTrue();

        // 이웃의 이웃은 드러나지 않는다. 없는 노드는 드러나지 않는다.
        assertThat(graph.isRevealed("count-01", Set.of("timer-01")::contains)).isFalse();
        assertThat(graph.isRevealed("ghost-01", id -> true)).isFalse();
        assertThat(graph.isRevealed(null, id -> true)).isFalse();
    }

    @Test
    void startMayBeOmittedAndUnknownFieldsAreIgnored() {
        String layout = """
                {"Comment": "무시", "Nodes": [
                  {"Id": "timer-01", "Start": true, "X": 0, "Y": 0, "Links": ["speed-01"], "Memo": "무시"},
                  {"Id": "speed-01", "X": 1, "Y": 0},
                  {"Id": "count-01", "X": 2, "Y": 0, "Links": ["speed-01"]}
                ]}
                """;

        assertThat(load(layout).graph().isStart("speed-01")).isFalse();
    }

    // ---------- 배치 파일 형식 ----------

    @Test
    void invalidJsonIsReported() {
        assertDiagnostic(diagnosticsOf(NodeContentFixture.withLayout("{not json")), "layout.json", "JSON 형식이 올바르지 않다");
    }

    @Test
    void missingLayoutFileIsReported() {
        assertDiagnostic(diagnosticsOf(NodeContentFixture.withLayout(null)), "layout.json", "배치 파일이 없다.");
    }

    @Test
    void topLevelMustHaveNodesArray() {
        assertDiagnostic(diagnosticsOf(NodeContentFixture.withLayout("{\"Items\": []}")), "layout.json", "\"Nodes\"");
        assertDiagnostic(diagnosticsOf(NodeContentFixture.withLayout("[]")), "layout.json", "\"Nodes\"");
    }

    @Test
    void badNodeFieldsAreReportedWithTheNodePosition() {
        String layout = """
                {"Nodes": [
                  {"Id": "timer-01", "Start": "yes", "X": 0, "Y": 0},
                  {"Id": "speed-01", "X": "1", "Y": 0},
                  {"Id": "count-01", "X": 2, "Y": 0, "Links": [7]},
                  {"X": 3, "Y": 0},
                  5
                ]}
                """;

        var diagnostics = diagnosticsOf(NodeContentFixture.withLayout(layout));

        assertDiagnostic(diagnostics, "Nodes[timer-01]", "Start는 true 또는 false여야 한다.");
        assertDiagnostic(diagnostics, "Nodes[speed-01]", "X는 정수여야 한다.");
        assertDiagnostic(diagnostics, "Nodes[count-01].Links[0]", "노드 ID(글자)여야 한다.");
        assertDiagnostic(diagnostics, "Nodes[3]", "Id는 비어 있지 않은 글자여야 한다.");
        assertDiagnostic(diagnostics, "Nodes[4]", "노드는 { } 객체여야 한다.");
    }

    // ---------- 트리 규칙 ----------

    @Test
    void placedNodeMustExistInTheContent() {
        String layout = """
                {"Nodes": [
                  {"Id": "timer-01", "Start": true, "X": 0, "Y": 0, "Links": ["ghost-01"]},
                  {"Id": "ghost-01", "X": 1, "Y": 0}
                ]}
                """;

        assertDiagnostic(diagnosticsOf(NodeContentFixture.withLayout(layout)), "Nodes[ghost-01]",
                "노드 콘텐츠(Nodes 시트)에 없는 노드다: 'ghost-01'.");
    }

    @Test
    void duplicatePlacementIdIsReported() {
        String layout = """
                {"Nodes": [
                  {"Id": "timer-01", "Start": true, "X": 0, "Y": 0},
                  {"Id": "timer-01", "X": 1, "Y": 0}
                ]}
                """;

        assertDiagnostic(diagnosticsOf(NodeContentFixture.withLayout(layout)), "Nodes[1]", "노드 ID 'timer-01'가 중복됐다.");
    }

    @Test
    void linksMustPointToAnotherPlacedNode() {
        String layout = """
                {"Nodes": [
                  {"Id": "timer-01", "Start": true, "X": 0, "Y": 0, "Links": ["timer-01", "nowhere", " "]}
                ]}
                """;

        var diagnostics = diagnosticsOf(NodeContentFixture.withLayout(layout));

        assertDiagnostic(diagnostics, "Nodes[timer-01].Links[0]", "자기 자신과 이을 수 없다.");
        assertDiagnostic(diagnostics, "Nodes[timer-01].Links[1]", "정의되지 않은 노드 ID다: 'nowhere'.");
        assertDiagnostic(diagnostics, "Nodes[timer-01].Links[2]", "이을 노드 ID가 비어 있다.");
    }

    @Test
    void oneCellHoldsOneNode() {
        String layout = """
                {"Nodes": [
                  {"Id": "timer-01", "Start": true, "X": 0, "Y": 0, "Links": ["speed-01"]},
                  {"Id": "speed-01", "X": 0, "Y": 0}
                ]}
                """;

        assertDiagnostic(diagnosticsOf(NodeContentFixture.withLayout(layout)), "Nodes[speed-01]",
                "칸 (0, 0)에 노드 'timer-01'가 이미 있다.");
    }

    @Test
    void atLeastOneStartNodeIsRequired() {
        String layout = """
                {"Nodes": [
                  {"Id": "timer-01", "X": 0, "Y": 0, "Links": ["speed-01"]},
                  {"Id": "speed-01", "X": 1, "Y": 0}
                ]}
                """;

        assertDiagnostic(diagnosticsOf(NodeContentFixture.withLayout(layout)), "Nodes", "시작 노드가 하나 이상 필요하다.");
    }

    @Test
    void everyPlacedNodeMustBeReachableFromAStartNode() {
        String layout = """
                {"Nodes": [
                  {"Id": "timer-01", "Start": true, "X": 0, "Y": 0, "Links": ["speed-01"]},
                  {"Id": "speed-01", "X": 1, "Y": 0},
                  {"Id": "count-01", "X": 5, "Y": 0}
                ]}
                """;

        assertDiagnostic(diagnosticsOf(NodeContentFixture.withLayout(layout)), "Nodes[count-01]",
                "시작 노드에서 선을 따라 닿지 않는다.");
    }

    @Test
    void emptyLayoutIsValidAndEveryContentNodeIsUnplaced() {
        NodeTree tree = load("{\"Nodes\": []}");

        assertThat(tree.nodes()).isEmpty();
        assertThat(tree.unplaced()).containsExactly("timer-01", "speed-01", "count-01");
    }

}
