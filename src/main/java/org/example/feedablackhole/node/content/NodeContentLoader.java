package org.example.feedablackhole.node.content;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * NodeContentData(시트 4개의 행) → NodeContent.
 * 행 순서에 뜻을 두지 않는다: 수치는 StatId로, 노드는 NodeId로, Rank는 (NodeId, Rank)로 짝짓는다.
 *
 * <p>콘텐츠 규칙은 모두 여기서 본다. 클라이언트의 NodeContentLoader와 같은 규칙이다.
 * <ol>
 * <li>수치: StatId 유일, ValueType·Unit 이름, Aggregation은 Add뿐, Min ≤ Max, DefaultValue는 Min·Max 안, Int 수치는 정수.
 * <li>노드: NodeId 유일, RankCount 1 이상.
 * <li>비용: Nodes 시트에 있는 노드, Rank는 1 ~ RankCount, (노드, Rank)마다 하나, 0보다 큼.
 * <li>효과: 노드·Rank는 비용과 같은 기준, UpgradeStats 시트에 있는 StatId, Unit이 수치의 Unit과 같음, Int 수치면 정수.
 * <li>빠짐: 모든 노드의 모든 Rank에 비용과 효과가 있음.
 * </ol>
 * 오류는 한 번에 모두 모은다. 하나라도 있으면 결과가 없다(부분 통과 금지).
 * 수치 합이 Max를 넘는 것은 오류가 아니다 — 계산할 때 자른다.
 *
 * <p>클라이언트 코드의 수치 목록(UpgradeStat)과 시트의 StatId가 일치하는지는 여기서 보지 않는다.
 * 서버가 그 코드를 모르므로, 수치 ID 집합을 고정한 테스트(RealNodeContentTests)가 그 계약을 지킨다.
 */
public final class NodeContentLoader {

    public static final String ADD_AGGREGATION = "Add";

    private NodeContentLoader() {
    }

    public static NodeContentLoadResult load(NodeContentData data) {
        List<ContentDiagnostic> diagnostics = new ArrayList<>();

        Set<String> statIds = new HashSet<>();
        Map<String, UpgradeStatDefinition> statsById = new HashMap<>();
        List<UpgradeStatDefinition> stats = loadStats(data.stats(), statIds, statsById, diagnostics);

        Set<String> nodeIds = new HashSet<>();
        List<NodeEntry> nodes = loadNodes(data.nodes(), nodeIds, diagnostics);
        Map<String, NodeEntry> nodesById = new HashMap<>();
        nodes.forEach(node -> nodesById.put(node.id(), node));

        Map<NodeRankKey, Long> costs = loadCosts(data.costs(), nodeIds, nodesById, diagnostics);

        Set<NodeRankKey> effectRanks = new HashSet<>();
        Map<NodeRankKey, List<NodeEffect>> effects =
                loadEffects(data.effects(), nodeIds, nodesById, statIds, statsById, effectRanks, diagnostics);

        // 효과는 시트의 수치 순서로 정렬한다(같은 Rank의 효과 순서가 시트 행 순서에 닿지 않게).
        Map<String, Integer> statOrder = new HashMap<>();
        for (int i = 0; i < stats.size(); i++) {
            statOrder.put(stats.get(i).statId(), i);
        }
        List<NodeDefinition> definitions = assemble(nodes, costs, effects, effectRanks, statOrder, diagnostics);

        return diagnostics.isEmpty()
                ? new NodeContentLoadResult(new NodeContent(stats, definitions), diagnostics)
                : new NodeContentLoadResult(null, diagnostics);
    }

