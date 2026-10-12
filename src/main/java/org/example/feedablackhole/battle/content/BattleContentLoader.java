package org.example.feedablackhole.battle.content;

import org.example.feedablackhole.node.content.ContentDiagnostic;
import org.example.feedablackhole.node.content.ContentLoadException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private static final String FILE = "battle.json";

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

    // 검증 기준: 색 등급(tiers)의 개수 (계약 1.5)
    // 공급 종류(소행성, 행성, 항성, 은하)는 등급이 6개
    private static final int SUPPLY_TIER_COUNT = 6;
    // 수거형(혜성)은 등급 구분 없이 1개
    private static final int PICKUP_TIER_COUNT = 1;

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

    // 다 만든 BattleContent가 규칙에 맞는지 검사한다. 어긴 곳은 into에 모은다(바로 던지지 않는다).
    private static void validate(BattleContent content, List<ContentDiagnostic> into) {
        // 규칙을 하나씩 여기에 추가
        verifyEnemies(content.enemies(), into);
//        verifyGrowth(content.growth(), into);
//        verifyStartSupply(content, into);
    }

    // 적 종류에 대한 규칙
    private static void verifyEnemies(List<EnemyKindDefinition> enemies, List<ContentDiagnostic> into) {
        Set<String> ids = new HashSet<>();

        // 규칙 1) enemyId가 유일하다 -> 중복 X
        for (EnemyKindDefinition enemy : enemies) {
            if (!ids.add(enemy.enemyId())) { // add가 false면 이미 있던 id
                into.add(new ContentDiagnostic("enemies[" + enemy.enemyId() + "]",
                        "적 종류 id '" + enemy.enemyId() + "'가 중복됐다."));
            }
        }

        // 규칙 2) upgradesTo가 존재하는 종류를 가리킨다 (없으면 null이라 검사하지 않는다)
        for (EnemyKindDefinition enemy : enemies) {
            if (enemy.upgradesTo() != null && !ids.contains(enemy.upgradesTo())) {
                into.add(new ContentDiagnostic("enemies[" + enemy.enemyId() + "].upgradesTo",
                        "변환 대상 '" + enemy.upgradesTo() + "'가 없는 종류다."));
            }
        }

        // 규칙 3) 변환 사슬에 순환이 없다
        // 적의 이름(enemyId)으로 적 객체를 바로 꺼낼 수 있는 표.
        // upgradesTo에는 객체가 아니라 이름만 들어 있어서, 사슬을 따라가려면 이름으로 적을 찾아야 한다
        Map<String, EnemyKindDefinition> byId = new HashMap<>();
        for (EnemyKindDefinition enemy : enemies) {
            byId.put(enemy.enemyId(), enemy);
        }

        // 모든 적을 한 번씩 시작점으로 삼아, 변환 사슬을 따라가다 시작점으로 돌아오는지 본다
        for (EnemyKindDefinition start : enemies) {
            String next = start.upgradesTo(); // 시작점이 변하는 다음 적의 이름
            int steps = 0;                    // 따라간 칸 수(무한 반복 방지용 카운터)

            // 계속 따라가는 조건: 사슬이 끝나지 않았고(null 아님), 존재하는 이름이고(규칙 2가 따로 보고),
            // 적의 개수보다 많이 따라가지 않았을 때(시작점을 지나지 않는 순환에서 멈추기 위한 상한)
            while (next != null && byId.containsKey(next) && steps <= enemies.size()) {
                // 따라간 곳이 시작점이면 원을 그린 것이다. 문자열은 == 가 아니라 equals로 비교한다
                if (start.enemyId().equals(next)) {
                    into.add(new ContentDiagnostic("enemies[" + start.enemyId() + "].upgradesTo",
                            "변환 사슬에 순환이 있다."));
                    break; // 이 시작점은 더 따라갈 필요가 없다
                }
                next = byId.get(next).upgradesTo(); // 한 칸 전진: next가 가리키는 적의 다음 변환 대상을 읽는다
                steps++;
            }
        }

        // 규칙 4) 색 등급 개수와 Gold·EXP
        // (적마다 보는 규칙이라 적 목록을 도는 반복문 안에 있다)
        for (EnemyKindDefinition enemy : enemies) {
            // 등급 개수: 수거형(혜성)은 1개, 공급 종류는 6개
            if (enemy.pickup()) {
                if (enemy.tiers().size() != PICKUP_TIER_COUNT) {
                    into.add(new ContentDiagnostic("enemies[" + enemy.enemyId() + "].tiers",
                            "색 등급은 1개여야 한다 (받은 개수: '" + enemy.tiers().size() + "')"));
                }
            } else {
                if (enemy.tiers().size() != SUPPLY_TIER_COUNT) {
                    into.add(new ContentDiagnostic("enemies[" + enemy.enemyId() + "].tiers",
                            "색 등급은 6개여야 한다 (받은 개수: '" + enemy.tiers().size() + "')"));
                }
            }

            // Gold·EXP: 개수가 틀려도 멈추지 않고, 이 적의 모든 등급을 돌며 음수인지 본다
            // (돌 대상은 적 목록이 아니라 이 적의 등급 목록이다)
            for (int i = 0; i < enemy.tiers().size(); i++) {
                // 0은 정상이다(혜성의 gold: 0). 음수일 때만 문제다
                if (enemy.tiers().get(i).gold() < 0) {
                    into.add(new ContentDiagnostic("enemies[" + enemy.enemyId() + "].tiers[" + i + "].gold",
                            "0 이상이어야 한다."));
                }
                if (enemy.tiers().get(i).exp() < 0) {
                    into.add(new ContentDiagnostic("enemies[" + enemy.enemyId() + "].tiers[" + i + "].exp",
                            "0 이상이어야 한다."));
                }
            }
        }
    }

//    private static void verifyGrowth()

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
        List<ContentDiagnostic> diagnostics = new ArrayList<>();
        validate(content, diagnostics);
        if (!diagnostics.isEmpty()) {
            throw new ContentLoadException(WHAT, diagnostics);
        }

        return content;
    }
}
