package org.example.feedablackhole.battle.rules;

import org.example.feedablackhole.battle.content.EnemyKindDefinition;
import org.example.feedablackhole.battle.content.EnemyStatIds;
import org.example.feedablackhole.battle.content.GoldenTrait;

import java.util.Optional;

/**
 * 판 하나에서 적 종류 하나의 구성(클라이언트 EnemyComposition.From과 EnemyDefinition.StatsAt 중 검증에 필요한 부분).
 */
public final class KindComposition implements KindSetup {

    // 성질 확률의 합이 이 값을 넘으면 예외(C#의 1 + 1e-4f)
    private static final float CHANCE_SUM_LIMIT = 1 + 1e-4f;

    private final EnemyKindDefinition kind;
    private final float[] tierRatios;   // 색 등급마다 나오는 비율(MassRule)
    private final int size;             // 이 판에서 나올 수 있는 가장 큰 크기
    private final int startSupplyBonus; // 시작 공급에 더하는 마리 수
    private final int upgradeCount;     // 시작 공급 중 다음 종류로 바꾸는 수
    private final float timeChance;     // 파괴될 때 시간 추가 확률(0 ~ 1)
    private final GoldenTrait golden;   // 노드가 반영된 황금 수치. 황금 성질이 없는 종류는 null
    private final boolean goldenPossible;

    private KindComposition(EnemyKindDefinition kind, float[] tierRatios, int size, int startSupplyBonus,
            int upgradeCount, float timeChance, GoldenTrait golden, boolean goldenPossible) {
        this.kind = kind;
        this.tierRatios = tierRatios;
        this.size = size;
        this.startSupplyBonus = startSupplyBonus;
        this.upgradeCount = upgradeCount;
        this.timeChance = timeChance;
        this.golden = golden;
        this.goldenPossible = goldenPossible;
    }

    /**
     * 산 노드의 수치 값으로 이 종류의 판 구성을 계산한다. 범위 밖이면 IllegalArgumentException.
     */
    static KindComposition from(EnemyKindDefinition kind, UpgradeValues upgrades) {
        EnemyStatIds stats = kind.stats();

        // 질량은 수치 값 그대로, 나머지는 기본값에 늘어난 양을 더한다
        float mass = stats.mass() == null ? MassRule.BASE : upgrades.valueOf(stats.mass());
        int size = Rounding.whole(SizeRule.BASE + gainOf(upgrades, stats.size()));
        int startSupplyBonus = Rounding.whole(gainOf(upgrades, stats.startSupply()));
        int upgradeCount = Rounding.whole(gainOf(upgrades, stats.upgrade()));
        float timeChance = percent(gainOf(upgrades, stats.timeChance()), kind, "시간 추가 확률");

        if (size < SizeRule.BASE || size > SizeRule.MAX) {
            throw invalid(kind, "크기는 " + SizeRule.BASE + "부터 " + SizeRule.MAX + "까지다. 업그레이드 합: " + size + ".");
        }
        if (upgradeCount < 0) {
            throw invalid(kind, "변환 수는 0 이상이어야 한다. 업그레이드 합: " + upgradeCount + ".");
        }
        // 변환 대상이 없는 종류는 바꿔 줄 종류가 없다
        if (upgradeCount > 0 && kind.upgradesTo() == null) {
            throw invalid(kind, "변환 대상이 없어 변환 수를 둘 수 없다. 업그레이드 합: " + upgradeCount + ".");
        }
        // 픽업(혜성)은 공급·질량·크기·시간 추가가 없다
        if (kind.pickup() && startSupplyBonus > 0) {
            throw invalid(kind, "픽업이라 공급되지 않는다. 공급 수 노드를 둘 수 없다.");
        }
        if (kind.pickup() && (mass != MassRule.BASE || size != SizeRule.BASE)) {
            throw invalid(kind, "픽업이라 질량·크기가 없다. 질량·크기 노드를 둘 수 없다.");
        }
        if (kind.pickup() && timeChance > 0) {
            throw invalid(kind, "픽업이라 파괴 때의 시간 추가 확률을 둘 수 없다.");
        }

        float[] tierRatios = MassRule.tierRatios(mass, kind.tiers().size());

        // 성질마다의 생성 확률. 픽업의 성질은 언제나 붙으므로 확률이 없다
        float chanceSum = 0;
        float goldenChance = 0;
        if (!kind.pickup()) {
            for (String trait : kind.traits()) { // 종류의 성질 순서대로 float 누적(C#과 같은 순서)
                float chance = percent(gainOf(upgrades, stats.traitChance().get(trait)), kind, "'" + trait + "' 성질 확률");
                chanceSum += chance;
                if (EnemyKindDefinition.GOLDEN.equals(trait)) {
                    goldenChance = chance;
                }
            }
        }
        // 성질은 한 마리에 하나다. 합이 100%를 넘으면 어느 성질도 약속한 몫을 받을 수 없다
        if (chanceSum > CHANCE_SUM_LIMIT) {
            throw invalid(kind, "성질 확률의 합이 100%를 넘는다(" + chanceSum * 100 + "%).");
        }

        GoldenTrait golden = kind.golden() == null ? null : upgradedGolden(kind, upgrades);
        boolean goldenPossible = golden != null && goldenChance > 0; // 황금 성질이 있고 생성 확률이 0보다 크다

        return new KindComposition(kind, tierRatios, size, startSupplyBonus, upgradeCount, timeChance, golden,
                goldenPossible);
    }

