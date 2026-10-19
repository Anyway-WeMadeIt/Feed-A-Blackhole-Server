package org.example.feedablackhole.sync.handler;

import org.example.feedablackhole.battle.content.ContentBundle;
import org.example.feedablackhole.battle.content.ContentBundleLoader;
import org.example.feedablackhole.battle.rules.ContentBattleRules;
import org.example.feedablackhole.sync.log.LogType;
import org.example.feedablackhole.sync.log.RejectCode;
import org.example.feedablackhole.sync.log.payload.RunStartPayload;
import org.example.feedablackhole.sync.log.payload.RunStartPayload.SupplyItem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RUN_START 처리기가 판을 열고, 서버 계산과 다르면 의심 사유를 남기는지 확인하는 테스트(계획서 6.2, 결정 D5, D8).
 * 서버 계산은 샘플 콘텐츠 묶음(src/test/resources/content-sample)의 실제 전투 규칙으로 하고,
 * 진행도는 가짜 SyncContext로 만든다. 샘플의 시작 공급은 소행성 20마리, 기본 제한 시간은 12초다.
 */
class RunStartHandlerTests {

    private static final String RUN_ID = "6f1c2b0a-3d4e-4f5a-8b6c-7d8e9f0a1b2c";
    private static final String OTHER_RUN_ID = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";

    // 클래스패스 기준 샘플 묶음 위치(src/test/resources 아래)
    private static final String SAMPLE = "/content-sample/";

    private static RunStartHandler handler;

    // 샘플 묶음의 규칙으로 처리기를 만든다(스프링 없이 직접 생성)
    @BeforeAll
    static void load() {
        ContentBundle bundle = ContentBundleLoader.load(RunStartHandlerTests::bytes);
        handler = new RunStartHandler(new ContentBattleRules(bundle.nodeTree(), bundle.battle()));
    }

    // 엔진이 RUN_START 로그를 이 처리기로 보내려면 타입과 payload 타입이 계약과 같아야 한다
    @Test
    void handlesTheRunStartLogType() {
        assertThat(handler.type()).isEqualTo(LogType.RUN_START);
        assertThat(handler.payloadType()).isEqualTo(RunStartPayload.class);
    }

    // 서버 계산과 모두 같으면 의심 사유 없이 판이 열린다(계약 3.8 샘플과 같은 시작: Gold 0, 제한 시간 12초, 소행성 20마리)
    @Test
    void matchingRunStartOpensTheRunWithoutSuspicion() {
        FakeSyncContext context = new FakeSyncContext();

        HandleResult result = handler.handle(payload(RUN_ID, 0, 0L, 0, 12f, asteroids(20)), context);

        assertThat(result).isEqualTo(HandleResult.applied());
        assertThat(context.openRun().orElseThrow().runId()).isEqualTo(RUN_ID);
        assertThat(context.suspicions()).isEmpty();
        assertThat(context.runIdUsed(RUN_ID)).isTrue();
    }

    // 성장도가 서버와 다르면 START_GROWTH_STAGE_MISMATCH만 남기고, 거부하지 않는다
    @Test
    void growthStageMismatchIsSuspected() {
        FakeSyncContext context = new FakeSyncContext();

        HandleResult result = handler.handle(payload(RUN_ID, 1, 0L, 0, 12f, asteroids(20)), context);

        assertThat(result).isEqualTo(HandleResult.applied());
        assertThat(context.suspicions()).containsExactly(SuspicionReason.START_GROWTH_STAGE_MISMATCH);
    }

    // 시작 Gold가 서버와 다르면 START_GOLD_MISMATCH
    @Test
    void goldMismatchIsSuspected() {
        FakeSyncContext context = new FakeSyncContext();

        handler.handle(payload(RUN_ID, 0, 5L, 0, 12f, asteroids(20)), context);

        assertThat(context.suspicions()).containsExactly(SuspicionReason.START_GOLD_MISMATCH);
    }

