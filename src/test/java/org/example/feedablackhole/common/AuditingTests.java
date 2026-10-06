package org.example.feedablackhole.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.example.feedablackhole.TestcontainersConfiguration;
import org.example.feedablackhole.account.entity.Account;
import org.example.feedablackhole.account.repository.AccountRepository;
import org.example.feedablackhole.common.config.JpaAuditingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * created_at이 저장 시점에 Clock 빈의 시각으로 채워지는지 확인한다.
 * 시각을 고정한 Clock을 쓰므로 값을 정확히 비교할 수 있다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class, AuditingTests.FixedClockConfig.class})
class AuditingTests {

    private static final Instant FIXED = Instant.parse("2026-10-06T09:30:15.123456Z");

    @Autowired
    private AccountRepository accountRepository;

    @Test
    void fillsCreatedAtFromClockOnSave() {
        Account saved = accountRepository.saveAndFlush(Account.create());

        assertThat(saved.getCreatedAt()).isEqualTo(FIXED);
    }

    @Test
    void keepsCreatedAtWhenEntityIsUpdated() {
        Account saved = accountRepository.saveAndFlush(Account.create());

        saved.markLoggedIn(FIXED.plusSeconds(60));
        accountRepository.saveAndFlush(saved);

        assertThat(accountRepository.findById(saved.getId()).orElseThrow().getCreatedAt()).isEqualTo(FIXED);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {

        @Bean
        Clock clock() {
            return Clock.fixed(FIXED, ZoneOffset.UTC);
        }

    }

}
