package org.example.feedablackhole.battle.content;

import org.example.feedablackhole.node.content.ContentDiagnostic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 다 만든 BattleContent가 규칙(계약 1.5의 로더 검증)에 맞는지 검사한다.
 * 규칙을 어긴 곳은 처음 하나에서 멈추지 않고 모두 모아서 돌려준다.
 */
public final class BattleContentValidator {
    // 검증 기준: 색 등급(tiers)의 개수 (계약 1.5)
    // 공급 종류(소행성, 행성, 항성, 은하)는 등급이 6개
    private static final int SUPPLY_TIER_COUNT = 6;
    // 수거형(혜성)은 등급 구분 없이 1개
    private static final int PICKUP_TIER_COUNT = 1;

    private BattleContentValidator() {
    }

    /**
     * 모든 규칙을 검사한다.
     *
     * @return 어긴 곳의 목록. 비어 있으면 규칙에 맞는 콘텐츠다
     */
    public static List<ContentDiagnostic> validate(BattleContent content, Set<String> statIds) {
        List<ContentDiagnostic> into = new ArrayList<>(); // 어긴 곳을 모으는 목록(바로 던지지 않는다)

        // 규칙을 하나씩 여기에 추가
        verifyEnemies(content.enemies(), into);
        verifyGrowth(content.growth(), into);
        verifyStartSupply(content, into);

        // statIds가 null이면(노드 콘텐츠를 읽지 못해 알 수 없을 때) 규칙 9는 건너뛴다
        if (statIds != null) {
            verifyStatIds(content, statIds, into);
        }

        return into;
    }

    // ---------- 적 종류 (규칙 1~5) ----------

    // 적 종류에 대한 규칙. 규칙마다 메서드를 나누고, 여기서 차례로 부른다
    private static void verifyEnemies(List<EnemyKindDefinition> enemies, List<ContentDiagnostic> into) {
        // 적의 이름(enemyId)으로 적 객체를 바로 꺼낼 수 있는 표.
        // upgradesTo에는 객체가 아니라 이름만 들어 있어서, 이름으로 적을 찾는 규칙(2, 3)이 쓴다
        Map<String, EnemyKindDefinition> byId = new HashMap<>();
        for (EnemyKindDefinition enemy : enemies) {
            byId.put(enemy.enemyId(), enemy);
        }

        verifyUniqueIds(enemies, into);                // 규칙 1
        verifyUpgradeTargets(enemies, byId, into);     // 규칙 2
        verifyUpgradeChains(enemies, byId, into);      // 규칙 3

        // 규칙 4, 5는 적마다 보는 규칙이라 적 목록을 도는 반복문에서 부른다
        for (EnemyKindDefinition enemy : enemies) {
            verifyTiers(enemy, into);                  // 규칙 4
            verifyGoldenTrait(enemy, into);            // 규칙 5
        }
    }

    // 규칙 1) enemyId가 유일하다 -> 중복 X
    private static void verifyUniqueIds(List<EnemyKindDefinition> enemies, List<ContentDiagnostic> into) {
        Set<String> ids = new HashSet<>();
        for (EnemyKindDefinition enemy : enemies) {
            if (!ids.add(enemy.enemyId())) { // add가 false면 이미 있던 id
                into.add(new ContentDiagnostic("enemies[" + enemy.enemyId() + "]",
                        "적 종류 id '" + enemy.enemyId() + "'가 중복됐다."));
            }
        }
    }

    // 규칙 2) upgradesTo가 존재하는 종류를 가리킨다 (없으면 null이라 검사하지 않는다)
    private static void verifyUpgradeTargets(List<EnemyKindDefinition> enemies,
            Map<String, EnemyKindDefinition> byId,
            List<ContentDiagnostic> into) {
        for (EnemyKindDefinition enemy : enemies) {
            if (enemy.upgradesTo() != null && !byId.containsKey(enemy.upgradesTo())) {
                into.add(new ContentDiagnostic("enemies[" + enemy.enemyId() + "].upgradesTo",
                        "변환 대상 '" + enemy.upgradesTo() + "'가 없는 종류다."));
            }
        }
    }

    // 규칙 3) 변환 사슬에 순환이 없다
    // 모든 적을 한 번씩 시작점으로 삼아, 변환 사슬을 따라가다 시작점으로 돌아오는지 본다
    private static void verifyUpgradeChains(List<EnemyKindDefinition> enemies,
            Map<String, EnemyKindDefinition> byId,
            List<ContentDiagnostic> into) {
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
    }

