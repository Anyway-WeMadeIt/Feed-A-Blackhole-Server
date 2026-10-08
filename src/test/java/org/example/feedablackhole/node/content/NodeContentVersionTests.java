package org.example.feedablackhole.node.content;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 콘텐츠 버전은 파일의 글자가 아니라 불러온 의미로 계산한다. 의미가 같은 편집은 버전을 바꾸지 않고, 의미가 달라지면 바꾼다.
 */
class NodeContentVersionTests {

    private static final String BASE = NodeTreeFactory.load(NodeContentFixture.valid()).nodeContentVersion();

    private static String versionOf(NodeContentFiles files) {
        return NodeTreeFactory.load(files).nodeContentVersion();
    }

    @Test
    void versionIsSixteenHexCharacters() {
        assertThat(BASE).matches("[0-9a-f]{16}");
    }

    @Test
    void sameContentGivesTheSameVersionEveryTime() {
        assertThat(versionOf(NodeContentFixture.valid())).isEqualTo(BASE);
    }

    // ---------- 의미가 같으면 버전도 같다 ----------

    @Test
    void editingMemosDoesNotChangeTheVersion() {
        String stats = NodeContentFixture.STATS.replace("세션 타이머", "수정한 메모, 쉼표 포함");
        String nodes = NodeContentFixture.NODES.replace("timer-01,1,", "timer-01,1,새 메모");

        assertThat(versionOf(NodeContentFixture.withStats(stats))).isEqualTo(BASE);
        assertThat(versionOf(NodeContentFixture.withNodes(nodes))).isEqualTo(BASE);
    }

    @Test
    void addingColumnsAfterMemoDoesNotChangeTheVersion() {
        assertThat(versionOf(NodeContentFixture.mapSheets(NodeContentFixture::withColumnsAfterMemo))).isEqualTo(BASE);
    }

    @Test
    void lineEndingsBomAndBlankLinesDoNotChangeTheVersion() {
        NodeContentFiles windows = NodeContentFixture.mapSheets(csv -> "\uFEFF" + csv.replace("\n", "\r\n") + "\r\n\r\n");

        assertThat(versionOf(windows)).isEqualTo(BASE);
    }

    @Test
    void anotherSpellingOfTheSameNumberDoesNotChangeTheVersion() {
        String effects = NodeContentFixture.EFFECTS.replace("breaker.speed,12.5,", "breaker.speed,12.50,")
                .replace("breaker.speed,10,", "breaker.speed,10.0,");
        String stats = NodeContentFixture.STATS.replace("timer,Float,Flat,12,", "timer,Float,Flat,12.000,");

        assertThat(versionOf(NodeContentFixture.withEffects(effects))).isEqualTo(BASE);
        assertThat(versionOf(NodeContentFixture.withStats(stats))).isEqualTo(BASE);
    }

    @Test
    void layoutFormattingAndWhichSideALinkIsWrittenOnDoNotChangeTheVersion() {
        String sameMeaning = """
                {"Nodes":[{"Id":"timer-01","Start":true,"X":0,"Y":0},
                {"Id":"speed-01","X":1,"Y":0,"Links":["timer-01","count-01"],"Memo":"무시"},
                {"Id":"count-01","X":2,"Y":0}]}""";

        assertThat(versionOf(NodeContentFixture.withLayout(sameMeaning))).isEqualTo(BASE);
    }

    // ---------- 의미가 달라지면 버전도 달라진다 ----------

    @Test
    void changingACostEffectOrStatDefinitionChangesTheVersion() {
        assertThat(versionOf(NodeContentFixture.withCost(NodeContentFixture.COST.replace("timer-01,1,2,", "timer-01,1,3,"))))
                .isNotEqualTo(BASE);
        assertThat(versionOf(NodeContentFixture.withEffects(
                NodeContentFixture.EFFECTS.replace("breaker.speed,12.5,", "breaker.speed,12.6,")))).isNotEqualTo(BASE);
        assertThat(versionOf(NodeContentFixture.withStats(
                NodeContentFixture.STATS.replace("asteroid.count,Int,Flat,5,Add,0,100", "asteroid.count,Int,Flat,5,Add,0,99"))))
                .isNotEqualTo(BASE);
        assertThat(versionOf(NodeContentFixture.withStats(
                NodeContentFixture.STATS.replace("breaker.speed,Float,Percent,100,Add,0,,", "breaker.speed,Float,Percent,100,Add,0,500,"))))
                .as("제한 없음이던 Max를 정함").isNotEqualTo(BASE);
    }

    @Test
    void changingTheLayoutChangesTheVersion() {
        String moved = NodeContentFixture.LAYOUT.replace("\"X\": 2, \"Y\": 0", "\"X\": 2, \"Y\": 1");
        String extraLink = NodeContentFixture.LAYOUT.replace(
                "\"X\": 2, \"Y\": 0, \"Links\": [\"speed-01\"]", "\"X\": 2, \"Y\": 0, \"Links\": [\"speed-01\", \"timer-01\"]");
        String secondStart = NodeContentFixture.LAYOUT.replace("\"Id\": \"speed-01\", \"Start\": false", "\"Id\": \"speed-01\", \"Start\": true");

        assertThat(versionOf(NodeContentFixture.withLayout(moved))).isNotEqualTo(BASE);
        assertThat(versionOf(NodeContentFixture.withLayout(extraLink))).isNotEqualTo(BASE);
        assertThat(versionOf(NodeContentFixture.withLayout(secondStart))).isNotEqualTo(BASE);
    }

    @Test
    void changingTheNodeOrderChangesTheVersion() {
        String reordered = """
                NodeId,RankCount,Memo
                speed-01,2,
                timer-01,1,
                count-01,1,
                """;

        assertThat(versionOf(NodeContentFixture.withNodes(reordered))).isNotEqualTo(BASE);
    }

}
