package org.example.feedablackhole.progress.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.example.feedablackhole.progress.entity.PlayerProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlayerProgressRepository extends JpaRepository<PlayerProgress, Long> {

    /**
     * 진행 상태를 바꾸기 위한 조회.
     * 행을 잠가서(SELECT ... FOR UPDATE) 같은 계정의 요청이 한 번에 하나씩만 처리되게 한다.(@Lock)
     *      잠그지 않으면 두 요청이 같은 revision을 보고 둘 다 저장해 한쪽이 덮어써질 수 있다.
     * 메서드 명은 의미를 호출자에게 알리는 용도 뿐이다. @Query와 @Lock이 실제 동작을 결정한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PlayerProgress p where p.accountId = :accountId")
    Optional<PlayerProgress> findForUpdateByAccountId(@Param("accountId") Long accountId);

}
