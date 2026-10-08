package org.example.feedablackhole.node.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.feedablackhole.node.content.NodeContentFixture.assertDiagnostic;
import static org.example.feedablackhole.node.content.NodeContentFixture.diagnosticsOf;

import org.junit.jupiter.api.Test;

/**
 * 시트의 칸 규칙: 필수 칸만 이름으로 찾아 읽고, Memo와 그 오른쪽 칸은 읽는 결과에 영향이 없다.
 */
class NodeContentColumnTests {

    private final NodeTree base = NodeTreeFactory.load(NodeContentFixture.valid());

    @Test
    void columnsAfterMemoNeverChangeTheResult() {
        NodeTree extra = NodeTreeFactory.load(NodeContentFixture.mapSheets(NodeContentFixture::withColumnsAfterMemo));

        assertThat(extra.content().stats()).isEqualTo(base.content().stats());
        assertThat(extra.content().nodes()).isEqualTo(base.content().nodes());
        assertThat(extra.placements()).isEqualTo(base.placements());
        assertThat(extra.nodeContentVersion()).as("의미가 같으면 버전도 같다").isEqualTo(base.nodeContentVersion());
    }

    @Test
    void requiredColumnNamesRepeatedAfterMemoDoNotReplaceTheFirstOnes() {
        // withColumnsAfterMemo는 NodeId·Rank·Cost·Value·StatId 머리칸을 뒤에 한 번 더 두고 쓰레기 값을 채운다.
        NodeTree extra = NodeTreeFactory.load(NodeContentFixture.withCost(
                NodeContentFixture.withColumnsAfterMemo(NodeContentFixture.COST)));

        assertThat(extra.node("count-01").orElseThrow().rankAt(1).cost()).isEqualTo(2_600_000_000_000L);
    }

    @Test
    void memoColumnIsOptionalInEverySheet() {
        NodeTree withoutMemo = NodeTreeFactory.load(NodeContentFixture.mapSheets(NodeContentFixture::withoutMemoColumn));

        assertThat(withoutMemo.content().stats()).isEqualTo(base.content().stats());
        assertThat(withoutMemo.content().nodes()).isEqualTo(base.content().nodes());
        assertThat(withoutMemo.nodeContentVersion()).isEqualTo(base.nodeContentVersion());
    }

    @Test
    void memoMayHoldAnything() {
        String richMemo = NodeContentFixture.COST
                .replace("timer-01,1,2,", "timer-01,1,2,\"쉼표, 따옴표 \"\"인용\"\", 줄\n바꿈\"");

        assertThat(NodeTreeFactory.load(NodeContentFixture.withCost(richMemo)).nodeContentVersion())
                .isEqualTo(base.nodeContentVersion());
    }

    @Test
    void rowsShorterThanTheHeaderAreReadAsEmptyCells() {
        String shortRows = NodeContentFixture.NODES.replace("timer-01,1,", "timer-01,1").replace("count-01,1,", "count-01,1");

        assertThat(NodeTreeFactory.load(NodeContentFixture.withNodes(shortRows)).nodeContentVersion())
                .isEqualTo(base.nodeContentVersion());
    }

    @Test
    void headerNamesAreEnglishAndCaseSensitive() {
        // 옛 한글 머리칸은 이제 필수 칸으로 읽히지 않는다. 메시지는 기대하는 영문 이름을 알려 준다.
        String legacyNodes = NodeContentFixture.NODES.replace("RankCount", "Rank 수");
        String legacyEffects = NodeContentFixture.EFFECTS.replace(",Unit,", ",단위,");
        String lowerCase = NodeContentFixture.COST.replace("NodeId,Rank,Cost", "nodeid,Rank,Cost");

        assertDiagnostic(diagnosticsOf(NodeContentFixture.withNodes(legacyNodes)), "Nodes 1행", "머리칸 'RankCount'이 없다.");
        assertDiagnostic(diagnosticsOf(NodeContentFixture.withEffects(legacyEffects)), "NodeEffects 1행", "머리칸 'Unit'이 없다.");
        assertDiagnostic(diagnosticsOf(NodeContentFixture.withCost(lowerCase)), "NodeCost 1행", "머리칸 'NodeId'이 없다.");
    }

    @Test
    void aLegacyEnabledColumnIsJustAnExtraColumn() {
        String legacy = NodeContentFixture.STATS
                .replace("Max,Memo", "Max,Enabled,Memo")
                .replace("timer,Float,Flat,12,Add,0,,", "timer,Float,Flat,12,Add,0,,FALSE,")
                .replace("asteroid.count,Int,Flat,5,Add,0,100,", "asteroid.count,Int,Flat,5,Add,0,100,TRUE,")
                .replace("breaker.speed,Float,Percent,100,Add,0,,", "breaker.speed,Float,Percent,100,Add,0,,not-a-boolean,");

        assertThat(NodeTreeFactory.load(NodeContentFixture.withStats(legacy)).nodeContentVersion())
                .isEqualTo(base.nodeContentVersion());
    }

}