    // 노드가 반영된 황금 수치: 배율과 치명 배율은 늘어난 %p ÷ 100을 더하고, 치명 확률은 1을 넘지 않게 자른다
    private static GoldenTrait upgradedGolden(EnemyKindDefinition kind, UpgradeValues upgrades) {
        EnemyStatIds stats = kind.stats();
        GoldenTrait base = kind.golden();

        float multiplier = base.multiplier() + gainOf(upgrades, stats.goldenMultiplier()) / 100;
        float critChance = Math.min(1f, base.critChance() + gainOf(upgrades, stats.goldenCritChance()) / 100);
        float critRewardScale = base.critRewardScale() + gainOf(upgrades, stats.goldenCritRewardScale()) / 100;

        if (!isPositiveFinite(multiplier) || !isPositiveFinite(critRewardScale) || !(critChance >= 0 && critChance <= 1)) {
            throw invalid(kind, "황금 수치가 범위 밖이다. 배율 " + multiplier + ", 치명 확률 " + critChance
                    + ", 치명 배율 " + critRewardScale + ".");
        }
        return new GoldenTrait(multiplier, critChance, critRewardScale);
    }

    @Override
    public String enemyId() {
        return kind.enemyId();
    }

    @Override
    public boolean pickup() {
        return kind.pickup();
    }

    @Override
    public int maxSize() {
        return size;
    }

    // 이 판의 질량에서 이 색의 비율이 0보다 큰가
    @Override
    public boolean tierPossible(int tier) {
        requireTier(tier);
        return tierRatios[tier] > 0;
    }

    @Override
    public boolean goldenPossible() {
        return goldenPossible;
    }

    @Override
    public float timeChance() {
        return timeChance;
    }

    @Override
    public Optional<GoldenTrait> golden() {
        return Optional.ofNullable(golden);
    }

    // 처치 하나의 Gold: round(색의 Gold × 크기 배율), 황금이면 round(그 값 × 황금 배율)
    @Override
    public long goldOf(int tier, int size, boolean golden) {
        requireTier(tier);
        requireSize(size);
        if (golden && !goldenPossible) {
            throw new IllegalArgumentException("'" + kind.enemyId() + "'는 이 판에서 황금이 나올 수 없다.");
        }

        // 크기 배율은 float이고, long × double로 곱한다(float을 double로 넓힌다)
        long gold = Rounding.scaled(kind.tiers().get(tier).gold(), SizeRule.statMultiplier(size));
        if (golden) {
            gold = Rounding.scaled(gold, this.golden.multiplier());
        }
        return gold;
    }

    // 처치 하나의 EXP: round(색의 EXP × 크기 배율)
    @Override
    public long expOf(int tier, int size) {
        requireTier(tier);
        requireSize(size);

        return Rounding.scaled(kind.tiers().get(tier).exp(), SizeRule.statMultiplier(size));
    }

    // 황금 보너스 치명 한 번의 Gold: round(황금 Gold × 치명 배율)
    @Override
    public long goldenCritBonusOf(int tier, int size) {
        long gold = goldOf(tier, size, true);

        // long * float은 float 곱셈이다(C28). 식은 Rounding.critBonus에 모아 기대값 표의 산술 확인 지점이 직접 확인한다
        return Rounding.critBonus(gold, golden.critRewardScale());
    }

    // 시작 공급에 더하는 마리 수(5단계의 시작 공급 계산이 쓴다)
    int startSupplyBonus() {
        return startSupplyBonus;
    }

    // 시작 공급 중 다음 종류로 바꾸는 수(5단계의 시작 공급 계산이 쓴다)
    int upgradeCount() {
        return upgradeCount;
    }

    private void requireTier(int tier) {
        if (tier < 0 || tier >= kind.tiers().size()) {
            throw new IllegalArgumentException("'" + kind.enemyId() + "'의 색 등급은 0부터 "
                    + (kind.tiers().size() - 1) + "까지다. 받은 값: " + tier);
        }
    }

    private void requireSize(int size) {
        if (size < SizeRule.BASE || size > this.size) {
            throw new IllegalArgumentException("이 판에서 '" + kind.enemyId() + "'의 크기는 " + SizeRule.BASE
                    + "부터 " + this.size + "까지다. 받은 값: " + size);
        }
    }

    // 수치가 없는 종류(statId가 null)는 늘어난 양이 0이다
    private static float gainOf(UpgradeValues upgrades, String statId) {
        return statId == null ? 0 : upgrades.gainOf(statId);
    }

    // 확률(%): 0 이상의 유한한 값이어야 하고, 1(100%)을 넘지 않게 자른다
    private static float percent(float value, EnemyKindDefinition kind, String label) {
        if (Float.isNaN(value) || Float.isInfinite(value) || value < 0) {
            throw invalid(kind, label + "은 0 이상의 유한한 값이어야 한다. 업그레이드 합: " + value + ".");
        }
        return Math.min(1f, value / 100f);
    }

    private static boolean isPositiveFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value) && value > 0;
    }

    private static IllegalArgumentException invalid(EnemyKindDefinition kind, String message) {
        return new IllegalArgumentException("'" + kind.enemyId() + "' " + message);
    }

}
