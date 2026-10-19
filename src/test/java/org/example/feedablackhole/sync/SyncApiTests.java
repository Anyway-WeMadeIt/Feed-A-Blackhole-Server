package org.example.feedablackhole.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.example.feedablackhole.TestcontainersConfiguration;
import org.example.feedablackhole.sync.entity.BattleRunStatus;
import org.example.feedablackhole.sync.entity.SyncLogRecord;
import org.example.feedablackhole.sync.entity.SyncLogResult;
import org.example.feedablackhole.sync.handler.HandleResult;
import org.example.feedablackhole.sync.handler.LogHandler;
import org.example.feedablackhole.sync.handler.SuspicionReason;
import org.example.feedablackhole.sync.handler.SyncContext;
import org.example.feedablackhole.sync.log.LogType;
import org.example.feedablackhole.sync.log.RejectCode;
import org.example.feedablackhole.sync.log.payload.NodePurchasePayload;
import org.example.feedablackhole.sync.log.payload.ResetPayload;
import org.example.feedablackhole.sync.log.payload.RunStartPayload;
import org.example.feedablackhole.sync.log.payload.SettlementPayload;
import org.example.feedablackhole.sync.repository.BattleRunRepository;
import org.example.feedablackhole.sync.repository.SyncLogRecordRepository;
import org.example.feedablackhole.sync.service.LogHandlerRegistry;
import org.example.feedablackhole.sync.service.ServerContentVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 동기화 엔진(W1-2)을 실제 MySQL까지 포함해 끝에서 끝까지 확인한다(docs/server-sync-contracts.md 3장, 4장).
 * 실제 처리기는 아직 없으므로 테스트용 처리기를 쓴다. 엔진의 몫(seq, 형식, 버전, 멈춤, 기록, 롤백)만 본다.
 *
 * <p>요청 전체의 롤백을 확인하려고 테스트 트랜잭션을 쓰지 않는다. 테스트마다 새 계정을 만들어 서로 영향을 주지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, SyncApiTests.FakeHandlers.class})
class SyncApiTests {

    private static final String SYNC = "/api/v1/me/sync";
    private static final String SAMPLE_VERSION = "4d6c398ad1f5e49c";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SyncLogRecordRepository logRepository;

    @Autowired
    private BattleRunRepository runRepository;

    // ---------- 순서대로 반영 ----------

    @Test
    void sampleRequestIsAppliedInOrder() throws Exception {
        Account account = newAccount();

        sync(account, sample("sync-request.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedThroughSeq").value(6))
                .andExpect(jsonPath("$.rejected").doesNotExist())
                .andExpect(jsonPath("$.contentVersion").value(SAMPLE_VERSION))
                .andExpect(jsonPath("$.progress.lastAppliedSeq").value(6))
                .andExpect(jsonPath("$.progress.gold").value(0))
                .andExpect(jsonPath("$.progress.growthStage").value(0))
                .andExpect(jsonPath("$.progress.nodes").isEmpty())
                .andExpect(jsonPath("$.progress.updatedAt").isString());

        assertThat(results(account)).containsOnly(SyncLogResult.APPLIED).hasSize(6);
        assertThat(runRepository.findByAccountIdAndRunId(account.id, "6f1c2b0a-3d4e-4f5a-8b6c-7d8e9f0a1b2c"))
                .hasValueSatisfying(run -> assertThat(run.getStatus()).isEqualTo(BattleRunStatus.SETTLED));
    }

