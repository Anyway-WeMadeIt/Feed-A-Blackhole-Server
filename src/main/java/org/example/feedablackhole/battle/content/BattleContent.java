package org.example.feedablackhole.battle.content;

import java.util.List;
import java.util.Optional;

/**
 * 서버가 결산 검증에 쓰는 전투 콘텐츠(content/battle/battle.json). 한 콘텐츠 버전 안에서 바뀌지 않는다.
 * 형식과 원본은 docs/server-sync-contracts.md 1.4~1.5. 검증된 값만 담는다(검증은 로더가 한다).
 *
 * @param enemies     적 종류(battle.json 순서 = 클라이언트 EnemyCatalog 순서)
 * @param startSupply 콘텐츠의 전투 시작 공급(노드 반영 전, 콘텐츠 순서)
 */
public record BattleContent(
        BattleRulesDefinition rules,
        HqGrowthDefinition growth,
        GlobalStatIds stats,
        List<EnemyKindDefinition> enemies,
        List<SupplyEntry> startSupply) {

    public BattleContent {
        enemies = List.copyOf(enemies);
        startSupply = List.copyOf(startSupply);
    }

    public Optional<EnemyKindDefinition> findEnemy(String enemyId) {
        return enemies.stream().filter(enemy -> enemy.enemyId().equals(enemyId)).findFirst();
    }

    /** 없는 종류면 IllegalArgumentException. */
    public EnemyKindDefinition enemy(String enemyId) {
        return findEnemy(enemyId)
                .orElseThrow(() -> new IllegalArgumentException("없는 적 종류다: " + enemyId));
    }

}
