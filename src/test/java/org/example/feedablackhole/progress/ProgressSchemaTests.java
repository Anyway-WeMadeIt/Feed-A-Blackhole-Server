package org.example.feedablackhole.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.example.feedablackhole.TestcontainersConfiguration;
import org.example.feedablackhole.account.entity.Account;
import org.example.feedablackhole.account.repository.AccountRepository;
import org.example.feedablackhole.common.config.ClockConfig;
import org.example.feedablackhole.common.config.JpaAuditingConfig;
import org.example.feedablackhole.progress.entity.PlayerNodeRank;
import org.example.feedablackhole.progress.entity.PlayerProgress;
import org.example.feedablackhole.progress.repository.PlayerNodeRankRepository;
import org.example.feedablackhole.progress.repository.PlayerProgressRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Flyway가 만든 실제 MySQL 스키마(V2)와 진행 상태 엔티티·제약 조건이 맞는지 확인한다.
 * (ddl-auto=validate는 테이블·컬럼의 존재와 타입, 유니크 제약이 다르면 컨텍스트 로드부터 실패시킨다.
 * 컬럼 길이, NOT NULL, 외래키는 검증하지 않으므로, 제약이 DB에서 실제로 동작하는지는 이 테스트의 저장 케이스로 확인한다.)
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, ClockConfig.class, JpaAuditingConfig.class})
class ProgressSchemaTests {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PlayerProgressRepository progressRepository;

    @Autowired
    private PlayerNodeRankRepository nodeRankRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void initialProgressSharesTheAccountIdAndStartsFromTheBeginning() {
        Account account = accountRepository.save(Account.create());

        PlayerProgress saved = progressRepository.saveAndFlush(PlayerProgress.initial(account));

        assertThat(saved.getAccountId()).isEqualTo(account.getId());
        assertThat(saved.getGold()).isZero();
        assertThat(saved.getGrowthStage()).isEqualTo(PlayerProgress.INITIAL_GROWTH_STAGE);
        assertThat(saved.getRevision()).isZero();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void progressOfTheSameAccountCannotBeCreatedTwice() {
        Account account = accountRepository.save(Account.create());
        progressRepository.saveAndFlush(PlayerProgress.initial(account));

        // 같은 PK(account_id)의 행을 SQL로 한 번 더 넣으면 거부된다.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO player_progress (account_id, created_at, updated_at) VALUES (?, NOW(6), NOW(6))",
                account.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void updateBumpsTheRevision() {
        Account account = accountRepository.save(Account.create());
        PlayerProgress progress = progressRepository.saveAndFlush(PlayerProgress.initial(account));

        progress.update(500, 1);
        progressRepository.saveAndFlush(progress);

        PlayerProgress found = progressRepository.findById(account.getId()).orElseThrow();
        assertThat(found.getGold()).isEqualTo(500);
        assertThat(found.getGrowthStage()).isEqualTo(1);
        assertThat(found.getRevision()).isEqualTo(1);
    }

    @Test
    void databaseRejectsNegativeGoldAndGrowthStage() {
        Account account = accountRepository.save(Account.create());
        progressRepository.saveAndFlush(PlayerProgress.initial(account));

        // MySQL의 CHECK 위반(오류 3819)은 Spring이 DataIntegrityViolationException으로 옮기지 않아 UncategorizedSQLException이다.
        // 어떤 제약이 걸렸는지를 메시지의 제약 이름으로 확인한다.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE player_progress SET gold = -1 WHERE account_id = ?", account.getId()))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_player_progress_gold");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE player_progress SET growth_stage = -1 WHERE account_id = ?", account.getId()))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_player_progress_growth_stage");
    }

    @Test
    void nodeRanksKeepTheOrderTheyWereBought() {
        Account account = accountRepository.save(Account.create());
        nodeRankRepository.save(PlayerNodeRank.of(account, "timer-02", 1));
        nodeRankRepository.save(PlayerNodeRank.of(account, "timer-01", 3));
        nodeRankRepository.flush();

        assertThat(nodeRankRepository.findAllByAccountIdOrderByIdAsc(account.getId()))
                .extracting(PlayerNodeRank::getNodeId)
                .containsExactly("timer-02", "timer-01");
    }

    @Test
    void sameNodeCannotBeSavedTwiceForOneAccount() {
        Account account = accountRepository.save(Account.create());
        nodeRankRepository.saveAndFlush(PlayerNodeRank.of(account, "timer-01", 1));

        assertThatThrownBy(() -> nodeRankRepository.saveAndFlush(PlayerNodeRank.of(account, "timer-01", 2)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameNodeCanBelongToDifferentAccounts() {
        Account first = accountRepository.save(Account.create());
        Account second = accountRepository.save(Account.create());
        nodeRankRepository.saveAndFlush(PlayerNodeRank.of(first, "timer-01", 1));

        nodeRankRepository.saveAndFlush(PlayerNodeRank.of(second, "timer-01", 1));

        assertThat(nodeRankRepository.findAllByAccountIdOrderByIdAsc(first.getId())).hasSize(1);
        assertThat(nodeRankRepository.findAllByAccountIdOrderByIdAsc(second.getId())).hasSize(1);
    }

    @Test
    void databaseRejectsRankBelowOne() {
        Account account = accountRepository.save(Account.create());
        nodeRankRepository.saveAndFlush(PlayerNodeRank.of(account, "timer-01", 1));

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE player_node_rank SET node_rank = 0 WHERE account_id = ?", account.getId()))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_player_node_rank_rank");
    }

}