    // 시작 Level이 서버와 다르면 START_LEVEL_MISMATCH(성장도 0의 시작 Level은 0)
    @Test
    void levelMismatchIsSuspected() {
        FakeSyncContext context = new FakeSyncContext();

        handler.handle(payload(RUN_ID, 0, 0L, 3, 12f, asteroids(20)), context);

        assertThat(context.suspicions()).containsExactly(SuspicionReason.START_LEVEL_MISMATCH);
    }

    // 제한 시간이 서버와 다르면 TIME_LIMIT_MISMATCH
    @Test
    void timeLimitMismatchIsSuspected() {
        FakeSyncContext context = new FakeSyncContext();

        handler.handle(payload(RUN_ID, 0, 0L, 0, 13f, asteroids(20)), context);

        assertThat(context.suspicions()).containsExactly(SuspicionReason.TIME_LIMIT_MISMATCH);
    }

    // 제한 시간은 0.001초 이내의 차이는 같다고 본다. 그보다 크면 의심한다
    @Test
    void timeLimitToleranceIsOneMillisecond() {
        FakeSyncContext within = new FakeSyncContext();
        FakeSyncContext beyond = new FakeSyncContext();

        handler.handle(payload(RUN_ID, 0, 0L, 0, 12.0005f, asteroids(20)), within);
        handler.handle(payload(RUN_ID, 0, 0L, 0, 12.002f, asteroids(20)), beyond);

        assertThat(within.suspicions()).isEmpty();
        assertThat(beyond.suspicions()).containsExactly(SuspicionReason.TIME_LIMIT_MISMATCH);
    }

    // 시작 공급의 수가 서버와 다르면 START_SUPPLY_MISMATCH
    @Test
    void supplyCountMismatchIsSuspected() {
        FakeSyncContext context = new FakeSyncContext();

        handler.handle(payload(RUN_ID, 0, 0L, 0, 12f, asteroids(19)), context);

        assertThat(context.suspicions()).containsExactly(SuspicionReason.START_SUPPLY_MISMATCH);
    }

    // 시작 공급은 종류와 수뿐 아니라 순서까지 같아야 한다(asteroid.count-01 + asteroid.toPlanet-02: 소행성 45, 행성 5)
    @Test
    void supplyOrderMatters() {
        FakeSyncContext same = withNodes("asteroid.count-01", "asteroid.toPlanet-02");
        FakeSyncContext swapped = withNodes("asteroid.count-01", "asteroid.toPlanet-02");

        handler.handle(payload(RUN_ID, 0, 0L, 0, 12f,
                List.of(new SupplyItem("asteroid", 45), new SupplyItem("planet", 5))), same);
        handler.handle(payload(RUN_ID, 0, 0L, 0, 12f,
                List.of(new SupplyItem("planet", 5), new SupplyItem("asteroid", 45))), swapped);

        assertThat(same.suspicions()).isEmpty();
        assertThat(swapped.suspicions()).containsExactly(SuspicionReason.START_SUPPLY_MISMATCH);
    }

    // 서버는 서버가 가진 산 노드로 계산한다: timer-01(+2초)과 asteroid.count-01(+30마리)을 산 서버에서는 14초, 50마리가 정답이다
    @Test
    void serverComputesFromItsOwnNodes() {
        FakeSyncContext honest = withNodes("timer-01", "asteroid.count-01");
        FakeSyncContext ignoringNodes = withNodes("timer-01", "asteroid.count-01");

        handler.handle(payload(RUN_ID, 0, 0L, 0, 14f, asteroids(50)), honest);
        handler.handle(payload(RUN_ID, 0, 0L, 0, 12f, asteroids(20)), ignoringNodes);

        assertThat(honest.suspicions()).isEmpty();
        assertThat(ignoringNodes.suspicions())
                .containsExactly(SuspicionReason.TIME_LIMIT_MISMATCH, SuspicionReason.START_SUPPLY_MISMATCH);
    }

    // 여러 값이 다르면 모두 남긴다. 순서는 사유 목록의 순서다
    @Test
    void everyMismatchIsRecorded() {
        FakeSyncContext context = new FakeSyncContext();

        HandleResult result = handler.handle(payload(RUN_ID, 2, 9L, 7, 99f, asteroids(1)), context);

        assertThat(result).isEqualTo(HandleResult.applied());
        assertThat(context.suspicions()).containsExactly(
                SuspicionReason.START_GROWTH_STAGE_MISMATCH,
                SuspicionReason.START_GOLD_MISMATCH,
                SuspicionReason.START_LEVEL_MISMATCH,
                SuspicionReason.TIME_LIMIT_MISMATCH,
                SuspicionReason.START_SUPPLY_MISMATCH);
    }

