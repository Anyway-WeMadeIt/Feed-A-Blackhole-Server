package org.example.feedablackhole.battle.content;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 블랙홀 성장(클라이언트 HqGrowthDefinition): 모든 판이 함께 쓰는 Level 사다리와 이정표.
 * 성장도(stage)는 도달한 이정표의 수(0 ~ maxStage)다.
 *
 * @param levelExp   levelExp[i]는 Level i + 1에 닿는 누적 EXP. Level 0은 EXP 0이다
 * @param milestones 이정표(Level이 커지는 순서). milestones[s]가 성장도 s의 판이 노리는 이정표다
 * @param galaxyStage 은하가 나오기 시작하는 성장도. 0이면 은하가 없다
 */
public record HqGrowthDefinition(List<Long> levelExp, List<HqMilestone> milestones, int galaxyStage) {

    public static final int START_LEVEL = 0;
    public static final int NO_GOAL = 0;
    public static final int NO_GALAXY = -1;

    public HqGrowthDefinition {
        levelExp = List.copyOf(levelExp);
        milestones = List.copyOf(milestones);
    }

    /** 은하가 없는 성장(galaxyStage 0). */
    public HqGrowthDefinition(List<Long> levelExp, List<HqMilestone> milestones) {
        this(levelExp, milestones, 0);
    }

    public int maxStage() {
        return milestones.size();
    }

    public int maxLevel() {
        return START_LEVEL + levelExp.size();
    }

    /**
     * 성장도 stage의 판에 나오는 은하의 색 등급(0부터, 이정표마다 +1). 은하가 없는 판이면 NO_GALAXY.
     * 색 수를 넘는 경우의 처리는 은하 종류의 색 수를 아는 쪽(KindComposition)이 맡는다.
     */
    public int galaxyTierAt(int stage) {
        requireStage(stage);
        return galaxyStage > 0 && stage >= galaxyStage ? stage - galaxyStage : NO_GALAXY;
    }

    /** 성장도 stage의 판이 시작하는 Level: 성장도 0이면 0, 아니면 마지막으로 도달한 이정표의 Level. */
    public int startLevelAt(int stage) {
        requireStage(stage);
        return stage == 0 ? START_LEVEL : milestones.get(stage - 1).level();
    }

    /** 성장도 stage의 판이 노리는 이정표. 마지막 이정표 뒤면 비어 있다. */
    public Optional<HqMilestone> nextMilestoneAt(int stage) {
        requireStage(stage);
        return stage < maxStage() ? Optional.of(milestones.get(stage)) : Optional.empty();
    }

    /** 성장도 stage의 판의 목표 Level(다음 이정표의 Level). 없으면 NO_GOAL. */
    public int goalLevelAt(int stage) {
        return nextMilestoneAt(stage).map(HqMilestone::level).orElse(NO_GOAL);
    }

    /** 이 Level에 닿는 누적 EXP. Level 0은 0이고, 사다리 밖이면 비어 있다. */
    public OptionalLong expToReach(int level) {
        if (level == START_LEVEL) {
            return OptionalLong.of(0);
        }
        int index = level - START_LEVEL - 1;
        return index >= 0 && index < levelExp.size() ? OptionalLong.of(levelExp.get(index)) : OptionalLong.empty();
    }

    private void requireStage(int stage) {
        if (stage < 0 || stage > maxStage()) {
            throw new IllegalArgumentException("성장도는 0부터 " + maxStage() + "까지다. 받은 값: " + stage);
        }
    }

}
