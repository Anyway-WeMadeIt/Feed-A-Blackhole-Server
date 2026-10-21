package org.example.feedablackhole.sync.repository;

import jakarta.persistence.QueryHint;
import java.util.Optional;
import org.example.feedablackhole.sync.entity.BattleRun;
import org.hibernate.jpa.HibernateHints;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.QueryHints;

public interface BattleRunRepository extends JpaRepository<BattleRun, Long> {

    /**
     * 이 계정에서 이미 쓴 runId인가(DUPLICATE_RUN_ID 검사).
     *
     * <p>flush를 하지 않는다(COMMIT). 기본값(AUTO)이면 이 조회 직전마다 바뀐 엔티티를 모두 훑어 UPDATE를 내보내서, 동기화 요청에
     * RUN_START가 많을수록 쿼리와 CPU가 로그 수만큼 늘었다. 이 요청 안에서 만든 판(BattleRun)은 IDENTITY라 저장하는 즉시 INSERT되어
     * flush 없이도 조회에 보이고, 나머지 변경은 커밋 때 한 번에 반영된다.
     */
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FLUSH_MODE, value = "COMMIT"))
    boolean existsByAccountIdAndRunId(Long accountId, String runId);

    Optional<BattleRun> findByAccountIdAndRunId(Long accountId, String runId);

}
