package org.example.feedablackhole.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.example.feedablackhole.TestcontainersConfiguration;
import org.example.feedablackhole.account.entity.Account;
import org.example.feedablackhole.account.repository.AccountRepository;
import org.example.feedablackhole.auth.entity.AuthIdentity;
import org.example.feedablackhole.auth.entity.AuthIdentityType;
import org.example.feedablackhole.auth.entity.RefreshToken;
import org.example.feedablackhole.auth.repository.AuthIdentityRepository;
import org.example.feedablackhole.auth.repository.RefreshTokenRepository;
import org.example.feedablackhole.common.config.ClockConfig;
import org.example.feedablackhole.common.config.JpaAuditingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Flyway가 만든 실제 MySQL 스키마와 엔티티·제약 조건이 맞는지 확인한다.
 * (ddl-auto=validate 이므로 엔티티와 스키마가 다르면 컨텍스트 로드부터 실패한다.)
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, ClockConfig.class, JpaAuditingConfig.class})
class AuthSchemaTests {

    private static final Instant NOW = Instant.now();

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AuthIdentityRepository authIdentityRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Test
    void savesAccountWithGuestIdentity() {
        Account account = accountRepository.save(Account.create());
        authIdentityRepository.saveAndFlush(AuthIdentity.guest(account, "guest-1", "a".repeat(64)));

        AuthIdentity found = authIdentityRepository
                .findByTypeAndIdentifier(AuthIdentityType.GUEST, "guest-1")
                .orElseThrow();

        assertThat(found.getAccount().getId()).isEqualTo(account.getId());
        assertThat(found.getSecretHash()).isEqualTo("a".repeat(64));
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void rejectsDuplicateIdentityOfSameType() {
        Account first = accountRepository.save(Account.create());
        Account second = accountRepository.save(Account.create());
        authIdentityRepository.saveAndFlush(AuthIdentity.guest(first, "guest-dup", "a".repeat(64)));

        assertThatThrownBy(() -> authIdentityRepository.saveAndFlush(
                AuthIdentity.guest(second, "guest-dup", "b".repeat(64))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void savesRefreshTokenAndFindsItByHash() {
        Account account = accountRepository.save(Account.create());
        refreshTokenRepository.saveAndFlush(
                RefreshToken.issue(account, "c".repeat(64), NOW.plus(Duration.ofDays(30))));

        RefreshToken found = refreshTokenRepository.findByTokenHash("c".repeat(64)).orElseThrow();

        assertThat(found.isUsable(NOW)).isTrue();
        assertThat(found.isUsable(NOW.plus(Duration.ofDays(31)))).isFalse();
    }

    @Test
    void revokedRefreshTokenIsNotUsable() {
        Account account = accountRepository.save(Account.create());
        RefreshToken token = RefreshToken.issue(account, "d".repeat(64), NOW.plus(Duration.ofDays(30)));

        token.revoke(NOW);

        assertThat(token.isUsable(NOW)).isFalse();
    }

    @Test
    void rejectsDuplicateRefreshTokenHash() {
        Account account = accountRepository.save(Account.create());
        refreshTokenRepository.saveAndFlush(
                RefreshToken.issue(account, "e".repeat(64), NOW.plus(Duration.ofDays(30))));

        assertThatThrownBy(() -> refreshTokenRepository.saveAndFlush(
                RefreshToken.issue(account, "e".repeat(64), NOW.plus(Duration.ofDays(30)))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

}
