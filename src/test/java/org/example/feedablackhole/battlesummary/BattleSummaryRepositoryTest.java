package org.example.feedablackhole.battlesummary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.example.feedablackhole.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

// 실제 MySQL(Testcontainers)에 V1 스키마로 저장해 본다.
@DataJpaTest
@Import(TestcontainersConfiguration.class)
class BattleSummaryRepositoryTest {

    @Autowired
    private BattleSummaryRepository repository;

    @Test
    void savesSummary() {
        String battleId = UUID.randomUUID().toString();

        repository.saveAndFlush(summary(battleId));

        assertThat(repository.existsByBattleId(battleId)).isTrue();
    }

    @Test
    void rejectsSameBattleIdTwice() {
        String battleId = UUID.randomUUID().toString();
        repository.saveAndFlush(summary(battleId));

        assertThatThrownBy(() -> repository.saveAndFlush(summary(battleId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static BattleSummary summary(String battleId) {
        Instant now = Instant.now();
        String rawJson = "{\"battleId\":\"" + battleId + "\"}";
        return new BattleSummary(battleId, UUID.randomUUID().toString(), 1, "1.0", "", 1, now, now, rawJson);
    }
}
