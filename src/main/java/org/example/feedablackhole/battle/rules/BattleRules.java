package org.example.feedablackhole.battle.rules;

import java.util.Map;

/**
 * 판 하나의 설정을 계산하는 규칙(클라이언트 NodePurchase.StatsFor, GameSessionFactory.Create 중 검증에 필요한 부분).
 * 계약은 docs/server-sync-contracts.md 1.6, 수치 일치 규칙은 1.7. 구현은 W1-4.
 */
public interface BattleRules {

    /** 산 노드 Rank(nodeId → rank, 산 노드만)로 수치 값을 계산한다. */
    UpgradeValues upgradesFor(Map<String, Integer> nodeRanks);

    /** 성장도와 수치 값으로 이번 판의 설정을 계산한다. */
    RunSetup setupFor(int growthStage, UpgradeValues upgrades);

}
