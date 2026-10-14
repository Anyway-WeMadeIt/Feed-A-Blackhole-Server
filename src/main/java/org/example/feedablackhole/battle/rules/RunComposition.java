package org.example.feedablackhole.battle.rules;

import org.example.feedablackhole.battle.content.BattleContent;
import org.example.feedablackhole.battle.content.EnemyKindDefinition;
import org.example.feedablackhole.battle.content.HqGrowthDefinition;
import org.example.feedablackhole.battle.content.HqMilestone;
import org.example.feedablackhole.battle.content.SupplyEntry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 성장도와 산 노드로 정해지는 판 하나의 설정(클라이언트 GameSessionFactory.Create 중 검증에 필요한 부분).
 * 판 시작 검증과 결산 검증이 이 설정을 보고 클라이언트가 보낸 값이 맞는지 확인한다.
 *
 * <p>판을 만들 때 모든 값을 한 번에 계산해 필드에 담는다. 잘못된 입력(범위 밖 성장도, 0 이하의 제한 시간,
 * 규칙을 어기는 노드 조합)은 만들 때 IllegalArgumentException으로 막는다.
 */
public final class RunComposition implements RunSetup {

    // levelForExp가 Level 사다리(Level별 누적 EXP)를 볼 때 쓴다
    private final HqGrowthDefinition growth;

    // 성장도: 지금까지 도달한 이정표의 수(0 ~ 이정표 수)
    private final int growthStage;

    // 이 판이 시작하는 블랙홀 Level
    private final int startLevel;

    // 이 판의 목표 Level(다음 이정표의 Level). 0이면 목표가 없다(마지막 이정표 뒤)
    private final int goalLevel;

    // startLevel에 닿는 누적 EXP. 결산의 exp는 이 값부터 센다
    private final long startExp;

    // 이 판이 노리는 이정표. 마지막 이정표 뒤면 비어 있다
    private final Optional<HqMilestone> nextMilestone;

    // 노드가 반영된 시작 제한 시간(초): 기본 시간 + timer가 늘어난 양
    private final float timeLimitSeconds;

    // Level업 1회마다 제한 시간에 더하는 초: growth.time이 늘어난 양
    private final float growthTimeSeconds;

    // 시간 추가 1회의 초(노드 영향 없음)
    private final float killTimeBonusSeconds;

    // 적 종류 이름(enemyId) -> 이 판에서의 종류 구성. 종류 순서(battle.json의 enemies 순서)를 지킨다
    private final Map<String, KindComposition> kinds;
    private final List<SupplyEntry> startSupply;

    private RunComposition(HqGrowthDefinition growth, int growthStage, int startLevel, int goalLevel, long startExp,
            Optional<HqMilestone> nextMilestone, float timeLimitSeconds, float growthTimeSeconds, float killTimeBonusSeconds,
            Map<String, KindComposition> kinds, List<SupplyEntry> startSupply) {
        this.growth = growth;
        this.growthStage = growthStage;
        this.startLevel = startLevel;
        this.goalLevel = goalLevel;
        this.startExp = startExp;
        this.nextMilestone = nextMilestone;
        this.timeLimitSeconds = timeLimitSeconds;
        this.growthTimeSeconds = growthTimeSeconds;
        this.killTimeBonusSeconds = killTimeBonusSeconds;
        this.kinds = kinds;
        this.startSupply = startSupply;
    }

