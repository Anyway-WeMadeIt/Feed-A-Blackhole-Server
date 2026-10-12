package org.example.feedablackhole.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.Instant;
import org.example.feedablackhole.TestcontainersConfiguration;
import org.example.feedablackhole.account.entity.Account;
import org.example.feedablackhole.account.repository.AccountRepository;
import org.example.feedablackhole.common.config.ClockConfig;
import org.example.feedablackhole.common.config.JpaAuditingConfig;
import org.example.feedablackhole.progress.entity.PlayerProgress;
import org.example.feedablackhole.progress.repository.PlayerProgressRepository;
import org.example.feedablackhole.sync.entity.BattleRun;
import org.example.feedablackhole.sync.entity.BattleRunStatus;
import org.example.feedablackhole.sync.entity.SyncLogRecord;
import org.example.feedablackhole.sync.entity.SyncLogResult;
import org.example.feedablackhole.sync.log.RejectCode;
import org.example.feedablackhole.sync.repository.BattleRunRepository;
import org.example.feedablackhole.sync.repository.SyncLogRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Flyway가 만든 실제 MySQL 스키마(V3)와 동기화 엔티티·제약 조건이 맞는지 확인한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, ClockConfig.class, JpaAuditingConfig.class})
class SyncSchemaTests {

    private static final String RUN_ID = "6f1c2b0a-3d4e-4f5a-8b6c-7d8e9f0a1b2c";

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PlayerProgressRepository progressRepository;

    @Autowired
    private BattleRunRepository runRepository;

    @Autowired
    private SyncLogRecordRepository logRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void newProgressStartsWithNoAppliedLogsAndNoOpenRun() {
        Account account = accountRepository.save(Account.create());

        PlayerProgress saved = progressRepository.saveAndFlush(PlayerProgress.initial(account));

        assertThat(saved.getLastAppliedSeq()).isZero();
        assertThat(saved.getOpenRunId()).isNull();
        assertThat(saved.getSuspiciousCount()).isZero();
    }

    @Test
    void runIsSavedOpenAndCanBeSettled() {
        Account account = accountRepository.save(Account.create());
        BattleRun run = runRepository.saveAndFlush(BattleRun.open(account, RUN_ID, 123456, 0, 0, 1, null));

        run.settle(2, 20, 0);
        runRepository.flush();

        BattleRun found = runRepository.findByAccountIdAndRunId(account.getId(), RUN_ID).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(BattleRunStatus.SETTLED);
        assertThat(found.getSettledGold()).isEqualTo(20);
        assertThat(found.getEndedSeq()).isEqualTo(2);
        assertThat(runRepository.existsByAccountIdAndRunId(account.getId(), RUN_ID)).isTrue();
    }

    @Test
    void closedRunCannotBeClosedAgain() {
        Account account = accountRepository.save(Account.create());
        BattleRun run = BattleRun.open(account, RUN_ID, 1, 0, 0, 1, null);
        run.abandon(2);

        assertThatThrownBy(() -> run.settle(3, 0, 0)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void runIdIsUniqueWithinAnAccountOnly() {
        Account first = accountRepository.save(Account.create());
        Account second = accountRepository.save(Account.create());
        runRepository.saveAndFlush(BattleRun.open(first, RUN_ID, 1, 0, 0, 1, null));
        runRepository.saveAndFlush(BattleRun.open(second, RUN_ID, 1, 0, 0, 1, null));

        assertThatThrownBy(() -> runRepository.saveAndFlush(BattleRun.open(first, RUN_ID, 1, 0, 0, 2, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void openRunIdMustPointToAnExistingRun() {
        Account account = accountRepository.save(Account.create());
        progressRepository.saveAndFlush(PlayerProgress.initial(account));

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE player_progress SET open_run_id = 999999 WHERE account_id = ?", account.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void logRecordKeepsRawJsonAndBlanksUnreadableText() {
        Account account = accountRepository.save(Account.create());
        String raw = "{\"seq\":3,\"type\":\"" + "X".repeat(40) + "\"}";

        logRepository.saveAndFlush(SyncLogRecord.of(account, 3, "X".repeat(40), SyncLogResult.REJECTED,
                RejectCode.MALFORMED_LOG, null, "0.4.0", raw, Instant.parse("2026-10-12T10:00:00Z")));
        logRepository.saveAndFlush(SyncLogRecord.of(account, 3, "RESET", SyncLogResult.APPLIED,
                null, "4d6c398ad1f5e49c", "0.4.0", "{\"seq\":3}", Instant.parse("2026-10-12T10:00:01Z")));

        assertThat(logRepository.findAllByAccountIdOrderByIdAsc(account.getId()))
                .extracting(SyncLogRecord::getType, SyncLogRecord::getContentVersion, SyncLogRecord::getRejectCode)
                .containsExactly(
                        tuple("", "", RejectCode.MALFORMED_LOG),
                        tuple("RESET", "4d6c398ad1f5e49c", null));
    }

}
