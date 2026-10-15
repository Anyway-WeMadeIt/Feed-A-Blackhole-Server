package org.example.feedablackhole.sync.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.feedablackhole.account.entity.Account;
import org.example.feedablackhole.common.entity.BaseTimeEntity;

/**
 * 판 하나. RUN_START로 열고, SETTLEMENT로 결산하거나 다음 RUN_START·RESET이 포기로 닫는다.
 * 스키마의 원본은 Flyway SQL(V3), 계약은 docs/server-sync-contracts.md 4.2.
 * 시작 Gold·성장도와 결산 값은 서버 진행도와 서버 계산 기준이다(클라이언트가 보낸 값이 아님).
 */
@Entity
@Table(
        name = "battle_run",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_battle_run_account_run",
                columnNames = {"account_id", "run_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BattleRun extends BaseTimeEntity {

    public static final int SUSPICIOUS_REASONS_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    // 클라이언트가 만든 소문자 UUID. 계정 안에서 유일하다.
    @Column(name = "run_id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String runId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BattleRunStatus status;

    @Column(nullable = false)
    private int seed;

    @Column(name = "start_growth_stage", nullable = false)
    private int startGrowthStage;

    @Column(name = "start_gold", nullable = false)
    private long startGold;

    @Column(name = "started_seq", nullable = false)
    private long startedSeq;

    @Column(name = "ended_seq")
    private Long endedSeq;

    @Column(name = "settled_gold")
    private Long settledGold;

    @Column(name = "next_growth_stage")
    private Integer nextGrowthStage;

    // 의심 사유 코드(SuspicionReason 이름)를 쉼표로 이은 것. 없으면 null.
    @Column(name = "suspicious_reasons", length = SUSPICIOUS_REASONS_LENGTH)
    private String suspiciousReasons;

    /** 열린 판. */
    public static BattleRun open(Account account, String runId, int seed, int startGrowthStage, long startGold,
            long startedSeq, String suspiciousReasons) {
        BattleRun run = new BattleRun();
        run.account = account;
        run.runId = runId;
        run.status = BattleRunStatus.OPEN;
        run.seed = seed;
        run.startGrowthStage = startGrowthStage;
        run.startGold = startGold;
        run.startedSeq = startedSeq;
        run.suspiciousReasons = suspiciousReasons;
        return run;
    }

    /** 결산 완료로 닫는다. */
    public void settle(long endedSeq, long settledGold, int nextGrowthStage) {
        requireOpen();
        this.status = BattleRunStatus.SETTLED;
        this.endedSeq = endedSeq;
        this.settledGold = settledGold;
        this.nextGrowthStage = nextGrowthStage;
    }

    /** 결산 없이 닫는다(다음 RUN_START·RESET·NODE_PURCHASE, 판 도중 앱 종료). */
    public void abandon(long endedSeq) {
        requireOpen();
        this.status = BattleRunStatus.ABANDONED;
        this.endedSeq = endedSeq;
    }

    private void requireOpen() {
        if (status != BattleRunStatus.OPEN) {
            throw new IllegalStateException("열린 판이 아니다. runId=" + runId + ", status=" + status);
        }
    }

}