    /**
     * 성장도와 산 노드의 수치 값으로 판 설정을 만든다. 범위 밖이면 IllegalArgumentException.
     *
     * @param content     전투 콘텐츠(성장, 규칙, 적 종류)
     * @param growthStage 성장도(0 ~ 이정표 수)
     * @param upgrades    산 노드로 계산한 수치 값
     */
    static RunComposition of(BattleContent content, int growthStage, UpgradeValues upgrades) {
        HqGrowthDefinition growth = content.growth();

        // 성장도로 정해지는 값. startLevelAt은 범위 밖 성장도에서 IllegalArgumentException을 던지므로 가장 먼저 부른다
        int startLevel = growth.startLevelAt(growthStage);
        int goalLevel = growth.goalLevelAt(growthStage);
        long startExp = growth.expToReach(startLevel).orElse(0); // Level 0처럼 값이 없으면 0
        Optional<HqMilestone> nextMilestone = growth.nextMilestoneAt(growthStage);

        // 노드로 정해지는 시간. 제한 시간은 value가 아니라 늘어난 양(gain)을 기본 시간에 더한다(float 덧셈)
        float timeLimitSeconds = content.rules().timeLimitSeconds() + upgrades.gainOf(content.stats().timer());
        float growthTimeSeconds = upgrades.gainOf(content.stats().growthTime());
        float killTimeBonusSeconds = content.rules().killTimeBonusSeconds();

        // 종류 구성은 종류 순서를 지켜 담는다(시작 공급 계산이 이 순서를 쓴다)
        Map<String, KindComposition> kinds = new LinkedHashMap<>();

        // 제한 시간은 0보다 커야 한다(0도 안 된다)
        if (Float.isNaN(timeLimitSeconds) || Float.isInfinite(timeLimitSeconds) || timeLimitSeconds <= 0) {
            throw new IllegalArgumentException("제한 시간은 0보다 큰 유한한 값이어야 한다. 계산된 값: " + timeLimitSeconds);
        }

        // 성장 시간은 0 이상이면 된다(0은 괜찮다)
        if (Float.isNaN(growthTimeSeconds) || Float.isInfinite(growthTimeSeconds) || growthTimeSeconds < 0) {
            throw new IllegalArgumentException("성장 시간은 0 이상의 유한한 값이어야 한다. 계산된 값: " + growthTimeSeconds);
        }

        // 모든 적 종류의 구성을 한꺼번에 만든다. 노드 조합이 규칙을 어기면 KindComposition.from이 예외를 던진다
        for (EnemyKindDefinition definition : content.enemies()) {
            kinds.put(definition.enemyId(), KindComposition.from(definition, upgrades));
        }

        List<SupplyEntry> startSupply = StartSupply.of(content, kinds);

        return new RunComposition(growth, growthStage, startLevel, goalLevel, startExp, nextMilestone,
                timeLimitSeconds, growthTimeSeconds, killTimeBonusSeconds, kinds, startSupply);
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
        return startSupply;
    }

    // 이름으로 종류 구성을 찾는다. 없으면 빈 Optional
    @Override
    public Optional<KindSetup> findKind(String enemyId) {
        return Optional.ofNullable(kinds.get(enemyId));
    }

    // 이름으로 종류 구성을 찾는다. 없으면 IllegalArgumentException
    @Override
    public KindSetup kind(String enemyId) {
        Optional<KindSetup> found = findKind(enemyId);
        if (found.isEmpty()) {
            throw new IllegalArgumentException("없는 종류다:" + enemyId);
        }

        return found.get();
    }

    /**
     * 이 판에서 누적 EXP가 exp일 때의 Level(클라이언트 Hq.RaiseLevels).
     * startLevel부터 시작해 다음 Level의 누적 EXP 이상이면 한 단계씩 올린다.
     * 목표 Level이 있으면 거기서, 없으면 Level 사다리의 끝에서 멈춘다.
     *
     * @param exp startExp부터 센 누적 EXP. startExp보다 작아도 오류가 아니고 startLevel이 나온다
     */
    @Override
    public int levelForExp(long exp) {
        // startLevel 필드는 바꾸지 않고, 올려 갈 Level은 지역 변수로 둔다
        int level = startLevel;
        OptionalLong next;

        // 목표가 없거나(goalLevel == NO_GOAL), 아직 목표에 닿지 않은 동안 반복한다
        while (goalLevel == HqGrowthDefinition.NO_GOAL || level < goalLevel) {
            // 다음 Level에 닿는 누적 EXP. 비어 있으면 Level 사다리의 끝이다
            next = growth.expToReach(level + 1);

            // 사다리 끝이거나 아직 다음 Level에 못 닿았으면 멈춘다(임계값과 같으면 올라간다)
            if (next.isEmpty() || exp < next.getAsLong()) {
                break;
            }
            level++;
        }

        return level;
    }
}
