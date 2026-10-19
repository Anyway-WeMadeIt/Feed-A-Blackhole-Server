package org.example.feedablackhole.node.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.feedablackhole.node.content.NodeContentFixture.assertDiagnostic;
import static org.example.feedablackhole.node.content.NodeContentFixture.diagnosticsOf;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 시트 4개의 형식과 콘텐츠 규칙을 하나씩 확인한다. 유효한 작은 콘텐츠(NodeContentFixture)에서 한 군데만 고쳐 문제를 만든다.
 */
class NodeContentLoaderTests {

    // ---------- 유효한 콘텐츠 ----------

    @Test
    void validContentLoadsWithEverythingInSheetOrder() {
        NodeTree tree = NodeTreeFactory.load(NodeContentFixture.valid());
        NodeContent content = tree.content();

        assertThat(content.stats()).extracting(UpgradeStatDefinition::statId)
                .containsExactly("timer", "asteroid.count", "breaker.speed");
        assertThat(content.nodes()).extracting(NodeDefinition::id).containsExactly("timer-01", "speed-01", "count-01");

        UpgradeStatDefinition count = content.stat("asteroid.count").orElseThrow();
        assertThat(count.valueType()).isEqualTo(StatValueType.INT);
        assertThat(count.unit()).isEqualTo(StatUnit.FLAT);
        assertThat(count.defaultValue()).isEqualByComparingTo("5");
        assertThat(count.min()).isEqualByComparingTo("0");
        assertThat(count.max()).isEqualByComparingTo("100");

        UpgradeStatDefinition timer = content.stat("timer").orElseThrow();
        assertThat(timer.max()).as("비어 있는 Max는 제한 없음").isNull();
    }

    @Test
    void thousandsSeparatorsInCostAreAccepted() {
        NodeContent content = NodeTreeFactory.load(NodeContentFixture.valid()).content();

        assertThat(content.node("speed-01").orElseThrow().rankAt(1).cost()).isEqualTo(1000L);
        assertThat(content.node("count-01").orElseThrow().rankAt(1).cost()).isEqualTo(2_600_000_000_000L);
    }

    @Test
    void decimalEffectValuesAreKeptExactly() {
        NodeContent content = NodeTreeFactory.load(NodeContentFixture.valid()).content();

        BigDecimal value = content.node("speed-01").orElseThrow().rankAt(2).effects().get(0).value();

        assertThat(value).isEqualByComparingTo(new BigDecimal("12.5"));
    }

    @Test
    void effectsOfOneRankFollowTheStatOrderOfTheSheetNotTheRowOrder() {
        String effects = NodeContentFixture.EFFECTS.replace(
                "timer-01,1,timer,2,Flat,\n",
                "timer-01,1,breaker.speed,5,Percent,\ntimer-01,1,timer,2,Flat,\n");

        NodeContent content = NodeTreeFactory.load(NodeContentFixture.withEffects(effects)).content();

        assertThat(content.node("timer-01").orElseThrow().rankAt(1).effects())
                .extracting(NodeEffect::statId).containsExactly("timer", "breaker.speed");
    }

    @Test
    void columnOrderExtraColumnsAndBlankRowsDoNotMatter() {
        String cost = """
                Cost,Memo,Rank,NodeId

                2,,1,timer-01
                "1,000",메모,1,speed-01
                ,,,
                2000,,2,speed-01
                "2,600,000,000,000",,1,count-01
                """;

        NodeContent content = NodeTreeFactory.load(NodeContentFixture.withCost(cost)).content();

        assertThat(content.node("speed-01").orElseThrow().rankAt(2).cost()).isEqualTo(2000L);
        assertThat(content.node("timer-01").orElseThrow().rankAt(1).cost()).isEqualTo(2L);
    }

    // ---------- 파일 형식 ----------

    @Test
    void missingSheetIsReported() {
        NodeContentFiles files = new NodeContentFiles(
                null, NodeContentFixture.NODES, NodeContentFixture.COST, NodeContentFixture.EFFECTS,
                NodeContentFixture.LAYOUT);

        assertDiagnostic(diagnosticsOf(files), "UpgradeStats", "시트(CSV)가 없다.");
    }

    @Test
    void emptySheetIsReported() {
        assertDiagnostic(diagnosticsOf(NodeContentFixture.withNodes("")), "Nodes", "비어 있다.");
    }