    // 1. 수치. 틀린 수치도 ID는 statIds에 넣는다 — 그 수치를 쓰는 효과마다 "없는 StatId"가 겹쳐 나오지 않게.
    private static List<UpgradeStatDefinition> loadStats(List<NodeContentData.StatRow> rows, Set<String> statIds,
            Map<String, UpgradeStatDefinition> statsById, List<ContentDiagnostic> into) {
        List<UpgradeStatDefinition> stats = new ArrayList<>(rows.size());
        Map<String, String> firstAt = new HashMap<>();

        for (NodeContentData.StatRow row : rows) {
            String at = at(NodeContentCsvReader.STATS_TAB, row.row());
            String id = row.statId();

            if (id.isEmpty()) {
                into.add(new ContentDiagnostic(at, "StatId가 비어 있다."));
                continue;
            }

            String first = firstAt.get(id);
            if (first != null) {
                into.add(new ContentDiagnostic(at, "StatId '" + id + "'가 " + first + "에도 있다."));
                continue;
            }

            firstAt.put(id, at);
            statIds.add(id);
            int before = into.size();

            StatValueType valueType = StatValueType.fromSheetName(row.valueType()).orElse(null);
            if (valueType == null) {
                into.add(new ContentDiagnostic(at, "ValueType은 Float·Int 가운데 하나다. 받은 값: '" + row.valueType() + "'."));
            }

            StatUnit unit = StatUnit.fromSheetName(row.unit()).orElse(null);
            if (unit == null) {
                into.add(new ContentDiagnostic(at, "Unit은 Flat·Percent 가운데 하나다. 받은 값: '" + row.unit() + "'."));
            }

            if (!ADD_AGGREGATION.equals(row.aggregation())) {
                into.add(new ContentDiagnostic(at,
                        "Aggregation은 " + ADD_AGGREGATION + "만 쓸 수 있다. 받은 값: '" + row.aggregation() + "'."));
            }

            BigDecimal min = row.min();
            BigDecimal max = row.max();

            if (min != null && max != null && min.compareTo(max) > 0) {
                into.add(new ContentDiagnostic(at,
                        "Min(" + plain(min) + ")이 Max(" + plain(max) + ")보다 크다."));
            } else if ((min != null && row.defaultValue().compareTo(min) < 0)
                    || (max != null && row.defaultValue().compareTo(max) > 0)) {
                into.add(new ContentDiagnostic(at, "DefaultValue(" + plain(row.defaultValue()) + ")가 Min·Max ["
                        + (min == null ? "-∞" : plain(min)) + ", " + (max == null ? "∞" : plain(max)) + "] 밖이다."));
            }

            if (valueType == StatValueType.INT && (!isWhole(row.defaultValue())
                    || (min != null && !isWhole(min)) || (max != null && !isWhole(max)))) {
                into.add(new ContentDiagnostic(at, "Int 수치는 DefaultValue·Min·Max가 정수여야 한다."));
            }

            if (into.size() > before) {
                continue;
            }

            UpgradeStatDefinition stat =
                    new UpgradeStatDefinition(id, valueType, unit, row.defaultValue(), min, max);
            stats.add(stat);
            statsById.put(id, stat);
        }

        return stats;
    }

    // 2. 노드. RankCount가 틀린 노드도 ID는 nodeIds에 넣는다 — 그 노드의 비용·효과 행마다 "없는 NodeId"가 겹쳐 나오지 않게.
    private static List<NodeEntry> loadNodes(
            List<NodeContentData.NodeRow> rows, Set<String> nodeIds, List<ContentDiagnostic> into) {
        List<NodeEntry> nodes = new ArrayList<>(rows.size());
        Map<String, String> firstAt = new HashMap<>();

        for (NodeContentData.NodeRow row : rows) {
            String at = at(NodeContentCsvReader.NODES_TAB, row.row());
            String id = row.nodeId();

            if (id.isEmpty()) {
                into.add(new ContentDiagnostic(at, "NodeId가 비어 있다."));
                continue;
            }

            String first = firstAt.get(id);
            if (first != null) {
                into.add(new ContentDiagnostic(at, "NodeId '" + id + "'가 " + first + "에도 있다."));
                continue;
            }

            firstAt.put(id, at);
            nodeIds.add(id);

            if (row.rankCount() < 1) {
                into.add(new ContentDiagnostic(at, "'" + id + "'의 RankCount는 1 이상이다. 받은 값: " + row.rankCount() + "."));
                continue;
            }

            nodes.add(new NodeEntry(id, row.rankCount(), at));
        }

        return nodes;
    }

