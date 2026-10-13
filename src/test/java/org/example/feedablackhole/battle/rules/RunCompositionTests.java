package org.example.feedablackhole.battle.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import org.example.feedablackhole.battle.content.BattleContent;
import org.example.feedablackhole.battle.content.ContentBundle;
import org.example.feedablackhole.battle.content.ContentBundleLoader;
import org.example.feedablackhole.battle.content.HqMilestone;
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

        assertThat(RunComposition.of(battle, 0, new FixedGains(Map.of())).growthTimeSeconds()).isZero();
    }
}