    @Test
    void missingHeaderColumnIsReported() {
        String nodes = NodeContentFixture.NODES.replace("RankCount", "Rank Count");

        assertDiagnostic(diagnosticsOf(NodeContentFixture.withNodes(nodes)), "Nodes 1행", "머리칸 'RankCount'이 없다.");
    }

    @Test
    void nonNumericCellIsReportedWithItsSheetCoordinate() {
        String cost = NodeContentFixture.COST.replace("speed-01,2,2000", "speed-01,x,2000");

        // 머리칸이 1행이고 speed-01 Rank 2 행은 4행, Rank 칸은 B열이다.
        assertDiagnostic(diagnosticsOf(NodeContentFixture.withCost(cost)), "NodeCost!B4", "정수가 필요하다. 받은 값: 'x'.");
    }

    @Test
    void percentSignInValueIsRejected() {
        String effects = NodeContentFixture.EFFECTS.replace("breaker.speed,10,Percent", "breaker.speed,10%,Percent");

        assertDiagnostic(diagnosticsOf(NodeContentFixture.withEffects(effects)), "NodeEffects!D3",
                "숫자(소수는 점으로, % 기호 없이)가 필요하다. 받은 값: '10%'.");
    }

    @Test
    void costBeyondLongRangeIsRejected() {
        String cost = NodeContentFixture.COST.replace("timer-01,1,2", "timer-01,1,9223372036854775808");

        assertDiagnostic(diagnosticsOf(NodeContentFixture.withCost(cost)), "NodeCost!C2", "정수가 필요하다.");
    }

    @Test
    void blankRequiredCellIsReportedAsEmpty() {
        String cost = NodeContentFixture.COST.replace("timer-01,1,2", "timer-01,1,");

        assertDiagnostic(diagnosticsOf(NodeContentFixture.withCost(cost)), "NodeCost!C2", "비어 있다.");
    }

    // ---------- 1. 수치 ----------

    @Test
    void duplicateStatIdIsReported() {
        String stats = NodeContentFixture.STATS + "timer,Float,Flat,0,Add,0,,\n";

        assertDiagnostic(diagnosticsOf(NodeContentFixture.withStats(stats)), "UpgradeStats 5행",
                "StatId 'timer'가 UpgradeStats 2행에도 있다.");
    }

    @Test
    void unknownValueTypeUnitAndAggregationAreReported() {
        String stats = NodeContentFixture.STATS
                .replace("timer,Float,Flat,12,Add", "timer,Double,Flat,12,Add")
                .replace("asteroid.count,Int,Flat,5,Add", "asteroid.count,Int,flat,5,Mul");

        List<ContentDiagnostic> diagnostics = diagnosticsOf(NodeContentFixture.withStats(stats));

        assertDiagnostic(diagnostics, "UpgradeStats 2행", "ValueType은 Float·Int 가운데 하나다. 받은 값: 'Double'.");
        assertDiagnostic(diagnostics, "UpgradeStats 3행", "Unit은 Flat·Percent 가운데 하나다. 받은 값: 'flat'.");
        assertDiagnostic(diagnostics, "UpgradeStats 3행", "Aggregation은 Add만 쓸 수 있다. 받은 값: 'Mul'.");
    }

    @Test
    void minGreaterThanMaxIsReported() {
        String stats = NodeContentFixture.STATS.replace("asteroid.count,Int,Flat,5,Add,0,100", "asteroid.count,Int,Flat,5,Add,200,100");

        assertDiagnostic(diagnosticsOf(NodeContentFixture.withStats(stats)), "UpgradeStats 3행", "Min(200)이 Max(100)보다 크다.");
    }

    @Test
    void defaultValueOutsideMinMaxIsReported() {
        String stats = NodeContentFixture.STATS.replace("asteroid.count,Int,Flat,5,Add,0,100", "asteroid.count,Int,Flat,500,Add,0,100");

        assertDiagnostic(diagnosticsOf(NodeContentFixture.withStats(stats)), "UpgradeStats 3행",
                "DefaultValue(500)가 Min·Max [0, 100] 밖이다.");
    }

    @Test
    void intStatMustHaveWholeNumbers() {
        String stats = NodeContentFixture.STATS.replace("asteroid.count,Int,Flat,5,Add", "asteroid.count,Int,Flat,5.5,Add");

        assertDiagnostic(diagnosticsOf(NodeContentFixture.withStats(stats)), "UpgradeStats 3행",
                "Int 수치는 DefaultValue·Min·Max가 정수여야 한다.");
    }

