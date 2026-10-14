package org.example.feedablackhole.battle.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.example.feedablackhole.node.content.NodeDefinition;
import org.example.feedablackhole.node.content.NodeTree;
import org.example.feedablackhole.node.content.UpgradeStatDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * NodeUpgradeValues가 산 노드로 수치 값을 올바르게 계산하는지 확인하는 테스트.
 * 샘플 콘텐츠 묶음(src/test/resources/content-sample)의 노드 트리로 검사한다.
 */
class NodeUpgradeValuesTests {

    private static NodeTree tree;

    // 샘플 묶음의 노드 트리를 가져온다
    @BeforeAll
    static void loadTree() {
        tree = SampleContent.tree();
    }

    /**
     * 노드를 하나도 사지 않으면 모든 수치가 기본값이고, 늘어난 양은 0이다.
     */
    @Test
    void noNodesBoughtGivesTheDefaultValues() {
        NodeUpgradeValues values = NodeUpgradeValues.of(tree, Map.of());

        // 제한 시간의 시트 기본값은 12초
        assertThat(values.valueOf("timer")).isEqualTo(12f);
        assertThat(values.gainOf("timer")).isZero();

        // 어떤 수치든 늘어난 양이 0이어야 한다
        for (UpgradeStatDefinition stat : tree.content().stats()) {
            assertThat(values.gainOf(stat.statId())).as(stat.statId()).isZero();
        }
    }

    /**
     * 노드를 사면 그 Rank의 효과가 수치에 더해진다. timer-01(1 Rank)은 제한 시간을 2초 늘린다.
     */
    @Test
    void aBoughtNodeAddsItsEffect() {
        NodeUpgradeValues values = NodeUpgradeValues.of(tree, Map.of("timer-01", 1));

        assertThat(values.valueOf("timer")).isEqualTo(14f);
        assertThat(values.gainOf("timer")).isEqualTo(2f);
    }

    /**
     * 최대 Rank보다 큰 Rank를 요청해도 최대 Rank까지만 반영하고, 트리에 없는 노드는 무시한다.
     */
    @Test
    void ranksAboveTheMaximumAreCappedAndUnknownNodesAreIgnored() {
        NodeUpgradeValues values = NodeUpgradeValues.of(tree, Map.of("timer-01", 99, "no-such-node", 3));

        // timer-01은 1 Rank까지라서 99를 요청해도 +2만 반영된다
        assertThat(values.gainOf("timer")).isEqualTo(2f);
    }

    /**
     * 시트에 없는 수치를 물으면 IllegalArgumentException이다.
     */
    @Test
    void unknownStatIsRejected() {
        NodeUpgradeValues values = NodeUpgradeValues.of(tree, Map.of());

        assertThatThrownBy(() -> values.valueOf("no.such"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> values.gainOf("no.such"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 모든 노드를 마지막 Rank까지 사도 모든 수치가 [min, max] 안에 있다. 상한이 있는 수치는 상한에서 멈춘다.
     * (예: 재생성 확률은 최대 100%)
     */
    @Test
    void valuesStayInsideTheirMinAndMaxWhenEveryNodeIsBought() {
        Map<String, Integer> everything = new HashMap<>();
        for (NodeDefinition node : tree.nodes()) {
            everything.put(node.id(), node.maxRank());
        }
        NodeUpgradeValues values = NodeUpgradeValues.of(tree, everything);

        for (UpgradeStatDefinition stat : tree.content().stats()) {
            float value = values.valueOf(stat.statId());
            if (stat.min() != null) {
                assertThat(value).as(stat.statId()).isGreaterThanOrEqualTo(stat.min().floatValue());
            }
            if (stat.max() != null) {
                assertThat(value).as(stat.statId()).isLessThanOrEqualTo(stat.max().floatValue());
            }
        }
    }

}
