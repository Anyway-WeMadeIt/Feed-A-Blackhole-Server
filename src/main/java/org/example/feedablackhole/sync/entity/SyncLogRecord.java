package org.example.feedablackhole.sync.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.feedablackhole.account.entity.Account;
import org.example.feedablackhole.sync.log.RejectCode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 받은 동기화 로그 하나의 기록(sync_log 테이블, 감사·진단용). 처리 결과와 함께 받은 글자 그대로 남긴다.
 * 같은 seq가 여러 번 올 수 있다(재전송, 거부 뒤 재사용). 적용 여부의 기준은 player_progress.last_applied_seq다.
 * 스키마의 원본은 Flyway SQL(V3), 계약은 docs/server-sync-contracts.md 4.2.
 */
@Entity
@Table(
        name = "sync_log",
        indexes = @Index(name = "ix_sync_log_account_seq", columnList = "account_id, seq"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SyncLogRecord {

    public static final int TYPE_LENGTH = 32;
    public static final int CONTENT_VERSION_LENGTH = 64;
    public static final int BUILD_VERSION_LENGTH = 32;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(nullable = false)
    private long seq;

    // 받은 type 글자. 읽지 못했거나 길이를 넘으면 "".
    @Column(nullable = false, length = TYPE_LENGTH)
    private String type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SyncLogResult result;

    @Enumerated(EnumType.STRING)
    @Column(name = "reject_code", length = 40)
    private RejectCode rejectCode;

    @Column(name = "content_version", nullable = false, length = CONTENT_VERSION_LENGTH)
    private String contentVersion;

    @Column(name = "build_version", nullable = false, length = BUILD_VERSION_LENGTH)
    private String buildVersion;

    // 받은 로그 객체 그대로(JSON).
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_json", nullable = false)
    private String rawJson;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    public static SyncLogRecord of(Account account, long seq, String type, SyncLogResult result, RejectCode rejectCode,
            String contentVersion, String buildVersion, String rawJson, Instant receivedAt) {
        SyncLogRecord record = new SyncLogRecord();
        record.account = account;
        record.seq = seq;
        record.type = fit(type, TYPE_LENGTH);
        record.result = result;
        record.rejectCode = rejectCode;
        record.contentVersion = fit(contentVersion, CONTENT_VERSION_LENGTH);
        record.buildVersion = fit(buildVersion, BUILD_VERSION_LENGTH);
        record.rawJson = rawJson;
        record.receivedAt = receivedAt;
        return record;
    }

    // 읽지 못한 값(null)과 길이를 넘는 값은 ""로 남긴다. 원래 글자는 raw_json에 있다.
    private static String fit(String value, int length) {
        return value == null || value.length() > length ? "" : value;
    }

}
