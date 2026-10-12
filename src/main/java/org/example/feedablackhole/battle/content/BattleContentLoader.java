package org.example.feedablackhole.battle.content;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BattleContentLoader {

    // battle.json의 키 이름
    private static final String RULES = "rules";
    private static final String TIME_LIMIT_SECONDS = "timeLimitSeconds";
    private static final String KILL_TIME_BONUS_SECONDS = "killTimeBonusSeconds";
    private static final String GROWTH = "growth";
    private static final String LEVEL_EXP = "levelExp";
    private static final String MILESTONES = "milestones";
    private static final String LEVEL = "level";
    private static final String TARGET_GOLD = "targetGold";
    private static final String STATS = "stats";
    private static final String TIMER = "timer";
    private static final String GROWTH_TIME = "growthTime";
    private static final String ENEMIES = "enemies";
    private static final String ENEMY_ID = "enemyId";
    private static final String PICK_UP = "pickup";
    private static final String UPGRADES_TO = "upgradesTo";
    private static final String TIERS = "tiers";
    private static final String GOLD = "gold";
    private static final String EXP = "exp";
    private static final String TRAITS = "traits";
    private static final String GOLDEN = "golden";
    private static final String MULTIPLIER = "multiplier";
    private static final String CRIT_CHANCE = "critChance";
    private static final String CRIT_REWARD_SCALE = "critRewardScale";
    private static final String MASS = "mass";
    private static final String SIZE = "size";
    private static final String START_SUPPLY = "startSupply";
    private static final String GROWTH_SUPPLY = "growthSupply";
    private static final String UPGRADE = "upgrade";
    private static final String TIME_CHANCE = "timeChance";
    private static final String TRAIT_CHANCE = "traitChance";
    private static final String GOLDEN_MULTIPLIER = "goldenMultiplier";
    private static final String GOLDEN_CRIT_CHANCE = "goldenCritChance";
    private static final String GOLDEN_CRIT_REWARD_SCALE = "goldenCritRewardScale";
    private static final String COUNT = "count";

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    // JSON 숫자(BigDecimal 또는 정수) -> float
    private static float floatOf(Object value) {
        BigDecimal decimal = value instanceof BigDecimal d ? d : BigDecimal.valueOf(((Number) value).longValue());
        return decimal.floatValue();
    }

    // enemies[i].tiers: 객체 목록 -> List<EnemyTier>
    private static List<EnemyTier> tiersOf(Map<?, ?> enemy) {
        return ((List<?>) enemy.get(TIERS)).stream()
                .map(v -> {
                    Map<?, ?> item = (Map<?, ?>) v;
                    return new EnemyTier(
                            ((Number) item.get(GOLD)).longValue(),
                            ((Number) item.get(EXP)).longValue());
                })
                .toList();
    }

    private static List<String> traitsOf(Map<?, ?> enemy) {
        return ((List<?>) enemy.get(TRAITS)).stream()
                .map(v -> (String) v)
                .toList();
    }

    // enemies[i].golden: null이면 null, 객체면 GoldenTrait
    private static GoldenTrait goldenOf(Map<?, ?> enemy) {
        Object value = enemy.get(GOLDEN);
        if (value == null) {
            return null;
        }

        Map<?, ?> golden = (Map<?, ?>) value;
        return new GoldenTrait(
                floatOf(golden.get(MULTIPLIER)),
                floatOf(golden.get(CRIT_CHANCE)),
                floatOf(golden.get(CRIT_REWARD_SCALE))
        );
    }

    // stats.traitChance: 성질 id -> statId
    private static Map<String, String> traitChanceOf(Map<?, ?> stats) {
        Map<String, String> result = new LinkedHashMap<>();
        Map<?, ?> chances = (Map<?, ?>) stats.get(TRAIT_CHANCE);
        for (Map.Entry<?, ?> entry : chances.entrySet()) {
            result.put((String) entry.getKey(), (String) entry.getValue());
        }

        return result;
    }

    // enemies[i].stats -> EnemyStatIds
    private static EnemyStatIds statsOf(Map<?, ?> enemy) {
        Map<?, ?> stats = (Map<?, ?>) enemy.get(STATS);
        return new EnemyStatIds(
                (String) stats.get(MASS),
                (String) stats.get(SIZE),
                (String) stats.get(START_SUPPLY),
                (String) stats.get(GROWTH_SUPPLY),
                (String) stats.get(UPGRADE),
                (String) stats.get(TIME_CHANCE),
                traitChanceOf(stats),
                (String) stats.get(GOLDEN_MULTIPLIER),
                (String) stats.get(GOLDEN_CRIT_CHANCE),
                (String) stats.get(GOLDEN_CRIT_REWARD_SCALE)
        );
    }

    public static BattleContent load(String json) {
        Object root = MAPPER.readValue(json, Object.class);
        Map<?, ?> object = (Map<?, ?>) root;

        // rules
        Map<?, ?> rules = (Map<?, ?>) object.get(RULES);
        BattleRulesDefinition rulesDef = new BattleRulesDefinition(
                floatOf(rules.get(TIME_LIMIT_SECONDS)), floatOf(rules.get(KILL_TIME_BONUS_SECONDS)));

        // growth.levelExp: 숫자 목록 -> List<Long>
        Map<?, ?> growth = (Map<?, ?>) object.get(GROWTH);
        List<Long> levelExp = ((List<?>) growth.get(LEVEL_EXP)).stream()
                .map(v -> ((Number) v).longValue()).toList();

        // growth.milestones: 객체 목록 -> List<HqMilestone>
        // 원소마다 Map으로 꺼내 level(int)과 targetGold(long)을 읽는다
        List<HqMilestone> milestones = ((List<?>) growth.get(MILESTONES)).stream()
                .map(v -> {
                    Map<?, ?> item = (Map<?, ?>) v;
                    return new HqMilestone(
                            ((Number) item.get(LEVEL)).intValue(),
                            ((Number) item.get(TARGET_GOLD)).longValue()
                    );
                })
                .toList();
        HqGrowthDefinition growthDef = new HqGrowthDefinition(levelExp, milestones);

        Map<?, ?> stats = (Map<?, ?>) object.get(STATS);
        GlobalStatIds globalStatIds = new GlobalStatIds(
                stats.get(TIMER).toString(), stats.get(GROWTH_TIME).toString()
        );

        List<EnemyKindDefinition> enemies = ((List<?>) object.get(ENEMIES)).stream()
                .map(v -> {
                    Map<?, ?> item = (Map<?, ?>) v;
                    return new EnemyKindDefinition(
                            (String) item.get(ENEMY_ID),
                            (Boolean) item.get(PICK_UP),
                            (String) item.get(UPGRADES_TO),
                            tiersOf(item),
                            traitsOf(item),
                            goldenOf(item),
                            statsOf(item)
                    );
                })
                .toList();

        List<SupplyEntry> startSupply = ((List<?>) object.get(START_SUPPLY)).stream()
                .map(v -> {
                    Map<?, ?> item = (Map<?, ?>) v;
                    return new SupplyEntry(
                            (String) item.get(ENEMY_ID),
                            ((Number) item.get(COUNT)).intValue()
                    );
                })
                .toList();

        return new BattleContent(rulesDef, growthDef, globalStatIds, enemies, startSupply);
    }
}
