package org.example.feedablackhole.battle.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.example.feedablackhole.battle.content.BattleContent;
import org.example.feedablackhole.battle.content.ContentBundle;
import org.example.feedablackhole.battle.content.ContentBundleLoader;
import org.example.feedablackhole.battle.content.EnemyKindDefinition;
import org.example.feedablackhole.battle.content.EnemyStatIds;
import org.example.feedablackhole.battle.content.EnemyTier;
import org.example.feedablackhole.battle.content.GoldenTrait;
import org.example.feedablackhole.node.content.NodeDefinition;
import org.example.feedablackhole.node.content.NodeTree;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * KindComposition이 산 노드로 적 종류의 판 구성(최대 크기, 나올 수 있는 색, 황금, Gold·EXP)을 올바르게 계산하는지 확인한다.
 * 서버가 쓰는 실제 콘텐츠 묶음(src/main/resources/content)으로 검사하고, 마지막 테스트만 직접 만든 종류를 쓴다.
 */
class KindCompositionTests {

    // 클래스패스 기준 실제 콘텐츠 위치(src/main/resources 아래)
    private static final String CONTENT = "/content/";

    private static NodeTree tree;
    private static BattleContent battle;

    @BeforeAll
    static void load() {
        ContentBundle bundle = ContentBundleLoader.load(KindCompositionTests::bytes);
        tree = bundle.nodeTree();
        battle = bundle.battle();
    }

    // 노드를 하나도 사지 않은 소행성: 크기 1, 첫 색만, 황금 없음, 색의 Gold·EXP 그대로
    @Test
    void asteroidWithoutNodes() {
        KindComposition asteroid = compose("asteroid", Map.of());

        assertThat(asteroid.maxSize()).isEqualTo(1);
        assertThat(asteroid.tierPossible(0)).isTrue();
        assertThat(asteroid.tierPossible(1)).isFalse();
        assertThat(asteroid.goldenPossible()).isFalse();
        assertThat(asteroid.timeChance()).isZero();
        assertThat(asteroid.goldOf(0, 1, false)).isEqualTo(1);
        assertThat(asteroid.goldOf(5, 1, false)).isEqualTo(7800);
        assertThat(asteroid.expOf(5, 1)).isEqualTo(243);
    }

    // 행성은 황금 성질이 없다. 혜성은 픽업이다
    @Test
    void planetAndCometWithoutNodes() {
        KindComposition planet = compose("planet", Map.of());
        assertThat(planet.golden()).isEmpty();
        assertThat(planet.goldOf(0, 1, false)).isEqualTo(290_000);

        KindComposition comet = compose("comet", Map.of());
        assertThat(comet.pickup()).isTrue();
        assertThat(comet.maxSize()).isEqualTo(1);
        assertThat(comet.tierPossible(0)).isTrue();
    }

    // 노드를 사면 크기, 색, 황금, 시간 추가 확률이 바뀌고 Gold·EXP가 그에 맞게 계산된다
    @Test
    void boughtNodesChangeTheComposition() {
        KindComposition asteroid = compose("asteroid", Map.of(
                "goldenAsteroid.spawnChance-01", 1,   // 황금 확률 +10%
                "goldenAsteroid.rewardScale-01", 1,   // 황금 배율 +75 (50 + 75 / 100 = 50.75)
                "asteroid.size-01", 1,                // 크기 +1
                "asteroid.size-02", 1,                // 크기 +1
                "asteroid.timeChance-01", 1,          // 시간 추가 확률 +5%
                "asteroid.massScale-01", 1));         // 질량 +40% (140%)

        assertThat(asteroid.maxSize()).isEqualTo(3);
        assertThat(asteroid.tierPossible(1)).isTrue();   // 질량 140%면 색 1도 나온다
        assertThat(asteroid.tierPossible(2)).isFalse();
        assertThat(asteroid.goldenPossible()).isTrue();
        assertThat(asteroid.golden().orElseThrow().multiplier()).isEqualTo(50.75f);
        assertThat(asteroid.timeChance()).isEqualTo(0.05f);

        assertThat(asteroid.goldOf(0, 1, true)).isEqualTo(51);     // 1 × 50.75 = 50.75 -> 51
        assertThat(asteroid.goldOf(1, 1, true)).isEqualTo(305);    // 6 × 50.75 = 304.5 -> 305 (.5는 올림)
        assertThat(asteroid.goldOf(1, 3, false)).isEqualTo(18);    // 6 × 크기 배율 3
        assertThat(asteroid.expOf(1, 3)).isEqualTo(9);             // 3 × 크기 배율 3
    }

    // 범위 밖 입력은 IllegalArgumentException이다
    @Test
    void outOfRangeInputIsRejected() {
        KindComposition asteroid = compose("asteroid", Map.of());

        assertThatThrownBy(() -> asteroid.goldOf(0, 1, true)).isInstanceOf(IllegalArgumentException.class); // 황금 불가
        assertThatThrownBy(() -> asteroid.goldOf(0, 2, false)).isInstanceOf(IllegalArgumentException.class); // 최대 크기 1
        assertThatThrownBy(() -> asteroid.goldOf(6, 1, false)).isInstanceOf(IllegalArgumentException.class); // 색은 0~5
        assertThatThrownBy(() -> asteroid.tierPossible(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    // 모든 노드를 마지막 Rank까지 사도 모든 종류의 구성이 만들어진다(클라이언트 UpgradeContentCheck와 같은 확인)
    @Test
    void everyKindCanBeComposedWhenEveryNodeIsBought() {
        Map<String, Integer> everything = new HashMap<>();
        for (NodeDefinition node : tree.nodes()) {
            everything.put(node.id(), node.maxRank());
        }

        for (EnemyKindDefinition kind : battle.enemies()) {
            assertThat(KindComposition.from(kind, NodeUpgradeValues.of(tree, everything)).maxSize())
                    .as(kind.enemyId()).isBetween(SizeRule.BASE, SizeRule.MAX);
        }
    }

    /**
     * 황금 치명 보너스는 gold × 치명 배율을 float으로 곱한다(클라이언트의 long * float).
     * Gold 16,777,217에 1.1f를 곱하면 float 곱셈은 18,454,938, double 곱셈은 18,454,939로 갈라진다.
     */
    @Test
    void goldenCritBonusMultipliesInFloat() {
        EnemyKindDefinition kind = new EnemyKindDefinition("test", false, null,
                List.of(new EnemyTier(16_777_217L, 1)),
                List.of(EnemyKindDefinition.GOLDEN),
                new GoldenTrait(1f, 0f, 1.1f),
                new EnemyStatIds(null, null, null, null, null, null, Map.of("golden", "chance"), null, null, null));
        KindComposition composition = KindComposition.from(kind, new FixedGains(Map.of("chance", 10f)));

        assertThat(composition.goldenCritBonusOf(0, 1)).isEqualTo(18_454_938L);
    }

    private static KindComposition compose(String enemyId, Map<String, Integer> nodeRanks) {
        return KindComposition.from(battle.enemy(enemyId), NodeUpgradeValues.of(tree, nodeRanks));
    }

    // 실제 콘텐츠 폴더에서 파일 바이트를 읽는다. 없으면 null
    private static byte[] bytes(String path) {
        try (InputStream in = KindCompositionTests.class.getResourceAsStream(CONTENT + path)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
