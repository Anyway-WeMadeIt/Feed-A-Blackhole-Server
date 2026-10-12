package org.example.feedablackhole.sync.repository;

import java.util.Optional;
import org.example.feedablackhole.sync.entity.BattleRun;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BattleRunRepository extends JpaRepository<BattleRun, Long> {

    /** 이 계정에서 이미 쓴 runId인가(DUPLICATE_RUN_ID 검사). */
    boolean existsByAccountIdAndRunId(Long accountId, String runId);

    Optional<BattleRun> findByAccountIdAndRunId(Long accountId, String runId);

}