    // ---------- 2. 노드 ----------

    @Test
    void duplicateNodeIdIsReported() {
        String nodes = NodeContentFixture.NODES + "timer-01,2\n";

        assertDiagnostic(diagnosticsOf(NodeContentFixture.withNodes(nodes)), "Nodes 5행", "NodeId 'timer-01'가 Nodes 2행에도 있다.");
    }

    @Test
    void rankCountBelowOneIsReportedWithoutCascadingErrors() {
        String nodes = NodeContentFixture.NODES.replace("speed-01,2", "speed-01,0");

        List<ContentDiagnostic> diagnostics = diagnosticsOf(NodeContentFixture.withNodes(nodes));

        assertDiagnostic(diagnostics, "Nodes 3행", "'speed-01'의 RankCount는 1 이상이다. 받은 값: 0.");
        // 그 노드의 비용·효과 행마다 "없는 NodeId" 같은 연쇄 오류가 따라 나오지 않는다.
        assertThat(diagnostics).hasSize(1);
    }

    // ---------- 3. 비용 ----------

    @Test
    void costOfUnknownNodeIsReported() {
        String cost = NodeContentFixture.COST + "ghost-01,1,5\n";

        assertDiagnostic(diagnosticsOf(NodeContentFixture.withCost(cost)), "NodeCost 6행",
                "Nodes 시트에 없는 NodeId다: 'ghost-01'.");
    }

    @Test
    void rankOutsideTheNodeRankCountIsReported() {
        String cost = NodeContentFixture.COST.replace("speed-01,2,2000", "speed-01,3,2000");

        List<ContentDiagnostic> diagnostics = diagnosticsOf(NodeContentFixture.withCost(cost));

        assertDiagnostic(diagnostics, "NodeCost 4행", "'speed-01'의 Rank는 1부터 2까지다(Nodes 시트 RankCount). 받은 값: 3.");
        assertDiagnostic(diagnostics, "Nodes 3행", "'speed-01'의 Rank 2 비용이 NodeCost 시트에 없다.");
    }

    @Test
    void duplicateCostForTheSameRankIsReported() {
        String cost = NodeContentFixture.COST + "timer-01,1,9\n";

        assertDiagnostic(diagnosticsOf(NodeContentFixture.withCost(cost)), "NodeCost 6행",
                "'timer-01' Rank 1의 비용이 NodeCost 2행에도 있다.");
    }

    @Test
    void costMustBeGreaterThanZero() {
        String zero = NodeContentFixture.COST.replace("timer-01,1,2", "timer-01,1,0");
        String negative = NodeContentFixture.COST.replace("timer-01,1,2", "timer-01,1,-5");

        assertDiagnostic(diagnosticsOf(NodeContentFixture.withCost(zero)), "NodeCost 2행",
                "'timer-01' Rank 1의 비용은 0보다 커야 한다. 받은 값: 0.");
        assertDiagnostic(diagnosticsOf(NodeContentFixture.withCost(negative)), "NodeCost 2행",
                "비용은 0보다 커야 한다. 받은 값: -5.");
    }

    @Test
    void missingCostForARankIsReported() {
        String cost = NodeContentFixture.COST.replace("speed-01,2,2000,\n", "");

        assertDiagnostic(diagnosticsOf(NodeContentFixture.withCost(cost)), "Nodes 3행",
                "'speed-01'의 Rank 2 비용이 NodeCost 시트에 없다.");
    }

    // ---------- 4. 효과 ----------

    @Test
    void effectWithUnknownStatIsReportedOnce() {
        String effects = NodeContentFixture.EFFECTS.replace("breaker.speed,10,Percent", "breaker.spd,10,Percent");

        List<ContentDiagnostic> diagnostics = diagnosticsOf(NodeContentFixture.withEffects(effects));

        assertDiagnostic(diagnostics, "NodeEffects 3행", "UpgradeStats 시트에 없는 StatId다: 'breaker.spd'.");
        assertThat(diagnostics).as("\"효과가 없다\"가 겹쳐 나오지 않는다").hasSize(1);
    }

    @Test
    void effectUnitMustMatchTheStatUnit() {
        String effects = NodeContentFixture.EFFECTS.replace("breaker.speed,10,Percent", "breaker.speed,10,Flat");

        assertDiagnostic(diagnosticsOf(NodeContentFixture.withEffects(effects)), "NodeEffects 3행",
                "Unit이 수치 정의와 다르다: 'breaker.speed'는 Percent인데 Flat로 적었다.");
    }

