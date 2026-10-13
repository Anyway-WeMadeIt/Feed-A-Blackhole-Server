package org.example.feedablackhole.battle.rules;

import org.example.feedablackhole.battle.content.BattleContent;
import org.example.feedablackhole.battle.content.HqGrowthDefinition;
import org.example.feedablackhole.battle.content.HqMilestone;
import org.example.feedablackhole.battle.content.SupplyEntry;

import java.util.List;
import java.util.Optional;

public final class RunComposition implements RunSetup {

    private final int growthStage;
    private final int startLevel;
    private final int goalLevel;
    private final long startExp;
    private final Optional<HqMilestone> nextMilestone;
    private final float timeLimitSeconds;
    private final float growthTimeSeconds;
    private final float killTimeBonusSeconds;

    private RunComposition(int growthStage, int startLevel, int goalLevel, long startExp, Optional<HqMilestone> nextMilestone,
            float timeLimitSeconds, float growthTimeSeconds, float killTimeBonusSeconds) {
        this.growthStage = growthStage;
        this.startLevel = startLevel;
        this.goalLevel = goalLevel;
        this.startExp = startExp;
        this.nextMilestone = nextMilestone;
        this.timeLimitSeconds = timeLimitSeconds;
        this.growthTimeSeconds = growthTimeSeconds;
        this.killTimeBonusSeconds = killTimeBonusSeconds;
    }

    static RunComposition of(BattleContent content, int growthStage, UpgradeValues upgrades) {
        HqGrowthDefinition growth = content.growth();
        int startLevel = growth.startLevelAt(growthStage);
        int goalLevel = growth.goalLevelAt(growthStage);
        long startExp = growth.expToReach(startLevel).orElse(0);
        Optional<HqMilestone> nextMilestone = growth.nextMilestoneAt(growthStage);
        float timeLimitSeconds = content.rules().timeLimitSeconds() + upgrades.gainOf(content.stats().timer());
        float growthTimeSeconds = upgrades.gainOf(content.stats().growthTime());
        float killTimeBonusSeconds = content.rules().killTimeBonusSeconds();

        if (Float.isNaN(timeLimitSeconds) || Float.isInfinite(timeLimitSeconds) || timeLimitSeconds <= 0) {
            throw new IllegalArgumentException("제한 시간은 0보다 큰 유한한 값이어야 한다. 계산된 값: " + timeLimitSeconds);
        }

        if (Float.isNaN(growthTimeSeconds) || Float.isInfinite(growthTimeSeconds) || growthTimeSeconds < 0) {
            throw new IllegalArgumentException("성장 시간은 0 이상의 유한한 값이어야 한다. 계산된 값: " + growthTimeSeconds);
        }

        return new RunComposition(growthStage, startLevel, goalLevel, startExp, nextMilestone,
                timeLimitSeconds, growthTimeSeconds, killTimeBonusSeconds);
    }

    @Override
    public int growthStage() {
        return growthStage;
    }

    @Override
    public int startLevel() {
        return startLevel;
    }

    @Override
    public int goalLevel() {
        return goalLevel;
    }

    @Override
    public long startExp() {
        return startExp;
    }

    @Override
    public Optional<HqMilestone> nextMilestone() {
        return nextMilestone;
    }

    @Override
    public float timeLimitSeconds() {
        return timeLimitSeconds;
    }

    @Override
    public float growthTimeSeconds() {
        return growthTimeSeconds;
    }

    @Override
    public float killTimeBonusSeconds() {
        return killTimeBonusSeconds;
    }

    @Override
    public List<SupplyEntry> startSupply() {
        throw new UnsupportedOperationException("아직 구현하지 않았다");
    }

    @Override
    public Optional<KindSetup> findKind(String enemyId) {
        throw new UnsupportedOperationException("아직 구현하지 않았다");
    }

    @Override
    public KindSetup kind(String enemyId) {
        throw new UnsupportedOperationException("아직 구현하지 않았다");
    }

    @Override
    public int levelForExp(long exp) {
        throw new UnsupportedOperationException("아직 구현하지 않았다");
    }
}
