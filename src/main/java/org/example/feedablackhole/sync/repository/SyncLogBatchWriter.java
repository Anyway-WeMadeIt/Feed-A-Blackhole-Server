package org.example.feedablackhole.sync.repository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.example.feedablackhole.sync.entity.SyncLogRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 받은 로그 기록(sync_log)을 한 번에 삽입한다.
 *
 * <p>sync_log의 id는 AUTO_INCREMENT(IDENTITY)라서 JPA는 행마다 INSERT를 따로 보낸다(id를 받아야 해서 배치가 꺼진다).
 * 요청 하나에 로그가 최대 500개라서, 왕복 수와 CPU가 로그 수만큼 늘었다(부하 테스트, loadtest/README.md).
 * 여기서는 JDBC 배치로 보내고, 드라이버의 rewriteBatchedStatements가 이를 다중 행 INSERT 하나로 합친다.
 * 같은 DataSource라 호출한 서비스의 트랜잭션에 참여한다(커밋과 롤백이 함께). 행 순서가 곧 id 순서다.
 */
@Repository
@RequiredArgsConstructor
public class SyncLogBatchWriter {

    private static final String INSERT = """
            INSERT INTO sync_log (account_id, seq, type, result, reject_code, content_version, build_version, raw_json, received_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private final JdbcTemplate jdbc;

    public void insertAll(long accountId, List<SyncLogRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(INSERT, records, records.size(), (ps, record) -> {
            ps.setLong(1, accountId);
            ps.setLong(2, record.getSeq());
            ps.setString(3, record.getType());
            ps.setString(4, record.getResult().name());
            ps.setString(5, record.getRejectCode() == null ? null : record.getRejectCode().name());
            ps.setString(6, record.getContentVersion());
            ps.setString(7, record.getBuildVersion());
            ps.setString(8, record.getRawJson());
            // Hibernate가 hibernate.jdbc.time_zone=UTC로 저장하는 것과 같은 값(UTC 시각 그대로)을 쓴다.
            ps.setObject(9, LocalDateTime.ofInstant(record.getReceivedAt(), ZoneOffset.UTC));
        });
    }

}