    @Test
    void effectUnitMustBeAKnownName() {
        String effects = NodeContentFixture.EFFECTS.replace("breaker.speed,10,Percent", "breaker.speed,10,pct");

        assertDiagnostic(diagnosticsOf(NodeContentFixture.withEffects(effects)), "NodeEffects 3행",
                "Unit은 Flat·Percent 가운데 하나다. 받은 값: 'pct'.");
    }

    @Test
    void effectOnIntStatMustBeWhole() {
        String effects = NodeContentFixture.EFFECTS.replace("asteroid.count,3,Flat", "asteroid.count,3.5,Flat");

        assertDiagnostic(diagnosticsOf(NodeContentFixture.withEffects(effects)), "NodeEffects 5행",
                "'asteroid.count'는 정수 수치다. 받은 값: 3.5.");
    }

    @Test
    void effectOfUnknownNodeOrRankIsReported() {
        String effects = NodeContentFixture.EFFECTS + "ghost-01,1,timer,1,Flat\ntimer-01,2,timer,1,Flat\n";

        List<ContentDiagnostic> diagnostics = diagnosticsOf(NodeContentFixture.withEffects(effects));

        assertDiagnostic(diagnostics, "NodeEffects 6행", "Nodes 시트에 없는 NodeId다: 'ghost-01'.");
        assertDiagnostic(diagnostics, "NodeEffects 7행", "'timer-01'의 Rank는 1부터 1까지다(Nodes 시트 RankCount). 받은 값: 2.");
    }

    // ---------- 5. 빠짐 ----------

    @Test
    void missingEffectForARankIsReported() {
        String effects = NodeContentFixture.EFFECTS.replace("speed-01,2,breaker.speed,12.5,Percent,\n", "");

        assertDiagnostic(diagnosticsOf(NodeContentFixture.withEffects(effects)), "Nodes 3행",
                "'speed-01'의 Rank 2 효과가 NodeEffects 시트에 없다.");
    }

    // ---------- 부분 통과 금지 ----------

    @Test
    void allProblemsAreReportedAtOnceAndNothingPartialLoads() {
        String cost = NodeContentFixture.COST.replace("timer-01,1,2", "timer-01,1,0");
        String effects = NodeContentFixture.EFFECTS.replace("breaker.speed,10,Percent", "breaker.speed,10,Flat");
        NodeContentFiles files = new NodeContentFiles(
                NodeContentFixture.STATS, NodeContentFixture.NODES, cost, effects, NodeContentFixture.LAYOUT);

        List<ContentDiagnostic> diagnostics = diagnosticsOf(files);

        assertDiagnostic(diagnostics, "NodeCost 2행", "비용은 0보다 커야 한다.");
        assertDiagnostic(diagnostics, "NodeEffects 3행", "Unit이 수치 정의와 다르다");
        assertThat(diagnostics).hasSize(2);
    }

    @Test
    void loadExceptionListsEveryDiagnosticInItsMessage() {
        String cost = NodeContentFixture.COST + "ghost-01,1,5\n";

        try {
            NodeTreeFactory.load(NodeContentFixture.withCost(cost));
            throw new AssertionError("예외가 나야 한다.");
        } catch (ContentLoadException e) {
            assertThat(e.getMessage()).contains("노드 콘텐츠").contains("문제 1개").contains("NodeCost 6행");
        }
    }

    // ---------- 수치 값 ----------

    @Test
    void statClampsToMinAndMax() {
        UpgradeStatDefinition stat = NodeTreeFactory.load(NodeContentFixture.valid())
                .content().stat("asteroid.count").orElseThrow();

        assertThat(stat.clamp(new BigDecimal("150"))).isEqualByComparingTo("100");
        assertThat(stat.clamp(new BigDecimal("-3"))).isEqualByComparingTo("0");
        assertThat(stat.clamp(new BigDecimal("42"))).isEqualByComparingTo("42");

        UpgradeStatDefinition unbounded = NodeTreeFactory.load(NodeContentFixture.valid())
                .content().stat("breaker.speed").orElseThrow();
        assertThat(unbounded.clamp(new BigDecimal("1000000"))).isEqualByComparingTo("1000000");
    }

}
