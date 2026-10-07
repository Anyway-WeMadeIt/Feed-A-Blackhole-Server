package org.example.feedablackhole.progress.repository;

import java.util.List;
import org.example.feedablackhole.progress.entity.PlayerNodeRank;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlayerNodeRankRepository extends JpaRepository<PlayerNodeRank, Long> {

    /**
     * 계정이 산 노드. id 순서라 처음 산 순서다.
     */
    List<PlayerNodeRank> findAllByAccountIdOrderByIdAsc(Long accountId);

    /**
     * 계정의 산 노드를 모두 지운다(새 게임).
     * 이름 규칙으로만 선언하면, 먼저 행을 모두 조회한 뒤 하나씩 DELETE를 보낸다.(N+1)
     *      따라서 @Query를 사용해 벌크 연산을 수행하도록 한다.
     * Spring Data는 @Query를 기본적으로 조회(SELECT) 로 가정하고 실행한다.
     *      어노테이션을 통한 벌크 연산 시 @Modifying이 필수적이다.
     *      벌크 연산은 영속성 컨텍스트를 거치지 않고 DB에 직접 쿼리를 실행하낟.
     *      DB에는 반영되지만 영속성 컨텍스트에 반영되지 않아 데이터 불일치가 발생할 수 있다.
     * flushAutomatically는 쿼리 실행 전 캐시의 변경 내용을 DB에 반영하도록 한다.
     * clearAutomatically는 쿼리 실행 후 캐시를 비워서 이후 조회가 DB를 다시 읽도록 한다.
     *      같은 트랜잭션에서 기존에 영속성 컨텍스트로 관리되던 객체의 관리가 끊어진다.
     *      객체 수정을 다 마치고 이 요청을 마지막으로 수행해야 안전하게 DB에 반영된다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PlayerNodeRank n where n.account.id = :accountId")
    void deleteAllByAccountId(@Param("accountId") Long accountId);

}
