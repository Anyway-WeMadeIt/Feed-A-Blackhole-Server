package org.example.feedablackhole.battle.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.example.feedablackhole.battle.content.ContentBundle;
import org.example.feedablackhole.battle.content.ContentBundleLoader;
import org.example.feedablackhole.battle.content.GoldenTrait;
import org.example.feedablackhole.battle.content.HqMilestone;
import org.example.feedablackhole.battle.content.SupplyEntry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 전투 규칙 이식이 클라이언트(C#)와 같은 값을 내는지 확인한다(계약 1.7a, W4-1b).
 *
 * <p>기대값 표(fixtures/rules-expected.json)는 클라이언트 에디터 메뉴(BlackHole &gt; Sync Fixtures &gt; Export Rules Table...)가
 * 실제 콘텐츠로 실제 규칙 코드를 돌려 만든다. 서버는 실제 묶음(src/main/resources/content) 위에서 같은 입력으로 같은 값을 내야 한다.
 * 비교는 완전 일치다(float은 비트 비교, 허용 오차 없음). 어긋나면 어느 값이 얼마나 다른지 전부 모아 알린다.
 *
 * <p>표의 콘텐츠 버전이 서버 콘텐츠 버전과 다르면 값을 비교하지 않고 "표 재생성 필요"로 실패한다.
 * 콘텐츠를 바꾸면 W0-1 묶음 내보내기 뒤에 이 표도 다시 내보낸다.
 */
class RulesTableTests {

    // 클래스패스 기준 위치
    private static final String CONTENT = "/content/";
    private static final String TABLE = "/fixtures/rules-expected.json";

    // 한 번에 보여 줄 불일치 수(나머지는 개수만 알린다)
    private static final int SHOWN = 30;

    private static ContentBundle bundle;
    private static ContentBattleRules rules;
    private static JsonNode table;

    @BeforeAll
    static void load() {
        bundle = ContentBundleLoader.load(path -> read(CONTENT + path));
        rules = new ContentBattleRules(bundle.nodeTree(), bundle.battle());
        byte[] bytes = read(TABLE);
        assertThat(bytes).as("기대값 표 %s (클라이언트에서 내보낸다)", TABLE).isNotNull();
        table = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build().readTree(bytes);

        // 버전이 다르면 값을 비교해도 의미가 없다. 가장 먼저, 가장 분명하게 알린다.
        String tableVersion = table.get("contentVersion").asString();
        String serverVersion = bundle.manifest().contentVersion();
        assertThat(tableVersion)
                .as("기대값 표의 콘텐츠 버전(%s)이 서버 콘텐츠 버전(%s)과 다르다. 픽스처 재생성 필요: 클라이언트에서 "
                        + "BlackHole > Sync Fixtures > Export Rules Table...을 다시 실행해 표를 서버 저장소에 커밋한다.",
                        tableVersion, serverVersion)
                .isEqualTo(serverVersion);
    }

    @Test
    void tableCoversEveryStageAndTheExpectedLoadouts() {
        assertThat(table.get("formatVersion").asInt()).isEqualTo(1);
        assertThat(table.get("stages")).as("성장도마다 하나씩").hasSize(bundle.battle().growth().maxStage() + 1);

        List<String> names = new ArrayList<>();
        table.get("loadouts").forEach(loadout -> names.add(loadout.get("name").asString()));
        assertThat(names).containsExactly("empty", "partial-10", "partial-25", "partial-50", "partial-75", "all");

        // 표가 비어도 통과하지 않도록: 비교할 값이 충분히 있어야 한다
        assertThat(table.get("arithmetic").get("multiply")).isNotEmpty();
        assertThat(table.get("arithmetic").get("critBonus")).isNotEmpty();
    }

    // 성장도마다: 시작·목표 레벨, 시작 EXP, 다음 이정표, 레벨 계산, 마일스톤 보상. 어떤 노드 상태에서도 같아야 한다
    @Test
    void stagesMatch() {
        Mismatches diff = new Mismatches();

        for (JsonNode loadout : table.get("loadouts")) {
            UpgradeValues upgrades = upgradesOf(loadout);

            for (JsonNode stage : table.get("stages")) {
                int s = stage.get("growthStage").asInt();
                RunSetup run = rules.setupFor(s, upgrades);
                String at = "stage " + s + " (노드 " + loadout.get("name").asString() + ")";

                diff.eq(at + " startLevel", stage.get("startLevel").asInt(), run.startLevel());
                diff.eq(at + " goalLevel", stage.get("goalLevel").asInt(), run.goalLevel());
                diff.eq(at + " startExp", stage.get("startExp").longValue(), run.startExp());

                JsonNode next = stage.get("nextMilestone");
                Optional<HqMilestone> milestone = run.nextMilestone();
                if (next.isNull()) {
                    diff.eq(at + " nextMilestone 없음", true, milestone.isEmpty());
                } else if (milestone.isEmpty()) {
                    diff.eq(at + " nextMilestone 있음", true, false);
                } else {
                    diff.eq(at + " nextMilestone.level", next.get("level").asInt(), milestone.get().level());
                    diff.eq(at + " nextMilestone.targetGold", next.get("targetGold").longValue(), milestone.get().targetGold());
                }

                for (JsonNode pair : stage.get("levelForExp")) {
                    long exp = pair.get(0).longValue();
                    diff.eq(at + " levelForExp(" + exp + ")", pair.get(1).asInt(), run.levelForExp(exp));
                }

                JsonNode rewards = stage.get("milestoneRewards");
                diff.eq(at + " milestoneRewards 있음 == nextMilestone 있음", next.isNull(), rewards.isEmpty());
                if (milestone.isPresent()) {
                    for (JsonNode pair : rewards) {
                        long gold = pair.get(0).longValue();
                        diff.eq(at + " rewardFor(" + gold + ")", pair.get(1).longValue(), milestone.get().rewardFor(gold));
                    }
                }
            }
        }

        diff.assertNone();
    }

    // 노드 상태마다: 수치 값, 제한 시간, 시작 공급, 종류별 구성과 Gold·EXP 표. 어떤 성장도에서도 같아야 한다
    @Test
    void loadoutsMatch() {
        Mismatches diff = new Mismatches();

        for (JsonNode loadout : table.get("loadouts")) {
            UpgradeValues upgrades = upgradesOf(loadout);
            String name = loadout.get("name").asString();

            // 진단: 수치 값. 여기서 어긋나면 노드 합산·clamp 단계가 원인이다
            loadout.get("stats").properties().forEach(e -> {
                diff.eq(name + " stats " + e.getKey() + " value", f(e.getValue().get("value")), upgrades.valueOf(e.getKey()));
                diff.eq(name + " stats " + e.getKey() + " gain", f(e.getValue().get("gain")), upgrades.gainOf(e.getKey()));
            });

            for (JsonNode stage : table.get("stages")) {
                RunSetup run = rules.setupFor(stage.get("growthStage").asInt(), upgrades);
                String at = name + " @성장도" + stage.get("growthStage").asInt();

                diff.eq(at + " timeLimitSeconds", f(loadout.get("timeLimitSeconds")), run.timeLimitSeconds());
                diff.eq(at + " growthTimeSeconds", f(loadout.get("growthTimeSeconds")), run.growthTimeSeconds());
                diff.eq(at + " killTimeBonusSeconds", f(loadout.get("killTimeBonusSeconds")), run.killTimeBonusSeconds());
                diff.eq(at + " startSupply", supplyOf(loadout.get("startSupply")), supplyOf(run.startSupply()));

                for (JsonNode kind : loadout.get("kinds")) {
                    String enemyId = kind.get("enemyId").asString();
                    compareKind(diff, at + " " + enemyId, kind, run.kind(enemyId));
                }
            }
        }

        diff.assertNone();
    }

    // 콘텐츠가 아직 쓰지 않는 경우(큰 값, 소수 배율, 정확히 .5)에서도 곱셈과 반올림이 클라이언트와 같다
    @Test
    void arithmeticMatches() {
        Mismatches diff = new Mismatches();

        for (JsonNode c : table.get("arithmetic").get("multiply")) {
            long value = c.get(0).longValue();
            float factor = f(c.get(1));
            diff.eq("multiply(" + value + ", " + factor + ")", c.get(2).longValue(), Rounding.scaled(value, factor));
        }
        for (JsonNode c : table.get("arithmetic").get("critBonus")) {
            long gold = c.get(0).longValue();
            float scale = f(c.get(1));
            diff.eq("critBonus(" + gold + ", " + scale + ")", c.get(2).longValue(), Rounding.critBonus(gold, scale));
        }

        diff.assertNone();
    }

    private static void compareKind(Mismatches diff, String at, JsonNode k, KindSetup kind) {
        diff.eq(at + " pickup", k.get("pickup").asBoolean(), kind.pickup());
        diff.eq(at + " maxSize", k.get("maxSize").asInt(), kind.maxSize());

        JsonNode possible = k.get("tierPossible");
        for (int t = 0; t < possible.size(); t++) {
            diff.eq(at + " tierPossible[" + t + "]", possible.get(t).asBoolean(), kind.tierPossible(t));
        }

        boolean goldenPossible = k.get("goldenPossible").asBoolean();
        diff.eq(at + " goldenPossible", goldenPossible, kind.goldenPossible());
        diff.eq(at + " timeChance", f(k.get("timeChance")), kind.timeChance());

        JsonNode golden = k.get("golden");
        Optional<GoldenTrait> actual = kind.golden();
        if (golden.isNull()) {
            diff.eq(at + " golden 없음", true, actual.isEmpty());
        } else if (actual.isEmpty()) {
            diff.eq(at + " golden 있음", true, false);
        } else {
            diff.eq(at + " golden.multiplier", f(golden.get("multiplier")), actual.get().multiplier());
            diff.eq(at + " golden.critChance", f(golden.get("critChance")), actual.get().critChance());
            diff.eq(at + " golden.critRewardScale", f(golden.get("critRewardScale")), actual.get().critRewardScale());
        }

        // 황금 개체 표는 황금이 나올 수 있을 때만 있다(그렇지 않으면 서버도 goldOf(…, true)가 예외다)
        JsonNode gold = k.get("gold");
        JsonNode exp = k.get("exp");
        JsonNode goldGolden = k.get("goldGolden");
        JsonNode critBonus = k.get("critBonus");
        diff.eq(at + " goldGolden 없음 == !goldenPossible", !goldenPossible, goldGolden.isNull());
        diff.eq(at + " critBonus 없음 == !goldenPossible", !goldenPossible, critBonus.isNull());

        for (int t = 0; t < gold.size(); t++) {
            for (int z = 0; z < gold.get(t).size(); z++) {
                String cell = "[" + t + "][" + z + "]";
                int size = z + 1;
                diff.eq(at + " gold" + cell, gold.get(t).get(z).longValue(), kind.goldOf(t, size, false));
                diff.eq(at + " exp" + cell, exp.get(t).get(z).longValue(), kind.expOf(t, size));
                if (goldenPossible) {
                    diff.eq(at + " goldGolden" + cell, goldGolden.get(t).get(z).longValue(), kind.goldOf(t, size, true));
                    diff.eq(at + " critBonus" + cell, critBonus.get(t).get(z).longValue(), kind.goldenCritBonusOf(t, size));
                }
            }
        }
    }

    private static UpgradeValues upgradesOf(JsonNode loadout) {
        Map<String, Integer> nodes = new LinkedHashMap<>();
        loadout.get("nodes").properties().forEach(e -> nodes.put(e.getKey(), e.getValue().asInt()));
        return rules.upgradesFor(nodes);
    }

    private static List<String> supplyOf(JsonNode supply) {
        List<String> list = new ArrayList<>();
        supply.forEach(e -> list.add(e.get("enemyId").asString() + " x" + e.get("count").asInt()));
        return list;
    }

    private static List<String> supplyOf(List<SupplyEntry> supply) {
        List<String> list = new ArrayList<>();
        supply.forEach(e -> list.add(e.enemyId() + " x" + e.count()));
        return list;
    }

    // JSON의 수를 float으로(BigDecimal에서 한 번에 반올림해 C#의 float.Parse와 같은 값을 얻는다)
    private static float f(JsonNode node) {
        return node.decimalValue().floatValue();
    }

    private static byte[] read(String resource) {
        try (InputStream in = RulesTableTests.class.getResourceAsStream(resource)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 불일치를 모아 한 번에 알린다(처음 하나에서 멈추지 않는다). 비교한 개수도 함께 센다. */
    private static final class Mismatches {

        private final List<String> found = new ArrayList<>();
        private int compared;

        void eq(String what, Object expected, Object actual) {
            compared++;
            boolean same = expected instanceof Float e && actual instanceof Float a
                    ? Float.floatToIntBits(e) == Float.floatToIntBits(a)
                    : expected.equals(actual);
            if (!same) {
                found.add(what + ": 기대(C#) " + expected + " / 서버 " + actual);
            }
        }

        void assertNone() {
            assertThat(compared).as("비교한 값이 있어야 한다").isPositive();
            assertThat(found)
                    .as("불일치 %d개 / 비교 %d개 (앞 %d개만 표시)", found.size(), compared, SHOWN)
                    .withFailMessage(() -> "불일치 " + found.size() + "개 / 비교 " + compared + "개 (앞 " + SHOWN + "개만 표시)\n  "
                            + String.join("\n  ", found.subList(0, Math.min(SHOWN, found.size()))))
                    .isEmpty();
        }
    }
}