    // 3. 비용. 0 이하인 비용도 자리는 채운다 — 5단계가 "비용 없음"을 겹쳐 알리지 않게. 진단이 있으니 결과는 나오지 않는다.
    private static Map<NodeRankKey, Long> loadCosts(List<NodeContentData.CostRow> rows, Set<String> nodeIds,
            Map<String, NodeEntry> nodes, List<ContentDiagnostic> into) {
        Map<NodeRankKey, Long> costs = new HashMap<>();
        Map<NodeRankKey, String> firstAt = new HashMap<>();

        for (NodeContentData.CostRow row : rows) {
            String at = at(NodeContentCsvReader.COST_TAB, row.row());
            String id = row.nodeId();

            if (!isRankOf(id, row.rank(), at, nodeIds, nodes, into)) {
                continue;
            }

            NodeRankKey key = new NodeRankKey(id, row.rank());
            String first = firstAt.get(key);
            if (first != null) {
                into.add(new ContentDiagnostic(at, "'" + id + "' Rank " + row.rank() + "의 비용이 " + first + "에도 있다."));
                continue;
            }

            if (row.cost() <= 0) {
                into.add(new ContentDiagnostic(at,
                        "'" + id + "' Rank " + row.rank() + "의 비용은 0보다 커야 한다. 받은 값: " + row.cost() + "."));
            }

            firstAt.put(key, at);
            costs.put(key, row.cost());
        }

        return costs;
    }

    // 4. 효과. 같은 (노드, Rank)에 효과가 여럿이어도 된다.
    // ranks: 효과 행이 하나라도 가리킨 (노드, Rank). 그 행이 다른 이유로 틀렸어도 넣는다 — 5단계가 "효과 없음"을 겹쳐 알리지 않게.
    private static Map<NodeRankKey, List<NodeEffect>> loadEffects(List<NodeContentData.EffectRow> rows,
            Set<String> nodeIds, Map<String, NodeEntry> nodes, Set<String> statIds,
            Map<String, UpgradeStatDefinition> stats, Set<NodeRankKey> ranks, List<ContentDiagnostic> into) {
        Map<NodeRankKey, List<NodeEffect>> effects = new HashMap<>();

        for (NodeContentData.EffectRow row : rows) {
            String at = at(NodeContentCsvReader.EFFECTS_TAB, row.row());
            String id = row.nodeId();

            if (!isRankOf(id, row.rank(), at, nodeIds, nodes, into)) {
                continue;
            }

            NodeRankKey key = new NodeRankKey(id, row.rank());
            ranks.add(key);

            if (!statIds.contains(row.statId())) {
                into.add(new ContentDiagnostic(at, "UpgradeStats 시트에 없는 StatId다: '" + row.statId() + "'."));
                continue;
            }

            // 수치 정의 자체가 틀렸으면(1단계에서 이미 알림) 단위·정수 검사는 건너뛴다. 그 경우 결과가 없다.
            UpgradeStatDefinition stat = stats.get(row.statId());
            if (stat == null || !fits(stat, row, at, into)) {
                continue;
            }

            effects.computeIfAbsent(key, k -> new ArrayList<>()).add(new NodeEffect(row.statId(), row.value()));
        }

        return effects;
    }

