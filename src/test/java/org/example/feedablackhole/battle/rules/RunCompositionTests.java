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
import org.example.feedablackhole.battle.content.HqMilestone;
import org.example.feedablackhole.battle.content.SupplyEntry;
import org.example.feedablackhole.node.content.NodeDefinition;
import org.example.feedablackhole.node.content.NodeTree;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * RunComposition이 성장도와 산 노드로 판 설정을 올바르게 계산하는지 확인하는 테스트.
 * 서버가 쓰는 실제 콘텐츠 묶음(src/main/resources/content)으로 검사한다.
 */
class RunCompositionTests {

    // 클래스패스 기준 실제 콘텐츠 위치(src/main/resources 아래)
    private static final String CONTENT = "/content/";

    private static NodeTree tree;
    private static BattleContent battle;

    @BeforeAll
    static void load() {
        ContentBundle bundle = ContentBundleLoader.load(RunCompositionTests::bytes);
        tree = bundle.nodeTree();
        battle = bundle.battle();
    }

    // 성장도 0: Level 0에서 시작해 첫 이정표(Level 10)를 노린다
    @Test
    void stageZeroStartsAtLevelZeroAndAimsForTheFirstMilestone() {
        RunComposition run = setup(0);

        assertThat(run.growthStage()).isZero();
        assertThat(run.startLevel()).isZero();
        assertThat(run.goalLevel()).isEqualTo(10);
        assertThat(run.startExp()).isZero();
        assertThat(run.nextMilestone()).contains(new HqMilestone(10, 690_000L));
    }

    // 성장도 1: 첫 이정표의 Level에서 시작하고, 그 Level에 닿는 누적 EXP부터 센다
    @Test
    void laterStagesStartAtTheLastReachedMilestone() {
        RunComposition run = setup(1);

        assertThat(run.startLevel()).isEqualTo(10);
        assertThat(run.goalLevel()).isEqualTo(20);
        assertThat(run.startExp()).isEqualTo(250_000L);
        assertThat(run.nextMilestone()).contains(new HqMilestone(20, 5_600_000L));
    }

    // 마지막 성장도(이정표 4개를 모두 지난 뒤): 목표가 없고 다음 이정표도 없다
    @Test
    void theLastStageHasNoGoal() {
        RunComposition run = setup(4);

        assertThat(run.startLevel()).isEqualTo(40);
        assertThat(run.goalLevel()).isZero();
        assertThat(run.startExp()).isEqualTo(190_000_000_000L);
        assertThat(run.nextMilestone()).isEmpty();
    }

