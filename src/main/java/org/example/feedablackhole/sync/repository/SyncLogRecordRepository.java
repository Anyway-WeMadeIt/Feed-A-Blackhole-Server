package org.example.feedablackhole.sync.repository;

import java.util.List;
import org.example.feedablackhole.sync.entity.SyncLogRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncLogRecordRepository extends JpaRepository<SyncLogRecord, Long> {

    /** 계정이 보낸 로그 기록(받은 순서). 진단과 테스트용. */
    List<SyncLogRecord> findAllByAccountIdOrderByIdAsc(Long accountId);

}