    // 5. 노드마다 Rank 1부터 RankCount까지 비용과 효과를 모아 정의를 만든다. Nodes 시트 순서를 지킨다.
    private static List<NodeDefinition> assemble(List<NodeEntry> nodes, Map<NodeRankKey, Long> costs,
            Map<NodeRankKey, List<NodeEffect>> effects, Set<NodeRankKey> effectRanks, Map<String, Integer> statOrder,
            List<ContentDiagnostic> into) {
        Comparator<NodeEffect> byStatThenValue = Comparator
                .comparing((NodeEffect effect) -> statOrder.getOrDefault(effect.statId(), Integer.MAX_VALUE))
                .thenComparing(NodeEffect::value);
        List<NodeDefinition> definitions = new ArrayList<>(nodes.size());

        for (NodeEntry node : nodes) {
            List<NodeRankDefinition> ranks = new ArrayList<>(node.rankCount());
            List<Integer> noCost = new ArrayList<>();
            List<Integer> noEffect = new ArrayList<>();

            for (int rank = 1; rank <= node.rankCount(); rank++) {
                NodeRankKey key = new NodeRankKey(node.id(), rank);
                Long cost = costs.get(key);
                List<NodeEffect> list = effects.get(key);

                if (cost == null) {
                    noCost.add(rank);
                }
                if (!effectRanks.contains(key)) {
                    noEffect.add(rank);
                }
                if (cost == null || list == null) {
                    continue;
                }

                List<NodeEffect> sorted = new ArrayList<>(list);
                sorted.sort(byStatThenValue);
                ranks.add(new NodeRankDefinition(rank, cost, sorted));
            }

            if (!noCost.isEmpty()) {
                into.add(new ContentDiagnostic(node.at(), "'" + node.id() + "'의 " + rankList(noCost) + " 비용이 "
                        + NodeContentCsvReader.COST_TAB + " 시트에 없다."));
            }
            if (!noEffect.isEmpty()) {
                into.add(new ContentDiagnostic(node.at(), "'" + node.id() + "'의 " + rankList(noEffect) + " 효과가 "
                        + NodeContentCsvReader.EFFECTS_TAB + " 시트에 없다."));
            }
            if (ranks.size() == node.rankCount()) {
                definitions.add(new NodeDefinition(node.id(), ranks));
            }
        }

        return definitions;
    }

    // 비용·효과 행이 가리키는 (노드, Rank)가 있는가. RankCount가 틀린 노드(2단계에서 이미 알림)를 가리키면 조용히 false다.
    private static boolean isRankOf(String id, int rank, String at, Set<String> nodeIds, Map<String, NodeEntry> nodes,
            List<ContentDiagnostic> into) {
        if (!nodeIds.contains(id)) {
            into.add(new ContentDiagnostic(at, NodeContentCsvReader.NODES_TAB + " 시트에 없는 NodeId다: '" + id + "'."));
            return false;
        }

        NodeEntry node = nodes.get(id);
        if (node == null) {
            return false;
        }

        if (rank < 1 || rank > node.rankCount()) {
            into.add(new ContentDiagnostic(at, "'" + id + "'의 Rank는 1부터 " + node.rankCount()
                    + "까지다(Nodes 시트 RankCount). 받은 값: " + rank + "."));
            return false;
        }

        return true;
    }

    // 효과 값이 수치 정의에 맞는가: 단위가 같고, Int 수치면 정수다.
    private static boolean fits(
            UpgradeStatDefinition stat, NodeContentData.EffectRow row, String at, List<ContentDiagnostic> into) {
        int before = into.size();

        StatUnit unit = StatUnit.fromSheetName(row.unit()).orElse(null);
        if (unit == null) {
            into.add(new ContentDiagnostic(at, "Unit은 Flat·Percent 가운데 하나다. 받은 값: '" + row.unit() + "'."));
        } else if (unit != stat.unit()) {
            into.add(new ContentDiagnostic(at, "Unit이 수치 정의와 다르다: '" + stat.statId() + "'는 "
                    + stat.unit().sheetName() + "인데 " + unit.sheetName() + "로 적었다."));
        }

        if (stat.valueType() == StatValueType.INT && !isWhole(row.value())) {
            into.add(new ContentDiagnostic(at,
                    "'" + stat.statId() + "'는 정수 수치다. 받은 값: " + plain(row.value()) + "."));
        }

        return into.size() == before;
    }

    private static boolean isWhole(BigDecimal value) {
        return value.signum() == 0 || value.stripTrailingZeros().scale() <= 0;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String rankList(List<Integer> ranks) {
        return "Rank " + ranks.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    private static String at(String tab, int row) {
        return tab + " " + row + "행";
    }

    private record NodeEntry(String id, int rankCount, String at) {
    }

    private record NodeRankKey(String nodeId, int rank) {
    }

}