    // 열린 판의 시작 Gold·성장도는 클라이언트가 보낸 값이 아니라 서버 진행도의 값이다
    @Test
    void openRunKeepsTheServerValues() {
        FakeSyncContext context = new FakeSyncContext().withGold(7);

        handler.handle(payload(RUN_ID, 0, 999L, 0, 12f, asteroids(20)), context);

        assertThat(context.openRun().orElseThrow().startGold()).isEqualTo(7);
        assertThat(context.openRun().orElseThrow().startGrowthStage()).isZero();
    }

    // 열린 판이 있으면 포기로 닫고 새 판을 연다(결정 D5)
    @Test
    void anOpenRunIsAbandonedWhenANewOneStarts() {
        FakeSyncContext context = new FakeSyncContext()
                .withOpenRun(new OpenRun(OTHER_RUN_ID, 3, 0, 0, 1));

        HandleResult result = handler.handle(payload(RUN_ID, 0, 0L, 0, 12f, asteroids(20)), context);

        assertThat(result).isEqualTo(HandleResult.applied());
        assertThat(context.abandonedRuns()).isEqualTo(1);
        assertThat(context.openRun().orElseThrow().runId()).isEqualTo(RUN_ID);
    }

    // 이미 쓴 runId는 DUPLICATE_RUN_ID로 거부하고, 진행도를 바꾸지 않는다
    @Test
    void duplicateRunIdIsRejected() {
        FakeSyncContext context = new FakeSyncContext().withUsedRunId(RUN_ID);

        HandleResult result = handler.handle(payload(RUN_ID, 0, 0L, 0, 12f, asteroids(20)), context);

        assertThat(rejectCode(result)).isEqualTo(RejectCode.DUPLICATE_RUN_ID);
        assertThat(context.changes()).isZero();
    }

    // 중복 확인이 열린 판 포기보다 먼저다: 거부하면서 열린 판을 닫지 않는다
    @Test
    void duplicateIsCheckedBeforeTheOpenRunIsAbandoned() {
        FakeSyncContext context = new FakeSyncContext()
                .withOpenRun(new OpenRun(OTHER_RUN_ID, 3, 0, 0, 1));

        HandleResult result = handler.handle(payload(OTHER_RUN_ID, 0, 0L, 0, 12f, asteroids(20)), context);

        assertThat(rejectCode(result)).isEqualTo(RejectCode.DUPLICATE_RUN_ID);
        assertThat(context.abandonedRuns()).isZero();
        assertThat(context.openRun().orElseThrow().runId()).isEqualTo(OTHER_RUN_ID);
        assertThat(context.changes()).isZero();
    }

    // 산 노드를 가진 진행도를 만든다(Rank 1씩)
    private static FakeSyncContext withNodes(String... nodeIds) {
        FakeSyncContext context = new FakeSyncContext();
        for (String nodeId : nodeIds) {
            context.withNode(nodeId, 1);
        }
        return context;
    }

    private static List<SupplyItem> asteroids(int count) {
        return List.of(new SupplyItem("asteroid", count));
    }

    private static RunStartPayload payload(String runId, int stage, long gold, int level, float timeLimit,
            List<SupplyItem> supply) {
        return new RunStartPayload(runId, 1, stage, gold, level, timeLimit, supply);
    }

    // 거부 결과에서 거부 코드를 꺼낸다. 거부가 아니면 null
    private static RejectCode rejectCode(HandleResult result) {
        return result instanceof HandleResult.Rejected rejected ? rejected.code() : null;
    }

    // 샘플 묶음 폴더에서 파일 바이트를 읽는다. 없으면 null
    private static byte[] bytes(String path) {
        try (InputStream in = RunStartHandlerTests.class.getResourceAsStream(SAMPLE + path)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