    // 성장도가 0 ~ 이정표 수 밖이면 IllegalArgumentException
    @Test
    void stageOutsideTheRangeIsRejected() {
        assertThatThrownBy(() -> setup(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> setup(5)).isInstanceOf(IllegalArgumentException.class);
    }

    // 노드를 사지 않은 판 설정을 만든다
    private static RunComposition setup(int growthStage) {
        return RunComposition.of(battle, growthStage, NodeUpgradeValues.of(tree, Map.of()));
    }

    // 실제 콘텐츠 폴더에서 파일 바이트를 읽는다. 없으면 null
    private static byte[] bytes(String path) {
        try (InputStream in = RunCompositionTests.class.getResourceAsStream(CONTENT + path)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // 노드를 사지 않은 성장도 0: 제한 시간 12초, 성장 시간 0, 처치 시간 보너스 0.3초
    @Test
    void timesWithoutNodes() {
        RunComposition run = setup(0);

        assertThat(run.timeLimitSeconds()).isEqualTo(12f);
        assertThat(run.growthTimeSeconds()).isZero();
        assertThat(run.killTimeBonusSeconds()).isEqualTo(0.3f);
    }

    // timer 노드를 사면 제한 시간이 늘어난다(timer-01은 +2초, timer-02는 +10초)
    @Test
    void timerNodesExtendTheTimeLimit() {
        RunComposition one = RunComposition.of(battle, 0, NodeUpgradeValues.of(tree, Map.of("timer-01", 1)));
        RunComposition two = RunComposition.of(battle, 0,
                NodeUpgradeValues.of(tree, Map.of("timer-01", 1, "timer-02", 1)));

        assertThat(one.timeLimitSeconds()).isEqualTo(14f);
        assertThat(two.timeLimitSeconds()).isEqualTo(24f);
    }

    // 성장 시간 노드를 사면 Level업마다 더하는 초가 생긴다(growth.time-01은 +3초)
    @Test
    void growthTimeNodeAddsSecondsPerLevel() {
        RunComposition run = RunComposition.of(battle, 0, NodeUpgradeValues.of(tree, Map.of("growth.time-01", 1)));

        assertThat(run.growthTimeSeconds()).isEqualTo(3f);
    }

    // 제한 시간은 노드 조합으로 0 이하가 되면 안 된다(0도 안 된다)
    @Test
    void timeLimitMustStayPositive() {
        assertThatThrownBy(() -> RunComposition.of(battle, 0, new FixedGains(Map.of("timer", -12f))))
                .isInstanceOf(IllegalArgumentException.class);   // 12 - 12 = 0
        assertThatThrownBy(() -> RunComposition.of(battle, 0, new FixedGains(Map.of("timer", -13f))))
                .isInstanceOf(IllegalArgumentException.class);   // 12 - 13 = -1
        assertThatThrownBy(() -> RunComposition.of(battle, 0, new FixedGains(Map.of("timer", Float.NaN))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // 성장 시간은 음수가 되면 안 된다(0은 괜찮다)
    @Test
    void growthTimeMustNotBeNegative() {
        assertThatThrownBy(() -> RunComposition.of(battle, 0, new FixedGains(Map.of("growth.time", -1f))))
                .isInstanceOf(IllegalArgumentException.class);

        // 노드를 사지 않으면 성장 시간은 0이고, 0은 허용된다
        assertThat(setup(0).growthTimeSeconds()).isZero();
    }

    // 성장도 0: 임계값에서 Level이 오르고(같으면 오른다), 목표(Level 10)에서 멈춘다
    @Test
    void levelRisesAtEachThresholdAndStopsAtTheGoal() {
        RunComposition run = setup(0);

        assertThat(run.levelForExp(0)).isZero();
        assertThat(run.levelForExp(79)).isZero();          // Level 1 임계값(80) 직전
        assertThat(run.levelForExp(80)).isEqualTo(1);      // 임계값과 같으면 오른다
        assertThat(run.levelForExp(349)).isEqualTo(1);     // Level 2 임계값(350) 직전
        assertThat(run.levelForExp(350)).isEqualTo(2);
        assertThat(run.levelForExp(Long.MAX_VALUE)).isEqualTo(10); // 목표 Level 10을 넘지 않는다
    }

    // 성장도 1: 시작 EXP부터 세고, 목표(Level 20)에서 멈춘다. 시작 EXP보다 작아도 시작 Level이다
    @Test
    void laterStagesCountFromTheirStartExp() {
        RunComposition run = setup(1);

        assertThat(run.levelForExp(0)).isEqualTo(10);          // 시작 EXP(250,000)보다 작아도 오류가 아니다
        assertThat(run.levelForExp(250_000)).isEqualTo(10);
        assertThat(run.levelForExp(289_999)).isEqualTo(10);    // Level 11 임계값(290,000) 직전
        assertThat(run.levelForExp(290_000)).isEqualTo(11);
        assertThat(run.levelForExp(Long.MAX_VALUE)).isEqualTo(20);
    }

    // 마지막 성장도(목표 없음): Level 사다리의 끝(Level 42)에서 멈춘다
    @Test
    void withoutAGoalTheLevelStopsAtTheEndOfTheLadder() {
        RunComposition run = setup(4);

        assertThat(run.levelForExp(1_050_000_000_000L - 1)).isEqualTo(41); // Level 42 임계값 직전
        assertThat(run.levelForExp(1_050_000_000_000L)).isEqualTo(42);
        assertThat(run.levelForExp(Long.MAX_VALUE)).isEqualTo(42);
    }

    // 판 설정은 모든 적 종류의 구성을 이름으로 꺼내 준다
    @Test
    void everyKindCanBeFoundByName() {
        RunComposition run = setup(0);

        for (EnemyKindDefinition kind : battle.enemies()) {
            assertThat(run.kind(kind.enemyId()).enemyId()).isEqualTo(kind.enemyId());
            assertThat(run.findKind(kind.enemyId())).isPresent();
        }
        assertThat(run.kind("asteroid").maxSize()).isEqualTo(1);
    }

    // 없는 종류: findKind는 비어 있고, kind는 IllegalArgumentException
    @Test
    void unknownKindIsRejected() {
        RunComposition run = setup(0);

        assertThat(run.findKind("nebula")).isEmpty();
        assertThatThrownBy(() -> run.kind("nebula")).isInstanceOf(IllegalArgumentException.class);
    }

    // 산 노드가 종류의 구성에 반영된다(소행성 크기 노드 2개 → 최대 크기 3)
    @Test
    void boughtNodesReachTheKindSetup() {
        RunComposition run = RunComposition.of(battle, 0,
                NodeUpgradeValues.of(tree, Map.of("asteroid.size-01", 1, "asteroid.size-02", 1)));

        assertThat(run.kind("asteroid").maxSize()).isEqualTo(3);
    }

    // 모든 노드를 마지막 Rank까지 사도, 어느 성장도에서나 판 설정이 만들어진다(클라이언트 UpgradeContentCheck와 같은 확인)
    @Test
    void everyStageCanBeComposedWhenEveryNodeIsBought() {
        Map<String, Integer> everything = new HashMap<>();
        for (NodeDefinition node : tree.nodes()) {
            everything.put(node.id(), node.maxRank());
        }

        for (int stage = 0; stage <= battle.growth().maxStage(); stage++) {
            RunComposition run = RunComposition.of(battle, stage, NodeUpgradeValues.of(tree, everything));
            assertThat(run.timeLimitSeconds()).as("성장도 %d", stage).isPositive();
        }
    }

    // 노드를 사지 않으면 콘텐츠의 시작 적(소행성 12마리)만 나온다
    @Test
    void startSupplyWithoutNodes() {
        assertThat(setup(0).startSupply()).containsExactly(new SupplyEntry("asteroid", 12));
    }

    // 시작 수 노드는 소행성 수를 늘린다(asteroid.count-01은 +18)
    @Test
    void startSupplyBonusAddsToTheContentSupply() {
        assertThat(supplyWith("asteroid.count-01"))
                .containsExactly(new SupplyEntry("asteroid", 30));
    }

    // 변환 수만큼 다음 종류로 옮긴다(asteroid.toPlanet-02는 +5)
    @Test
    void upgradeMovesKindsToTheNextKind() {
        assertThat(supplyWith("asteroid.count-01", "asteroid.toPlanet-02"))
                .containsExactly(new SupplyEntry("asteroid", 25), new SupplyEntry("planet", 5));
    }

    // 가진 수보다 많이 옮기지 않고, 0마리가 된 종류는 빠진다(asteroid.toPlanet-01은 +52, 소행성은 30마리뿐)
    @Test
    void upgradeNeverMovesMoreThanWhatIsThere() {
        assertThat(supplyWith("asteroid.count-01", "asteroid.toPlanet-01"))
                .containsExactly(new SupplyEntry("planet", 30));
    }

    // 앞 종류에서 바뀐 수까지 센다: 행성은 콘텐츠에 없지만 소행성에서 바뀐 5마리를 전부 항성으로 옮긴다
    @Test
    void upgradeCountsKindsThatWereJustConverted() {
        assertThat(supplyWith("asteroid.toPlanet-02", "planet.toStar-01"))
                .containsExactly(new SupplyEntry("asteroid", 7), new SupplyEntry("star", 5));
    }

    // 시작 수, 변환 둘 다 있고 체인이 이어진다. 종류는 처음 나온 순서(소행성, 행성, 항성)다
    @Test
    void chainedUpgradesKeepTheFirstSeenOrder() {
        assertThat(supplyWith("asteroid.count-01", "asteroid.toPlanet-02", "planet.toStar-02"))
                .containsExactly(new SupplyEntry("asteroid", 25), new SupplyEntry("planet", 3),
                        new SupplyEntry("star", 2));
    }

    // 노드를 Rank 1씩 산 판 설정의 시작 공급
    private static List<SupplyEntry> supplyWith(String... nodeIds) {
        Map<String, Integer> ranks = new HashMap<>();
        for (String nodeId : nodeIds) {
            ranks.put(nodeId, 1);
        }
        return RunComposition.of(battle, 0, NodeUpgradeValues.of(tree, ranks)).startSupply();
    }
}
