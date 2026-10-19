package org.example.feedablackhole.node.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * 규칙 하나하나를 검증하기 위한 작은 유효 콘텐츠. 테스트는 여기서 한 군데만 고쳐 문제를 만든다.
 *
 * <pre>
 * 수치  : timer(Float, Flat, 12) · asteroid.count(Int, Flat, 5, 0~100) · breaker.speed(Float, Percent, 100)
 * 노드  : timer-01(1 Rank) · speed-01(2 Rank) · count-01(1 Rank)
 * 배치  : timer-01(시작, 0,0) — speed-01(1,0) — count-01(2,0)
 * </pre>
 * 시트는 필수 칸 + Memo 칸이다(실제 콘텐츠와 같은 형식).
 */
final class NodeContentFixture {

    static final String STATS = """
            StatId,ValueType,Unit,DefaultValue,Aggregation,Min,Max,Memo
            timer,Float,Flat,12,Add,0,,세션 타이머
            asteroid.count,Int,Flat,5,Add,0,100,
            breaker.speed,Float,Percent,100,Add,0,,
            """;

    static final String NODES = """
            NodeId,RankCount,Memo
            timer-01,1,
            speed-01,2,
            count-01,1,
            """;

    static final String COST = """
            NodeId,Rank,Cost,Memo
            timer-01,1,2,
            speed-01,1,"1,000",
            speed-01,2,2000,
            count-01,1,"2,600,000,000,000",
            """;

    static final String EFFECTS = """
            NodeId,Rank,StatId,Value,Unit,Memo
            timer-01,1,timer,2,Flat,
            speed-01,1,breaker.speed,10,Percent,
            speed-01,2,breaker.speed,12.5,Percent,
            count-01,1,asteroid.count,3,Flat,
            """;

    static final String LAYOUT = """
            {"Nodes": [
              {"Id": "timer-01", "Start": true, "X": 0, "Y": 0, "Links": ["speed-01"]},
              {"Id": "speed-01", "Start": false, "X": 1, "Y": 0, "Links": []},
              {"Id": "count-01", "Start": false, "X": 2, "Y": 0, "Links": ["speed-01"]}
            ]}
            """;

    private NodeContentFixture() {
    }

    static NodeContentFiles valid() {
        return new NodeContentFiles(STATS, NODES, COST, EFFECTS, LAYOUT);
    }

    static NodeContentFiles withStats(String stats) {
        return new NodeContentFiles(stats, NODES, COST, EFFECTS, LAYOUT);
    }

    static NodeContentFiles withNodes(String nodes) {
        return new NodeContentFiles(STATS, nodes, COST, EFFECTS, LAYOUT);
    }

    static NodeContentFiles withCost(String cost) {
        return new NodeContentFiles(STATS, NODES, cost, EFFECTS, LAYOUT);
    }

    static NodeContentFiles withEffects(String effects) {
        return new NodeContentFiles(STATS, NODES, COST, effects, LAYOUT);
    }

    static NodeContentFiles withLayout(String layout) {
        return new NodeContentFiles(STATS, NODES, COST, EFFECTS, layout);
    }

    /** 시트 네 개를 같은 변환으로 바꾼다(배치는 그대로). */
    static NodeContentFiles mapSheets(UnaryOperator<String> change) {
        return new NodeContentFiles(change.apply(STATS), change.apply(NODES), change.apply(COST), change.apply(EFFECTS), LAYOUT);
    }

    /**
     * Memo 오른쪽에 칸을 더한다: 쉼표·줄바꿈이 든 칸, 필수 칸과 같은 이름의 머리칸(NodeId, Rank, Cost, Value, StatId), 옛 Enabled 칸,
     * 머리칸이 빈 칸. 이런 칸이 있어도 읽는 결과가 같아야 한다.
     */
    static String withColumnsAfterMemo(String csv) {
        String[] lines = csv.split("\n");
        StringBuilder out = new StringBuilder(lines[0]).append(",분석,NodeId,Rank,Cost,Value,StatId,Enabled,,메모2\n");
        for (int i = 1; i < lines.length; i++) {
            out.append(lines[i]).append(",\"쉼표, 포함\",garbage,garbage,garbage,garbage,garbage,maybe,빈머리,\"줄\n바꿈\"\n");
        }
        return out.toString();
    }

    /** 마지막 칸(Memo)을 뺀다. 픽스처의 Memo 칸은 항상 맨 끝이고 그 안에 쉼표가 없다. */
    static String withoutMemoColumn(String csv) {
        StringBuilder out = new StringBuilder();
        for (String line : csv.split("\n")) {
            out.append(line, 0, line.lastIndexOf(',')).append('\n');
        }
        return out.toString();
    }

    /** 불러오기를 시도해 진단을 돌려준다. 성공하면 빈 목록이다. */
    static List<ContentDiagnostic> diagnosticsOf(NodeContentFiles files) {
        try {
            NodeTreeFactory.load(files);
            return List.of();
        } catch (ContentLoadException e) {
            return e.getDiagnostics();
        }
    }

    /** 위치(at)와 메시지에 각각 해당 글자가 든 진단이 있는지 확인한다. */
    static void assertDiagnostic(List<ContentDiagnostic> diagnostics, String atPart, String messagePart) {
        assertThat(diagnostics)
                .as("진단 목록: %s", diagnostics)
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.at()).contains(atPart);
                    assertThat(diagnostic.message()).contains(messagePart);
                });
    }

}
