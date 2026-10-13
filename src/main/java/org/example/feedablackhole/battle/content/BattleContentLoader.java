package org.example.feedablackhole.battle.content;

import org.example.feedablackhole.node.content.ContentDiagnostic;
import org.example.feedablackhole.node.content.ContentLoadException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 전투 콘텐츠 파일(battle.json)의 글자를 읽어 BattleContent로 만든다.
 *
 * <p>두 단계로 동작한다.
 * <ol>
 * <li>읽기: JSON을 Map/List로 읽은 뒤, 이미 있는 record(BattleContent 등)로 옮겨 담는다.
 * <li>검증: 다 만든 뒤 규칙을 검사하고, 어긴 곳을 모아서 ContentLoadException 하나로 던진다.
 * </ol>
 */
public class BattleContentLoader {
    // 예외 메시지와 진단에 쓰는 이름 ("전투 콘텐츠을(를) 불러오지 못했다", "battle.json: ...")
    private static final String WHAT = "전투 콘텐츠";
    public static final String FILE = "battle.json";

    // ---------- battle.json의 키 이름 ----------

    // rules
    private static final String RULES = "rules";
    private static final String TIME_LIMIT_SECONDS = "timeLimitSeconds";
    private static final String KILL_TIME_BONUS_SECONDS = "killTimeBonusSeconds";

    // growth
    private static final String GROWTH = "growth";
    private static final String LEVEL_EXP = "levelExp";
    private static final String MILESTONES = "milestones";
    private static final String LEVEL = "level";
    private static final String TARGET_GOLD = "targetGold";

    // 최상위 stats(timer, growthTime)와 각 적 종류 안의 stats가 같은 키 이름을 쓴다
    private static final String STATS = "stats";
    private static final String TIMER = "timer";
    private static final String GROWTH_TIME = "growthTime";

    // enemies
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

    // enemies[i].stats: 이 종류의 판 구성에 닿는 수치의 statId들
    private static final String MASS = "mass";
    private static final String SIZE = "size";
    private static final String START_SUPPLY = "startSupply"; // 최상위 startSupply와 이름이 같다
    private static final String GROWTH_SUPPLY = "growthSupply";
    private static final String UPGRADE = "upgrade";
    private static final String TIME_CHANCE = "timeChance";
    private static final String TRAIT_CHANCE = "traitChance";
    private static final String GOLDEN_MULTIPLIER = "goldenMultiplier";
    private static final String GOLDEN_CRIT_CHANCE = "goldenCritChance";
    private static final String GOLDEN_CRIT_REWARD_SCALE = "goldenCritRewardScale";

    // startSupply[i]
    private static final String COUNT = "count";

    // JSON을 읽는 도구. 소수(0.3 등)를 double이 아닌 BigDecimal로 읽도록 설정한다.
    // double로 읽은 뒤 float으로 줄이면 드물게 값이 달라지기 때문이다(계약 1.7).
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    // JSON 숫자(소수는 BigDecimal, 정수는 Integer/Long) -> float
    private static float floatOf(Object value) {
        BigDecimal decimal = value instanceof BigDecimal d ? d : BigDecimal.valueOf(((Number) value).longValue());
        return decimal.floatValue();
    }

    // enemies[i].tiers: 객체 목록 -> List<EnemyTier>
    // gold는 int 범위를 넘을 수 있어서 longValue()로 읽는다
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

    // enemies[i].traits: 문자열 목록 -> List<String> (예: ["golden", "electric"])
    private static List<String> traitsOf(Map<?, ?> enemy) {
        return ((List<?>) enemy.get(TRAITS)).stream()
                .map(v -> (String) v)
                .toList();
    }

