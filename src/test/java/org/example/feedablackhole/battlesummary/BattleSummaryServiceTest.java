package org.example.feedablackhole.battlesummary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

// 저장소를 가짜(Mockito)로 바꿔 서비스의 갈래만 확인한다. DB와 Docker 없이 돈다.
// 실제 MySQL에서의 동작은 저장소 테스트와 이후 API 통합 테스트가 맡는다.
class BattleSummaryServiceTest {

    private final BattleSummaryRepository repository = mock(BattleSummaryRepository.class);
    private final BattleSummaryService service = new BattleSummaryService(repository);
    private final BattleSummary summary = summary();

    // 처음 온 판은 저장한다.
    @Test
    void createsNewSummary() {
        when(repository.existsByBattleId(summary.getBattleId())).thenReturn(false);

        assertThat(service.receive(summary)).isEqualTo(ReceiveResult.CREATED);
        verify(repository).saveAndFlush(summary);
    }

    // 이미 받은 판은 저장하지 않는다.
    @Test
    void skipsSummaryAlreadyReceived() {
        when(repository.existsByBattleId(summary.getBattleId())).thenReturn(true);

        assertThat(service.receive(summary)).isEqualTo(ReceiveResult.ALREADY_RECEIVED);
        verify(repository, never()).saveAndFlush(any());
    }

    // 확인한 뒤 저장하기 전에 같은 판이 먼저 저장됐다(동시 재전송). unique 충돌을 이미 받은 것으로 본다.
    @Test
    void treatsUniqueConflictAsAlreadyReceived() {
        when(repository.existsByBattleId(summary.getBattleId())).thenReturn(false, true);
        when(repository.saveAndFlush(summary)).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThat(service.receive(summary)).isEqualTo(ReceiveResult.ALREADY_RECEIVED);
    }

    // 같은 판이 없는데 제약을 어겼다면 숨기지 않고 그대로 던진다.
    @Test
    void rethrowsOtherConstraintViolation() {
        when(repository.existsByBattleId(summary.getBattleId())).thenReturn(false);
        when(repository.saveAndFlush(summary)).thenThrow(new DataIntegrityViolationException("too long"));

        assertThatThrownBy(() -> service.receive(summary))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static BattleSummary summary() {
        Instant now = Instant.now();
        return new BattleSummary(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                1, "1.0", "", 1, now, now, "{}");
    }
}