    @Test
    void progressAfterPurchaseKeepsTheNodeInPurchaseOrder() throws Exception {
        Account account = newAccount();

        sync(account, logs(sampleLogs(1, 3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedThroughSeq").value(3))
                .andExpect(jsonPath("$.progress.gold").value(18))
                .andExpect(jsonPath("$.progress.nodes[0].nodeId").value("timer-01"))
                .andExpect(jsonPath("$.progress.nodes[0].rank").value(1));
    }

    @Test
    void resendingAppliedLogsSkipsThem() throws Exception {
        Account account = newAccount();
        String firstThree = logs(sampleLogs(1, 3));

        sync(account, firstThree).andExpect(jsonPath("$.appliedThroughSeq").value(3));
        sync(account, firstThree)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedThroughSeq").value(3))
                .andExpect(jsonPath("$.rejected").doesNotExist())
                .andExpect(jsonPath("$.progress.gold").value(18));

        assertThat(results(account)).containsExactly(
                SyncLogResult.APPLIED, SyncLogResult.APPLIED, SyncLogResult.APPLIED,
                SyncLogResult.SKIPPED, SyncLogResult.SKIPPED, SyncLogResult.SKIPPED);
    }

    @Test
    void resendingOverlappingLogsAppliesOnlyTheNewOnes() throws Exception {
        Account account = newAccount();
        sync(account, logs(sampleLogs(1, 2)));

        sync(account, logs(sampleLogs(1, 3)))
                .andExpect(jsonPath("$.appliedThroughSeq").value(3))
                .andExpect(jsonPath("$.progress.gold").value(18));
    }

    @Test
    void emptyRequestReturnsTheProgress() throws Exception {
        Account account = newAccount();

        sync(account, sample("sync-request-empty.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedThroughSeq").value(0))
                .andExpect(jsonPath("$.rejected").doesNotExist())
                .andExpect(jsonPath("$.progress.lastAppliedSeq").value(0));
    }

    // ---------- 거부와 멈춤 ----------

    @Test
    void rejectedLogStopsProcessingAndKeepsEarlierLogs() throws Exception {
        Account account = newAccount();

        sync(account, sample("sync-request-tampered.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedThroughSeq").value(1))
                .andExpect(jsonPath("$.rejected.seq").value(2))
                .andExpect(jsonPath("$.rejected.type").value("SETTLEMENT"))
                .andExpect(jsonPath("$.rejected.code").value("SETTLEMENT_GOLD_MISMATCH"))
                .andExpect(jsonPath("$.progress.lastAppliedSeq").value(1))
                .andExpect(jsonPath("$.progress.gold").value(0));

        assertThat(results(account)).containsExactly(
                SyncLogResult.APPLIED, SyncLogResult.REJECTED, SyncLogResult.NOT_PROCESSED);
        assertThat(logRepository.findAllByAccountIdOrderByIdAsc(account.id).get(1).getRejectCode())
                .isEqualTo(RejectCode.SETTLEMENT_GOLD_MISMATCH);
    }

    @Test
    void missingEarlierLogIsASeqGap() throws Exception {
        Account account = newAccount();

        sync(account, logs(sampleLogs(2, 3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedThroughSeq").value(0))
                .andExpect(jsonPath("$.rejected.seq").value(2))
                .andExpect(jsonPath("$.rejected.code").value("SEQ_GAP"));
    }

    @Test
    void malformedPayloadIsRejectedAsMalformedLog() throws Exception {
        Account account = newAccount();
        List<ObjectNode> logs = sampleLogs(1, 3);
        ((ObjectNode) logs.get(2).get("payload")).remove("cost");

        sync(account, logs(logs))
                .andExpect(jsonPath("$.appliedThroughSeq").value(2))
                .andExpect(jsonPath("$.rejected.seq").value(3))
                .andExpect(jsonPath("$.rejected.type").value("NODE_PURCHASE"))
                .andExpect(jsonPath("$.rejected.code").value("MALFORMED_LOG"));
    }

    @Test
    void unknownTypeIsRejectedAsMalformedLog() throws Exception {
        Account account = newAccount();
        List<ObjectNode> logs = sampleLogs(1, 1);
        logs.get(0).put("type", "BOGUS");

        sync(account, logs(logs))
                .andExpect(jsonPath("$.rejected.type").value("BOGUS"))
                .andExpect(jsonPath("$.rejected.code").value("MALFORMED_LOG"));
    }

    @Test
    void invalidRunIdIsRejectedAsMalformedLog() throws Exception {
        Account account = newAccount();
        List<ObjectNode> logs = sampleLogs(1, 1);
        ((ObjectNode) logs.get(0).get("payload")).put("runId", "6F1C2B0A-3D4E-4F5A-8B6C-7D8E9F0A1B2C");

        sync(account, logs(logs)).andExpect(jsonPath("$.rejected.code").value("MALFORMED_LOG"));
    }

    @Test
    void duplicateKillBucketIsRejectedAsMalformedLog() throws Exception {
        Account account = newAccount();
        List<ObjectNode> logs = sampleLogs(1, 2);
        ArrayNode kills = (ArrayNode) logs.get(1).get("payload").get("kills");
        kills.add(kills.get(0).deepCopy());

        sync(account, logs(logs))
                .andExpect(jsonPath("$.rejected.seq").value(2))
                .andExpect(jsonPath("$.rejected.code").value("MALFORMED_LOG"));
    }

    @Test
    void differentContentVersionIsRejected() throws Exception {
        Account account = newAccount();
        List<ObjectNode> logs = sampleLogs(1, 1);
        logs.get(0).put("contentVersion", "0000000000000000");

        sync(account, logs(logs))
                .andExpect(jsonPath("$.appliedThroughSeq").value(0))
                .andExpect(jsonPath("$.rejected.code").value("CONTENT_VERSION_MISMATCH"));
    }

    @Test
    void handlerThatChangesProgressWhileRejectingRollsBackTheWholeRequest() throws Exception {
        Account account = newAccount();
        List<ObjectNode> logs = sampleLogs(1, 3);
        ((ObjectNode) logs.get(2).get("payload")).put("nodeId", FakeHandlers.BUGGY_NODE);

        sync(account, logs(logs)).andExpect(status().isInternalServerError());

        sync(account, sample("sync-request-empty.json"))
                .andExpect(jsonPath("$.appliedThroughSeq").value(0))
                .andExpect(jsonPath("$.progress.gold").value(0))
                .andExpect(jsonPath("$.progress.nodes").isEmpty());
        assertThat(results(account)).isEmpty();
    }

    // ---------- 판과 의심 표시 ----------

    @Test
    void runStartMismatchIsRecordedAsSuspicionWithoutRejecting() throws Exception {
        Account account = newAccount();
        List<ObjectNode> logs = sampleLogs(1, 1);
        ((ObjectNode) logs.get(0).get("payload")).put("startGold", 999);

        sync(account, logs(logs))
                .andExpect(jsonPath("$.appliedThroughSeq").value(1))
                .andExpect(jsonPath("$.rejected").doesNotExist());

        assertThat(runRepository.findByAccountIdAndRunId(account.id, "6f1c2b0a-3d4e-4f5a-8b6c-7d8e9f0a1b2c"))
                .hasValueSatisfying(run -> {
                    assertThat(run.getStatus()).isEqualTo(BattleRunStatus.OPEN);
                    assertThat(run.getSuspiciousReasons()).isEqualTo("START_GOLD_MISMATCH");
                    assertThat(run.getStartGold()).isZero();
                });
    }

    @Test
    void nextRunStartAbandonsTheOpenRun() throws Exception {
        Account account = newAccount();
        List<ObjectNode> logs = sampleLogs(1, 1);
        ObjectNode second = sampleLogs(4, 4).get(0);
        second.put("seq", 2);
        ((ObjectNode) second.get("payload")).put("startGold", 0);
        logs.add(second);

        sync(account, logs(logs)).andExpect(jsonPath("$.appliedThroughSeq").value(2));

        assertThat(runRepository.findByAccountIdAndRunId(account.id, "6f1c2b0a-3d4e-4f5a-8b6c-7d8e9f0a1b2c"))
                .hasValueSatisfying(run -> {
                    assertThat(run.getStatus()).isEqualTo(BattleRunStatus.ABANDONED);
                    assertThat(run.getEndedSeq()).isEqualTo(2);
                });
    }

    // ---------- 요청 전체의 오류 ----------

    @Test
    void requestWithoutLogsIsInvalid() throws Exception {
        Account account = newAccount();

        sync(account, "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        sync(account, "not json").andExpect(status().isBadRequest());
        sync(account, "{\"logs\":[1]}").andExpect(status().isBadRequest());
    }

    @Test
    void nonConsecutiveSeqInARequestIsInvalid() throws Exception {
        Account account = newAccount();
        List<ObjectNode> logs = sampleLogs(1, 3);
        logs.remove(1);

        sync(account, logs(logs)).andExpect(status().isBadRequest());
        assertThat(results(account)).isEmpty();
    }

    @Test
    void tooManyLogsIsTooLarge() throws Exception {
        Account account = newAccount();
        ArrayNode many = JSON.createArrayNode();
        for (int seq = 1; seq <= 501; seq++) {
            ObjectNode reset = sampleLogs(6, 6).get(0);
            reset.put("seq", seq);
            many.add(reset);
        }

        sync(account, JSON.createObjectNode().set("logs", many).toString())
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.error.code").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void syncRequiresAnAccessToken() throws Exception {
        mockMvc.perform(post(SYNC).contentType(MediaType.APPLICATION_JSON).content("{\"logs\":[]}"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- 도우미 ----------

    private record Account(long id, String token) {
    }

    private Account newAccount() throws Exception {
        String credentials = mockMvc.perform(post("/api/v1/auth/guest/register"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String tokens = mockMvc.perform(post("/api/v1/auth/guest/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"guestId\":\"%s\",\"guestSecret\":\"%s\"}".formatted(
                                JsonPath.<String>read(credentials, "$.guestId"),
                                JsonPath.<String>read(credentials, "$.guestSecret"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(tokens, "$.accessToken");
        String me = mockMvc.perform(get("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return new Account(((Number) JsonPath.read(me, "$.accountId")).longValue(), token);
    }

    private ResultActions sync(Account account, String body) throws Exception {
        return mockMvc.perform(post(SYNC)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + account.token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private List<SyncLogResult> results(Account account) {
        return logRepository.findAllByAccountIdOrderByIdAsc(account.id).stream()
                .map(SyncLogRecord::getResult)
                .toList();
    }

    // sync-request.json의 seq from~to 로그(고쳐 쓸 수 있는 사본).
    private static List<ObjectNode> sampleLogs(int from, int to) throws IOException {
        JsonNode logs = JSON.readTree(sample("sync-request.json")).get("logs");
        List<ObjectNode> picked = new ArrayList<>();
        for (int seq = from; seq <= to; seq++) {
            picked.add((ObjectNode) logs.get(seq - 1).deepCopy());
        }
        return picked;
    }

    private static String logs(List<ObjectNode> logs) {
        ArrayNode array = JSON.createArrayNode();
        logs.forEach(array::add);
        return JSON.createObjectNode().set("logs", array).toString();
    }

    private static String sample(String name) throws IOException {
        try (InputStream in = SyncApiTests.class.getResourceAsStream("/sync-samples/" + name)) {
            assertThat(in).as(name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 테스트용 처리기와 콘텐츠 버전. 실제 처리기(W1-3, W1-5, W1-6)와 겹치지 않도록 빈으로 등록하지 않고 등록소에만 넣는다.
     * 규칙은 샘플(계약 3.8)이 통과하고 조작 샘플이 거부되는 정도로만 흉내 낸다.
     */
    @TestConfiguration
    static class FakeHandlers {

        static final String BUGGY_NODE = "buggy-node";

        @Bean
        @Primary
        ServerContentVersion sampleContentVersion() {
            return () -> SAMPLE_VERSION;
        }

        @Bean
        @Primary
        LogHandlerRegistry fakeHandlerRegistry() {
            return new LogHandlerRegistry(List.of(
                    handler(LogType.NODE_PURCHASE, NodePurchasePayload.class, (payload, context) -> {
                        if (BUGGY_NODE.equals(payload.nodeId())) {
                            context.setNodeRank(payload.nodeId(), 1);
                            return HandleResult.rejected(RejectCode.UNKNOWN_NODE, "버그: 바꾼 뒤 거부");
                        }
                        if (context.gold() < payload.cost()) {
                            return HandleResult.rejected(RejectCode.NOT_ENOUGH_GOLD, "Gold 부족");
                        }
                        context.spendGold(payload.cost());
                        context.setNodeRank(payload.nodeId(), payload.rank());
                        return HandleResult.applied();
                    }),
                    handler(LogType.RESET, ResetPayload.class, (payload, context) -> {
                        context.resetProgress();
                        return HandleResult.applied();
                    }),
                    handler(LogType.RUN_START, RunStartPayload.class, (payload, context) -> {
                        if (context.runIdUsed(payload.runId())) {
                            return HandleResult.rejected(RejectCode.DUPLICATE_RUN_ID, "이미 쓴 runId");
                        }
                        List<SuspicionReason> suspicions = payload.startGold() == context.gold()
                                ? List.of() : List.of(SuspicionReason.START_GOLD_MISMATCH);
                        context.abandonOpenRun();
                        context.openRun(payload, suspicions);
                        return HandleResult.applied();
                    }),
                    handler(LogType.SETTLEMENT, SettlementPayload.class, (payload, context) -> {
                        boolean open = context.openRun().map(run -> run.runId().equals(payload.runId())).orElse(false);
                        if (!open) {
                            return HandleResult.rejected(RejectCode.RUN_NOT_OPEN, "열린 판이 아님");
                        }
                        if (payload.earnedGold() > 1000) {
                            return HandleResult.rejected(RejectCode.SETTLEMENT_GOLD_MISMATCH, "Gold가 장부와 맞지 않음");
                        }
                        context.earnGold(payload.settledGold());
                        context.setGrowthStage(payload.nextGrowthStage());
                        context.settleOpenRun(payload.settledGold(), payload.nextGrowthStage());
                        return HandleResult.applied();
                    })));
        }

        private interface Body<P> {
            HandleResult handle(P payload, SyncContext context);
        }

        private static <P> LogHandler<P> handler(LogType type, Class<P> payloadType, Body<P> body) {
            return new LogHandler<>() {
                @Override
                public LogType type() {
                    return type;
                }

                @Override
                public Class<P> payloadType() {
                    return payloadType;
                }

                @Override
                public HandleResult handle(P payload, SyncContext context) {
                    return body.handle(payload, context);
                }
            };
        }

    }

}
