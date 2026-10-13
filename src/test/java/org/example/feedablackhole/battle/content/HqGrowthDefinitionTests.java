package org.example.feedablackhole.battle.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 성장도마다의 시작·목표 Level과 이정표, Level 사다리(클라이언트 HqGrowthDefinition과 같은 규칙).
 */
class HqGrowthDefinitionTests {

    // Level 1~4에 닿는 누적 EXP, 이정표는 Level 2와 4.
    private static final HqGrowthDefinition GROWTH = new HqGrowthDefinition(
            List.of(10L, 30L, 60L, 100L),
            List.of(new HqMilestone(2, 1000), new HqMilestone(4, 5000)));

    @Test
    void stageZeroStartsAtLevelZeroAndAimsForTheFirstMilestone() {
        assertThat(GROWTH.startLevelAt(0)).isZero();
        assertThat(GROWTH.goalLevelAt(0)).isEqualTo(2);
        assertThat(GROWTH.nextMilestoneAt(0)).contains(new HqMilestone(2, 1000));
    }

    @Test
    void laterStagesStartAtTheLastReachedMilestone() {
        assertThat(GROWTH.startLevelAt(1)).isEqualTo(2);
        assertThat(GROWTH.goalLevelAt(1)).isEqualTo(4);
        assertThat(GROWTH.startLevelAt(2)).isEqualTo(4);
    }

    @Test
    void afterTheLastMilestoneThereIsNoGoal() {
        assertThat(GROWTH.maxStage()).isEqualTo(2);
        assertThat(GROWTH.nextMilestoneAt(2)).isEmpty();
        assertThat(GROWTH.goalLevelAt(2)).isEqualTo(HqGrowthDefinition.NO_GOAL);
    }

    @Test
    void stageOutsideTheLadderIsRejected() {
        assertThatThrownBy(() -> GROWTH.startLevelAt(3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GROWTH.startLevelAt(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expToReachCountsFromLevelZero() {
        assertThat(GROWTH.expToReach(0)).hasValue(0);
        assertThat(GROWTH.expToReach(1)).hasValue(10);
        assertThat(GROWTH.expToReach(4)).hasValue(100);
        assertThat(GROWTH.expToReach(5)).isEmpty();
    }

    @Test
    void milestoneRewardFillsUpToTheTargetAndNeverGoesNegative() {
        HqMilestone milestone = new HqMilestone(2, 1000);

        assertThat(milestone.rewardFor(300)).isEqualTo(700);
        assertThat(milestone.rewardFor(1500)).isZero();
    }

}
