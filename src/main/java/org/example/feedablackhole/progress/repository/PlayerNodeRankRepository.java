package org.example.feedablackhole.progress.repository;

import org.example.feedablackhole.progress.entity.PlayerNodeRank;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PlayerNodeRankRepository extends JpaRepository<PlayerNodeRank, Long> {

    /**
     * 계정이 산 노드. id 순서라 처음 산 순서다.
     */
    List<PlayerNodeRank> findAllByAccountIdOrderByIdAsc(Long accountId);

}
