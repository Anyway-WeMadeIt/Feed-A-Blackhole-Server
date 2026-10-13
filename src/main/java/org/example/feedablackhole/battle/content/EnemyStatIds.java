package org.example.feedablackhole.battle.content;

import java.util.Map;

/**
 * 적 종류 하나의 판 구성에 닿는 수치의 statId(클라이언트 EnemyUpgradeStats를 데이터로 내보낸 것).
 * 그 종류에 해당 수치가 없으면 null이다.
 *
 * @param traitChance 성질 id → 그 성질의 생성 확률 statId. 확률 수치가 없는 성질은 키가 없다
 */
public record EnemyStatIds(
        String mass,
        String size,
        String startSupply,
        String growthSupply,
        String upgrade,
        String timeChance,
        Map<String, String> traitChance,
        String goldenMultiplier,
        String goldenCritChance,
        String goldenCritRewardScale) {

    public EnemyStatIds {
        traitChance = Map.copyOf(traitChance);
    }

}
