package org.example.feedablackhole.battle.rules;

import org.example.feedablackhole.node.content.NodeContent;
import org.example.feedablackhole.node.content.NodeDefinition;
import org.example.feedablackhole.node.content.NodeEffect;
import org.example.feedablackhole.node.content.NodeTree;
import org.example.feedablackhole.node.content.UpgradeStatDefinition;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * "플레이어가 어떤 노드를 샀는지"를 넣으면, 각 수치(제한 시간, 크기, 확률 등)가 얼마가 되는지 계산해 주는 클래스
 */
public final class NodeUpgradeValues implements UpgradeValues {

    // 수치 하나의 정의를 float으로 바꿔 둔 것. min·max가 없으면 각각 음·양의 무한대
    private record StatRange(float defaultValue, double min, double max) {
    }

    private final Map<String, StatRange> ranges; // statId -> 정의(float으로 바꾼 값)
    private final Map<String, Double> sums;      // statId -> 산 효과 값의 합(double 누적). 합이 없는 수치는 키가 없다

    private NodeUpgradeValues(Map<String, StatRange> ranges, Map<String, Double> sums) {
        this.ranges = ranges;
        this.sums = sums;
    }

    /**
     * 산 노드 Rank로 수치 값을 계산한다. 산 노드 목록을 받아 이 객체를 만든다.
     *
     * @param nodeTree  노드 트리(배치된 노드의 순서와 효과)
     * @param nodeRanks nodeId -> 산 Rank (산 노드만)
     */
    public static NodeUpgradeValues of(NodeTree nodeTree, Map<String, Integer> nodeRanks) {
        return new NodeUpgradeValues(rangesOf(nodeTree.content()), sumsOf(nodeTree, nodeRanks));
    }

    // 수치의 최종 값을 돌려준다 (기본값 + 합, 최소·최대 범위 안으로)
    @Override
    public float valueOf(String statId) {
        StatRange range = rangeOf(statId);

        // 기본값(float)에 합(double)을 더하면 double 덧셈이다. C#의 DefaultValue + Sum과 같다
        double raw = (double) range.defaultValue() + sumOf(statId);

        // [min, max]로 자른 뒤 float으로 줄인다
        return (float) Math.min(range.max(), Math.max(range.min(), raw));
    }

    // 기본값에서 얼마나 늘었는지를 돌려준다 (최종 값 - 기본값)
    @Override
    public float gainOf(String statId) {
        // 자른 값 - 기본값. 둘 다 float이라 float 뺄셈이다
        return valueOf(statId) - rangeOf(statId).defaultValue();
    }

    // 수치마다 기본값과 최솟값·최댓값을 모아 둔다
    private static Map<String, StatRange> rangesOf(NodeContent content) {
        Map<String, StatRange> ranges = new HashMap<>();

        for (UpgradeStatDefinition stat : content.stats()) {
            // 시트 칸이 비어 있으면 null. 그쪽은 제한이 없다는 뜻이라 무한대로 둔다
            double min = stat.min() == null ? Double.NEGATIVE_INFINITY : floatOf(stat.min());
            double max = stat.max() == null ? Double.POSITIVE_INFINITY : floatOf(stat.max());

            ranges.put(stat.statId(), new StatRange(floatOf(stat.defaultValue()), min, max));
        }

        return ranges;
    }

    // 노드를 배치 순서로 돌며, 산 Rank까지의 효과를 수치마다 double로 누적한다
    // 산 노드들의 효과를 수치별로 합친다 (timer +2, 크기 +1 ...)
    private static Map<String, Double> sumsOf(NodeTree nodeTree, Map<String, Integer> nodeRanks) {
        Map<String, Double> sums = new HashMap<>();

        for (NodeDefinition node : nodeTree.nodes()) { // 배치된 노드만, 배치 순서
            // 요청한 Rank가 최대 Rank보다 크면 최대 Rank까지만 쓴다. 산 적 없으면 0
            int owned = Math.min(nodeRanks.getOrDefault(node.id(), 0), node.maxRank());

            for (int rank = 1; rank <= owned; rank++) {         // Rank 1부터 차례로
                for (NodeEffect effect : node.rankAt(rank).effects()) { // 시트 순서의 효과
                    // float으로 바꾼 값을 double 합에 더한다 (C#: double 합 + float 값)
                    sums.merge(effect.statId(), (double) floatOf(effect.value()), Double::sum);
                }
            }
        }

        return sums;
    }

    // statId의 정의. 없으면 IllegalArgumentException
    // 수치의 기본값·범위를 찾아 준다 (없는 수치면 예외)
    private StatRange rangeOf(String statId) {
        StatRange range = ranges.get(statId);
        if (range == null) {
            throw new IllegalArgumentException("없는 수치다: " + statId);
        }

        return range;
    }

    // statId의 효과 합. 합이 없으면 0
    // 수치의 효과 합을 찾아 준다 (노드를 안 샀으면 0)
    private double sumOf(String statId) {
        return sums.getOrDefault(statId, 0.0);
    }

    // BigDecimal -> float. 계약 1.7의 "효과 값은 BigDecimal.floatValue()"가 이 자리다
    // 시트의 소수(BigDecimal)를 float으로 바꾼다
    private static float floatOf(BigDecimal value) {
        return value.floatValue();
    }

}
