package org.example.feedablackhole.auth.repository;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.example.feedablackhole.auth.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * 갱신용 조회. 행을 잠가서(SELECT ... FOR UPDATE) 같은 토큰으로 동시에 갱신 요청이 와도 한 번에 하나씩만 처리한다.
     * 잠그지 않으면 두 요청이 모두 "아직 폐기되지 않음"으로 보고 새 토큰을 각각 발급할 수 있다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :tokenHash")
    Optional<RefreshToken> findForUpdateByTokenHash(@Param("tokenHash") String tokenHash);

    /**
     * 계정의 아직 폐기되지 않은 리프레시 토큰을 모두 폐기한다. 폐기된 토큰이 다시 쓰였을 때(탈취 의심)의 대응이다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.account.id = :accountId and t.revokedAt is null")
    int revokeAllByAccountId(@Param("accountId") Long accountId, @Param("now") Instant now);

}