    // enemies[i].golden: null이면 null, 객체면 GoldenTrait
    private static GoldenTrait goldenOf(Map<?, ?> enemy) {
        Object value = enemy.get(GOLDEN);
        if (value == null) {
            return null; // 황금 성질이 없는 종류(planet, star 등)
        }

        Map<?, ?> golden = (Map<?, ?>) value;
        return new GoldenTrait(
                floatOf(golden.get(MULTIPLIER)),
                floatOf(golden.get(CRIT_CHANCE)),
                floatOf(golden.get(CRIT_REWARD_SCALE))
        );
    }

    // stats.traitChance: 성질 id -> statId (예: "golden" -> "goldenAsteroid.spawnChance")
    // 혜성처럼 {} 이면 반복문이 돌지 않아 빈 Map이 된다
    private static Map<String, String> traitChanceOf(Map<?, ?> stats) {
        Map<String, String> result = new LinkedHashMap<>();
        Map<?, ?> chances = (Map<?, ?>) stats.get(TRAIT_CHANCE);
        for (Map.Entry<?, ?> entry : chances.entrySet()) {
            result.put((String) entry.getKey(), (String) entry.getValue());
        }

        return result;
    }

    // enemies[i].stats -> EnemyStatIds
    // 해당 수치가 없는 종류는 값이 null이다((String) null은 문제없다)
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

    /**
     * battle.json의 글자를 BattleContent로 만든다.
     *
     * @throws ContentLoadException JSON 형식이 틀렸거나, 검증 규칙을 어긴 곳이 하나라도 있을 때
     *                              (어긴 곳은 모두 모아서 한 번에 알린다)
     */
    public static BattleContent load(String json) {
        // JSON 형식 자체가 틀리면(괄호 누락 등) 여기서 알린다
        Object root;
        try {
            root = MAPPER.readValue(json, Object.class); // { } -> Map, [ ] -> List, 숫자/글자/true/null
        } catch (JacksonException e) {
            throw new ContentLoadException(WHAT, List.of(new ContentDiagnostic(FILE, "JSON 형식이 올바르지 않다: " + e.getMessage())));
        }
        Map<?, ?> object = (Map<?, ?>) root;

        // rules: 기본 제한 시간, 처치 시간 보너스
        Map<?, ?> rules = (Map<?, ?>) object.get(RULES);
        BattleRulesDefinition rulesDef = new BattleRulesDefinition(
                floatOf(rules.get(TIME_LIMIT_SECONDS)), floatOf(rules.get(KILL_TIME_BONUS_SECONDS)));

        // growth.levelExp: 숫자 목록 -> List<Long> (Level별 누적 EXP, int 범위를 넘는다)
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

        // 최상위 stats: 적 종류에 속하지 않는 수치의 statId (제한 시간, Level업당 시간)
        Map<?, ?> stats = (Map<?, ?>) object.get(STATS);
        GlobalStatIds globalStatIds = new GlobalStatIds(
                stats.get(TIMER).toString(), stats.get(GROWTH_TIME).toString()
        );

        // enemies: 적 종류마다 EnemyKindDefinition을 만든다 (JSON 순서 그대로)
        // tiers, traits, golden, stats는 위에서 만든 도우미 메서드가 읽는다
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

        // startSupply: 판 시작 때의 공급(노드 반영 전). 종류와 마릿수
        List<SupplyEntry> startSupply = ((List<?>) object.get(START_SUPPLY)).stream()
                .map(v -> {
                    Map<?, ?> item = (Map<?, ?>) v;
                    return new SupplyEntry(
                            (String) item.get(ENEMY_ID),
                            ((Number) item.get(COUNT)).intValue()
                    );
                })
                .toList();

        BattleContent content = new BattleContent(rulesDef, growthDef, globalStatIds, enemies, startSupply);

        // 다 만든 뒤 규칙을 검사하고, 문제가 하나라도 있으면 모아서 던진다
        List<ContentDiagnostic> diagnostics = BattleContentValidator.validate(content);
        if (!diagnostics.isEmpty()) {
            throw new ContentLoadException(WHAT, diagnostics);
        }

        return content;
    }
}