    // 규칙 4) 색 등급 개수와 Gold·EXP
    private static void verifyTiers(EnemyKindDefinition enemy, List<ContentDiagnostic> into) {
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
                        "Gold가 0 이상이어야 한다."));
            }
            if (enemy.tiers().get(i).exp() < 0) {
                into.add(new ContentDiagnostic("enemies[" + enemy.enemyId() + "].tiers[" + i + "].exp",
                        "EXP가 0 이상이어야 한다."));
            }
        }
    }

    // 규칙 5) golden 성질과 golden 수치는 짝이다
    private static void verifyGoldenTrait(EnemyKindDefinition enemy, List<ContentDiagnostic> into) {
        // 이 종류의 traits에 golden 성질이 있는지 (소행성만 true)
        boolean hasGoldenTrait = enemy.traits().contains(EnemyKindDefinition.GOLDEN);

        // 성질은 있는데 수치(multiplier 등)가 없으면 황금 적의 Gold를 계산할 수 없다
        if (hasGoldenTrait && enemy.golden() == null) {
            into.add(new ContentDiagnostic("enemies[" + enemy.enemyId() + "].golden",
                    "성질이 있으면 수치가 있어야 한다."));
        } else if (!hasGoldenTrait && enemy.golden() != null) {
            // 성질이 없는데 수치만 있으면 쓰이지 않는 수치가 남은 것이므로 JSON이 잘못된 것이다
            into.add(new ContentDiagnostic("enemies[" + enemy.enemyId() + "].golden",
                    "성질이 없으면 수치는 null이어야 한다."));
        }
    }

    // ---------- 성장 (규칙 6, 7) ----------

    /**
     * 성장(growth)에 대한 규칙을 검사한다.
     * 규칙 6(levelExp가 양수이고 증가)과 규칙 7(이정표 Level·targetGold 증가, Level은 사다리 안)을 함께 본다.
     *
     * @param growth Level 사다리(levelExp)와 이정표(milestones)
     * @param into   어긴 곳을 모으는 목록(바로 던지지 않는다)
     */
    private static void verifyGrowth(HqGrowthDefinition growth, List<ContentDiagnostic> into) {
        // 규칙 6) levelExp가 양수이고 증가한다
        List<Long> levelExp = growth.levelExp(); // Level별 누적 EXP. levelExp[i]는 Level i + 1에 닿는 값
        long previousExp = 0; // 앞 Level의 값. 첫 값은 0과 비교해서 "양수"인지도 함께 본다

        for (int i = 0; i < levelExp.size(); i++) {
            // Long(객체)을 long(기본형)으로 꺼내 값으로 비교한다
            long exp = levelExp.get(i);
            // 앞 값보다 커야 한다. 같아도 오류이고, 첫 값이 0 이하여도 걸린다
            if (exp <= previousExp) {
                into.add(new ContentDiagnostic("growth.levelExp[" + i + "]",
                        "앞 Level보다 커야 한다."));
            }
            // 오류가 나도 이번 값으로 갱신한다. 그래야 다음 값이 이번 값과 따로 비교되어 문제가 연쇄로 부풀지 않는다
            previousExp = exp;
        }

        // 규칙 7) 이정표 Level·targetGold가 증가하고, Level은 사다리 안이다
        List<HqMilestone> milestones = growth.milestones(); // 이정표 목록(Level이 커지는 순서)
        int previousLevel = 0;  // 앞 이정표의 Level. 첫 이정표는 0과 비교해서 1 이상인지도 함께 본다
        long previousGold = 0;  // 앞 이정표의 목표 Gold. 첫 이정표는 0과 비교한다

        for (int i = 0; i < milestones.size(); i++) {
            HqMilestone milestone = milestones.get(i);

            // Level은 앞 이정표보다 커야 한다. 같아도 오류이고, 첫 값이 0 이하여도 걸린다
            if (milestone.level() <= previousLevel) {
                into.add(new ContentDiagnostic("growth.milestones[" + i + "].level",
                        "앞 이정표보다 커야 한다."));
            }
            // 오류가 나도 이번 값으로 갱신한다. 그래야 다음 값이 이번 값과 따로 비교되어 문제가 연쇄로 부풀지 않는다
            previousLevel = milestone.level();

            // Level 사다리의 끝(levelExp의 개수)을 넘으면 그 Level에 닿는 EXP가 정의되어 있지 않다.
            // 위의 증가 검사와는 별개의 오류라서 else 없이 따로 검사한다(둘 다 어길 수 있다)
            if (milestone.level() > growth.maxLevel()) {
                into.add(new ContentDiagnostic("growth.milestones[" + i + "].level",
                        "Level이 사다리 밖이다."));
            }

            // 목표 Gold도 앞 이정표보다 커야 한다. 첫 값은 0과 비교해서 양수인지 본다
            if (milestone.targetGold() <= previousGold) {
                into.add(new ContentDiagnostic("growth.milestones[" + i + "].targetGold",
                        "앞 이정표보다 커야 한다."));
            }
            previousGold = milestone.targetGold();
        }

        // 은하 시작 성장도는 0(없음)부터 이정표 수까지다
        if (growth.galaxyStage() < 0 || growth.galaxyStage() > growth.maxStage()) {
            into.add(new ContentDiagnostic("growth.galaxyStage",
                    "0(없음)부터 " + growth.maxStage() + "(이정표 수)까지다."));
        }
    }

    // ---------- 시작 공급 (규칙 8) ----------

    /**
     * 시작 공급(startSupply)에 대한 규칙(규칙 8)을 검사한다.
     * 공급 한 건마다 (1) 종류가 존재하고 (2) 수거형이 아니고 (3) 마릿수가 0 이상인지 본다.
     *
     * @param content 시작 공급 목록과, 종류를 id로 찾는 findEnemy를 가진 전투 콘텐츠
     * @param into    어긴 곳을 모으는 목록(바로 던지지 않는다)
     */
    private static void verifyStartSupply(BattleContent content, List<ContentDiagnostic> into) {
        List<SupplyEntry> startSupplies = content.startSupply(); // 판 시작 때의 공급 목록(노드 반영 전)

        for (int i = 0; i < startSupplies.size(); i++) {
            SupplyEntry startSupply = startSupplies.get(i);

            // 이 공급이 가리키는 적 종류를 찾는다. 없으면 빈 Optional이 돌아온다
            Optional<EnemyKindDefinition> found = content.findEnemy(startSupply.enemyId());
            if (found.isEmpty()) {
                // 없는 종류: 종류 자체가 없으니 pickup()을 볼 수 없다. 그래서 수거형 검사는 else if로 분리했다
                into.add(new ContentDiagnostic("startSupply[" + i + "].enemyId",
                        "없는 적 종류다: '" + startSupply.enemyId() + "'."));
            } else if (found.get().pickup()) {
                // 수거형(혜성)은 주기적으로 나타나는 적이라 시작 공급에 쓸 수 없다.
                // else 쪽에서만 get()을 부르므로, 빈 Optional에서 꺼내다 터지는 일은 없다
                into.add(new ContentDiagnostic("startSupply[" + i + "].enemyId",
                        "수거형 '" + startSupply.enemyId() + "'는 시작 공급에 쓸 수 없다."));
            }

            // 마릿수는 종류와 무관한 별개의 검사라서 위의 if 밖에 둔다(종류가 틀려도 마릿수 오류는 따로 보고된다).
            // 0은 정상이고 음수만 오류다
            if (startSupply.count() < 0) {
                into.add(new ContentDiagnostic("startSupply[" + i + "].count",
                        "적의 수는 0 이상이어야 한다."));
            }
        }
    }

    /**
     * statId 하나가 시트에 있는지 본다. 이 검사를 값마다 반복해서 부른다.
     *
     * @param statId 검사할 값. null이면 "그 종류에 해당 수치가 없다"는 뜻이라 검사하지 않는다
     * @param at     그 값이 battle.json의 어디에 있는지(진단의 위치)
     */
    private static void verifyStatId(String statId, String at, Set<String> statIds, List<ContentDiagnostic> into) {
        if (statId == null) {
            return; // 해당 수치가 없는 종류(예: 행성의 startSupply)는 검사할 것이 없다
        }

        if (!statIds.contains(statId)) {
            into.add(new ContentDiagnostic(at, "UpgradeStats 시트에 없는 수치 '" + statId + "'다."));
        }
    }

    /**
     * battle.json이 가리키는 statId가 모두 UpgradeStats 시트에 있는지 검사한다(규칙 9).
     *
     * @param statIds UpgradeStats 시트의 StatId 전체
     */
    private static void verifyStatIds(BattleContent content, Set<String> statIds, List<ContentDiagnostic> into) {
        // 적 종류에 속하지 않는 수치(제한 시간, Level업당 시간)
        verifyStatId(content.stats().timer(), "stats.timer", statIds, into);
        verifyStatId(content.stats().growthTime(), "stats.growthTime", statIds, into);

        // 적 종류마다 판 구성에 닿는 수치
        for (EnemyKindDefinition enemy : content.enemies()) {
            String at = "enemies[" + enemy.enemyId() + "].stats"; // 이 종류의 stats 위치(뒤에 키 이름을 붙인다)
            EnemyStatIds stats = enemy.stats();

            verifyStatId(stats.mass(), at + ".mass", statIds, into);
            verifyStatId(stats.size(), at + ".size", statIds, into);
            verifyStatId(stats.startSupply(), at + ".startSupply", statIds, into);
            verifyStatId(stats.growthSupply(), at + ".growthSupply", statIds, into);
            verifyStatId(stats.upgrade(), at + ".upgrade", statIds, into);
            verifyStatId(stats.timeChance(), at + ".timeChance", statIds, into);
            verifyStatId(stats.goldenMultiplier(), at + ".goldenMultiplier", statIds, into);
            verifyStatId(stats.goldenCritChance(), at + ".goldenCritChance", statIds, into);
            verifyStatId(stats.goldenCritRewardScale(), at + ".goldenCritRewardScale", statIds, into);

            // 성질별 생성 확률 수치(성질 id -> statId). 어느 성질인지 위치에 성질 id를 적는다
            for (Map.Entry<String, String> chance : stats.traitChance().entrySet()) {
                verifyStatId(chance.getValue(), at + ".traitChance." + chance.getKey(), statIds, into);
            }
        }
    }
}
