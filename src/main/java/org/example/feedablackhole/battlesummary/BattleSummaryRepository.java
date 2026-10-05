package org.example.feedablackhole.battlesummary;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BattleSummaryRepository extends JpaRepository<BattleSummary, Long> {

    boolean existsByBattleId(String battleId);
}
