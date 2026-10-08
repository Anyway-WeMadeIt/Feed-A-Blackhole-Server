package org.example.feedablackhole.node.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * 실제 콘텐츠 파일(src/main/resources/content/nodes)을 읽어 확인한다. 콘텐츠를 고친 PR은 이 테스트로 검증된다:
 * 규칙을 어기면(NodeContentLoader, NodeTreeLoader) 불러오기가 실패하고, 수치 ID 계약이 바뀌면 이 테스트가 알린다.
 */
class RealNodeContentTests {

    /**
     * 클라이언트 코드의 수치 목록(UpgradeStat enum, 시트 순서)과 같아야 하는 StatId.
     * 클라이언트는 시트에 이 수치가 모두, 한 번씩 있어야 불러오고, 서버가 내려주는 수치 ID로 전투 값을 읽는다.
     * 그래서 수치를 더하거나 이름을 바꾸는 것은 클라이언트와 함께 정해야 하는 계약 변경이다. 의도한 변경이면 이 목록을 고친다.
     */
    private static final List<String> CLIENT_STAT_IDS = List.of(
            "timer",
            "growth.time",
            "growth.asteroids",
            "growth.planets",
            "growth.stars",
            "breaker.damage",
            "breaker.radius",
            "breaker.speed",
            "breaker.critChance",
            "breaker.critBonus",
            "breaker.planetBonusDamage",
            "breaker.starBonusDamage",
            "asteroid.count",
            "asteroid.size",
            "asteroid.massScale",
            "asteroid.timeChance",
            "asteroid.respawnChance",
            "asteroid.toPlanet",
            "planet.toStar",
            "electricAsteroid.spawnChance",
            "electricAsteroid.damage",
            "electricAsteroid.critChance",
            "electricAsteroid.chain",
            "electricAsteroid.splitChance",
            "electricAsteroid.critBonus",
            "goldenAsteroid.spawnChance",
            "goldenAsteroid.rewardScale",
            "goldenAsteroid.critChance",
            "goldenAsteroid.critRewardScale",
            "moonPlanet.spawnChance",
            "moonPlanet.speedScale",
            "moonPlanet.rangeScale",
            "moonPlanet.duration",
            "moonPlanet.maxCount",
            "planet.size",
            "planet.respawnChance",
            "planet.massScale",
            "planet.timeChance",
            "comet.spawnChance",
            "comet.duration",
            "comet.critBonus",
            "comet.rainChance",
            "star.respawnChance",
            "star.timeChance",
            "star.size",
            "star.massScale",
            "electricStar.spawnChance",
            "electricStar.damage",
            "electricStar.critChance",
            "electricStar.chain",
            "electricStar.splitChance",
            "electricStar.critBonus",
            "laserStar.spawnChance",
            "laserStar.width",
            "laserStar.critChance",
            "laserStar.damage",
            "laserStar.critBonus",
            "supernovaStar.range",
            "supernovaStar.hpDamage",
            "supernovaStar.spawnChance");

    private static NodeTree tree;

    @BeforeAll
    static void loadRealContent() {
        tree = NodeTreeFactory.load(realFiles());
    }

    @Test
    void realContentPassesEveryRule() {
        // BeforeAll에서 불러오기에 성공했다는 것 자체가 검증이다(실패하면 모든 문제가 메시지에 담긴다).
        assertThat(tree).isNotNull();
    }

    @Test
    void sizesMatchTheContentThatWasImported() {
        assertThat(tree.content().stats()).hasSize(60);
        assertThat(tree.content().nodes()).hasSize(247);
        assertThat(tree.nodes()).hasSize(247);
        assertThat(tree.content().nodes().stream().mapToInt(NodeDefinition::maxRank).sum()).isEqualTo(319);
        assertThat(tree.graph().links()).hasSize(364);
        assertThat(tree.graph().nodes().stream().filter(tree.graph()::isStart)).hasSize(4);
        assertThat(tree.unplaced()).isEmpty();
    }

    @Test
    void statIdsAreExactlyTheOnesTheClientCodeKnowsInTheSameOrder() {
        assertThat(tree.content().stats()).extracting(UpgradeStatDefinition::statId)
                .containsExactlyElementsOf(CLIENT_STAT_IDS);
    }

