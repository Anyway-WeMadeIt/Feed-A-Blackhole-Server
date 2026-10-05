package org.example.feedablackhole.battlesummary;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 전투 한 판의 요약(battle_summary 테이블). 받은 JSON 원문과 조회용 값을 함께 둔다.
@Entity
@Table(name = "battle_summary")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BattleSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 36)
    private String battleId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 36)
    private String installId;

    @Column(nullable = false)
    private int schemaVersion;

    @Column(nullable = false, length = 32)
    private String buildVersion;

    @Column(nullable = false, length = 64)
    private String contentVersion;

    @Column(nullable = false)
    private int battleIndex;

    @Column(nullable = false)
    private Instant startedAt;

    @Column(nullable = false)
    private Instant receivedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String rawJson;

    public BattleSummary(
            String battleId,
            String installId,
            int schemaVersion,
            String buildVersion,
            String contentVersion,
            int battleIndex,
            Instant startedAt,
            Instant receivedAt,
            String rawJson
    ) {
        this.battleId = battleId;
        this.installId = installId;
        this.schemaVersion = schemaVersion;
        this.buildVersion = buildVersion;
        this.contentVersion = contentVersion;
        this.battleIndex = battleIndex;
        this.startedAt = startedAt;
        this.receivedAt = receivedAt;
        this.rawJson = rawJson;
    }
}
