package org.example.feedablackhole.sync.handler;

import static org.assertj.core.api.Assertions.assertThat;

import org.example.feedablackhole.sync.log.LogType;
import org.example.feedablackhole.sync.log.payload.ResetPayload;
import org.junit.jupiter.api.Test;

/**
 * RESET 처리기가 새 게임으로 진행도를 초기화하는지 확인하는 테스트(계획서 6.2: 검증 없음).
 * 진행도는 DB 없이 메모리에 들고 있는 가짜 SyncContext로 만든다.
 */
class ResetHandlerTests {

    private final ResetHandler handler = new ResetHandler();

    // 엔진이 RESET 로그를 이 처리기로 보내려면 타입과 payload 타입이 계약과 같아야 한다
    @Test
    void handlesTheResetLogType() {
        assertThat(handler.type()).isEqualTo(LogType.RESET);
        assertThat(handler.payloadType()).isEqualTo(ResetPayload.class);
    }

    // 새 게임: Gold 0, 성장도 0, 산 노드 없음
    @Test
    void resetClearsGoldGrowthStageAndNodes() {
        FakeSyncContext context = new FakeSyncContext()
                .withGold(100)
                .withGrowthStage(2)
                .withNode("timer-01", 1)
                .withNode("asteroid.size-01", 1);

        HandleResult result = handler.handle(new ResetPayload(), context);

        assertThat(result).isEqualTo(HandleResult.applied());
        assertThat(context.gold()).isZero();
        assertThat(context.growthStage()).isZero();
        assertThat(context.nodeRanks()).isEmpty();
    }

    // 열린 판이 있으면 포기로 닫는다(보상 없음)
    @Test
    void resetAbandonsTheOpenRun() {
        FakeSyncContext context = new FakeSyncContext()
                .withGold(50)
                .withOpenRun(new OpenRun("6f1c2b0a-3d4e-4f5a-8b6c-7d8e9f0a1b2c", 7, 1, 50, 3));

        HandleResult result = handler.handle(new ResetPayload(), context);

        assertThat(result).isEqualTo(HandleResult.applied());
        assertThat(context.openRun()).isEmpty();
        assertThat(context.abandonedRuns()).isEqualTo(1);
        assertThat(context.gold()).isZero();
    }

    // 이미 비어 있는 진행도에서도 반영된다(검증이 없어서 거부하는 경우가 없다)
    @Test
    void resetOnAnEmptyProgressIsStillApplied() {
        FakeSyncContext context = new FakeSyncContext();

        HandleResult result = handler.handle(new ResetPayload(), context);

        assertThat(result).isEqualTo(HandleResult.applied());
        assertThat(context.gold()).isZero();
        assertThat(context.nodeRanks()).isEmpty();
    }

}