    @Test
    void renamedStatsKeepOnlyTheirNewNames() {
        assertThat(tree.content().stat("breaker.planetBonusDamage")).isPresent();
        assertThat(tree.content().stat("breaker.starBonusDamage")).isPresent();
        assertThat(tree.content().stat("breaker.planetBonus")).isEmpty();
        assertThat(tree.content().stat("breaker.starBonus")).isEmpty();
    }

    @Test
    void knownNodesHaveTheExpectedCostsAndRanks() {
        NodeDefinition timer02 = tree.node("timer-02").orElseThrow();
        assertThat(timer02.maxRank()).isEqualTo(1);
        assertThat(timer02.rankAt(1).cost()).isEqualTo(2_600_000_000_000L);

        assertThat(tree.content().nodes().stream().filter(node -> node.maxRank() == 10)).hasSize(8);
        assertThat(tree.content().nodes().stream().filter(node -> node.maxRank() == 1)).hasSize(239);
        assertThat(tree.content().nodes().stream()
                .flatMap(node -> node.ranks().stream()).mapToLong(NodeRankDefinition::cost).max().orElseThrow())
                .isEqualTo(1_000_000_000_000_000L);
    }

    @Test
    void everyEffectUsesAnExistingStatWithTheMatchingUnit() {
        for (NodeDefinition node : tree.content().nodes()) {
            for (NodeRankDefinition rank : node.ranks()) {
                assertThat(rank.effects()).as("%s Rank %d", node.id(), rank.rank()).isNotEmpty();
                for (NodeEffect effect : rank.effects()) {
                    assertThat(tree.content().stat(effect.statId())).as(node.id()).isPresent();
                }
            }
        }
    }

    @Test
    void nodeContentVersionIsStableAcrossLoads() {
        NodeTree again = NodeTreeFactory.load(realFiles());

        assertThat(again.nodeContentVersion()).isEqualTo(tree.nodeContentVersion()).matches("[0-9a-f]{16}");
    }

    @Test
    void columnsAfterMemoAndWindowsLineEndingsDoNotChangeTheRealContent() {
        NodeContentFiles real = realFiles();
        NodeContentFiles decorated = new NodeContentFiles(
                decorate(real.statsCsv()), decorate(real.nodesCsv()), decorate(real.costCsv()), decorate(real.effectsCsv()),
                real.layoutJson());

        NodeTree other = NodeTreeFactory.load(decorated);

        assertThat(other.nodeContentVersion()).isEqualTo(tree.nodeContentVersion());
        assertThat(other.content().stats()).isEqualTo(tree.content().stats());
        assertThat(other.content().nodes()).isEqualTo(tree.content().nodes());
    }

    @Test
    void everySheetHasTheRequiredColumnsAndAMemoColumn() {
        // 정리된 형식: 필수 칸 + Memo. Memo 오른쪽에는 칸을 더해도 된다(위 테스트가 보장한다).
        assertThat(firstLine(realFiles().statsCsv())).startsWith("StatId,ValueType,Unit,DefaultValue,Aggregation,Min,Max,Memo");
        assertThat(firstLine(realFiles().nodesCsv())).startsWith("NodeId,RankCount,Memo");
        assertThat(firstLine(realFiles().costCsv())).startsWith("NodeId,Rank,Cost,Memo");
        assertThat(firstLine(realFiles().effectsCsv())).startsWith("NodeId,Rank,StatId,Value,Unit,Memo");
    }

    // 머리칸 끝에 이름이 겹치는 칸을, 각 행 끝에 쓰레기 값을 붙이고 줄바꿈을 CRLF로 바꾼다.
    private static String decorate(String csv) {
        String[] lines = csv.split("\n");
        StringBuilder out = new StringBuilder(lines[0]).append(",분석,NodeId,Cost\r\n");
        for (int i = 1; i < lines.length; i++) {
            out.append(lines[i]).append(",값,x,x\r\n");
        }
        return out.toString();
    }

    private static String firstLine(String text) {
        return text.substring(0, text.indexOf('\n'));
    }

    private static NodeContentFiles realFiles() {
        return new NodeContentFiles(
                read(NodeContentFiles.STATS_FILE),
                read(NodeContentFiles.NODES_FILE),
                read(NodeContentFiles.COST_FILE),
                read(NodeContentFiles.EFFECTS_FILE),
                read(NodeContentFiles.LAYOUT_FILE));
    }

    private static String read(String name) {
        try {
            return new ClassPathResource("content/nodes/" + name).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
