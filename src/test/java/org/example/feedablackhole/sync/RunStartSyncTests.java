package org.example.feedablackhole.sync;

import com.jayway.jsonpath.JsonPath;
import org.example.feedablackhole.TestcontainersConfiguration;
import org.example.feedablackhole.sync.entity.BattleRunStatus;
import org.example.feedablackhole.sync.entity.SyncLogRecord;
import org.example.feedablackhole.sync.entity.SyncLogResult;
import org.example.feedablackhole.sync.repository.BattleRunRepository;
import org.example.feedablackhole.sync.repository.SyncLogRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 RUN_START 처리기를 엔진과 MySQL, 진짜 JpaSyncContext까지 포함해 확인한다(W1-5).
 * 단위 테스트(RunStartHandlerTests)는 가짜 진행도로 규칙을 보고, 여기서는 판이 battle_run에 저장되고
 * 의심 표시가 남고 이어서 계속 처리되는 흐름을 본다. 스프링 테스트는 샘플 콘텐츠 묶음 위에서 돈다(build.gradle의 test 설정).
 *
 * <p>테스트마다 새 계정을 만들어 서로 영향을 주지 않는다. 샘플의 서버 계산: 성장도 0, Gold 0, 시작 Level 0,
 * 제한 시간 12초, 시작 공급 소행성 20마리.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RunStartSyncTests {

    private static final String SYNC = "/api/v1/me/sync";
    private static final String RUN_A = "6f1c2b0a-3d4e-4f5a-8b6c-7d8e9f0a1b2c";
    private static final String RUN_B = "0a1b2c3d-4e5f-4a6b-8c7d-8e9f0a1b2c3d";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SyncLogRecordRepository logRepository;

    @Autowired
    private BattleRunRepository runRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    // 서버 계산과 같은 판 시작은 의심 없이 열린다(battle_run은 OPEN, 의심 사유 없음, 계정의 의심 표시 0)
    @Test
    void matchingRunStartOpensTheRun() throws Exception {
        Account account = newAccount();

        sync(account, request(runStart(1, RUN_A, 0, 0, 12f, 20)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedThroughSeq").value(1))
                .andExpect(jsonPath("$.rejected").doesNotExist())
                .andExpect(jsonPath("$.progress.gold").value(0));

        assertThat(runRepository.findByAccountIdAndRunId(account.id, RUN_A)).hasValueSatisfying(run -> {
            assertThat(run.getStatus()).isEqualTo(BattleRunStatus.OPEN);
            assertThat(run.getSuspiciousReasons()).isNull();
            assertThat(run.getStartedSeq()).isEqualTo(1);
        });
        assertThat(suspiciousCount(account)).isZero();
    }

    // 서버 계산과 다르면 멈추지 않고 판을 열되, 판에 의심 사유가 남고 계정의 의심 표시가 1 오른다.
    // 판의 시작 Gold는 클라이언트가 보낸 값(5)이 아니라 서버 값(0)이다
    @Test
    void mismatchedRunStartIsAppliedWithSuspicion() throws Exception {
        Account account = newAccount();

        sync(account, request(runStart(1, RUN_A, 5, 0, 12f, 20)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedThroughSeq").value(1))
                .andExpect(jsonPath("$.rejected").doesNotExist());

        assertThat(runRepository.findByAccountIdAndRunId(account.id, RUN_A)).hasValueSatisfying(run -> {
            assertThat(run.getStatus()).isEqualTo(BattleRunStatus.OPEN);
            assertThat(run.getSuspiciousReasons()).isEqualTo("START_GOLD_MISMATCH");
            assertThat(run.getStartGold()).isZero();
        });
        assertThat(suspiciousCount(account)).isEqualTo(1);
        assertThat(results(account)).containsExactly(SyncLogResult.APPLIED);
    }

    // 여러 값이 다르면 사유가 쉼표로 이어져 저장되고, 의심 표시는 판 하나당 1이다
    @Test
    void severalMismatchesAreStoredTogetherAndCountedOnce() throws Exception {
        Account account = newAccount();

        sync(account, request(runStart(1, RUN_A, 5, 3, 99f, 20)))
                .andExpect(jsonPath("$.appliedThroughSeq").value(1));

        assertThat(runRepository.findByAccountIdAndRunId(account.id, RUN_A)).hasValueSatisfying(run ->
                assertThat(run.getSuspiciousReasons())
                        .isEqualTo("START_GOLD_MISMATCH,START_LEVEL_MISMATCH,TIME_LIMIT_MISMATCH"));
        assertThat(suspiciousCount(account)).isEqualTo(1);
    }

    // 의심 표시가 남아도 뒤의 로그는 계속 처리된다. 뒤의 RESET이 열린 판을 포기로 닫는다
    @Test
    void processingContinuesAfterASuspiciousRunStart() throws Exception {
        Account account = newAccount();

        sync(account, request(runStart(1, RUN_A, 5, 0, 12f, 20), reset(2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedThroughSeq").value(2))
                .andExpect(jsonPath("$.rejected").doesNotExist());

        assertThat(results(account)).containsExactly(SyncLogResult.APPLIED, SyncLogResult.APPLIED);
        assertThat(runRepository.findByAccountIdAndRunId(account.id, RUN_A))
                .hasValueSatisfying(run -> assertThat(run.getStatus()).isEqualTo(BattleRunStatus.ABANDONED));
    }

    // 열린 판이 있는데 다음 판을 시작하면, 앞의 판은 포기로 닫히고 새 판이 열린다
    @Test
    void aNewRunStartAbandonsTheOpenRun() throws Exception {
        Account account = newAccount();

        sync(account, request(runStart(1, RUN_A, 0, 0, 12f, 20), runStart(2, RUN_B, 0, 0, 12f, 20)))
                .andExpect(jsonPath("$.appliedThroughSeq").value(2))
                .andExpect(jsonPath("$.rejected").doesNotExist());

        assertThat(runRepository.findByAccountIdAndRunId(account.id, RUN_A))
                .hasValueSatisfying(run -> assertThat(run.getStatus()).isEqualTo(BattleRunStatus.ABANDONED));
        assertThat(runRepository.findByAccountIdAndRunId(account.id, RUN_B))
                .hasValueSatisfying(run -> assertThat(run.getStatus()).isEqualTo(BattleRunStatus.OPEN));
    }

    // 이미 쓴 runId는 DUPLICATE_RUN_ID로 거부하고 멈춘다. 거부하면서 열린 판을 닫지 않는다
    @Test
    void duplicateRunIdIsRejectedAndKeepsTheOpenRun() throws Exception {
        Account account = newAccount();
        sync(account, request(runStart(1, RUN_A, 0, 0, 12f, 20)));

        sync(account, request(runStart(2, RUN_A, 0, 0, 12f, 20)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedThroughSeq").value(1))
                .andExpect(jsonPath("$.rejected.seq").value(2))
                .andExpect(jsonPath("$.rejected.type").value("RUN_START"))
                .andExpect(jsonPath("$.rejected.code").value("DUPLICATE_RUN_ID"));

        assertThat(results(account)).containsExactly(SyncLogResult.APPLIED, SyncLogResult.REJECTED);
        assertThat(runRepository.findByAccountIdAndRunId(account.id, RUN_A))
                .hasValueSatisfying(run -> assertThat(run.getStatus()).isEqualTo(BattleRunStatus.OPEN));
    }

    // 서버는 서버가 가진 산 노드로 계산한다: timer-01을 산 뒤의 판 시작은 Gold 18, 제한 시간 14초가 정답이다
    // (계약 3.8 샘플 seq 4와 같은 값). Gold는 결산 로그를 거치지 않고 DB에 직접 넣는다
    @Test
    void serverComputesFromItsOwnProgress() throws Exception {
        Account account = newAccount();
        giveGold(account, 20);

        sync(account, request(purchase(1, "timer-01", 1, 2), runStart(2, RUN_A, 18, 0, 14f, 20)))
                .andExpect(jsonPath("$.appliedThroughSeq").value(2))
                .andExpect(jsonPath("$.rejected").doesNotExist());

        assertThat(runRepository.findByAccountIdAndRunId(account.id, RUN_A)).hasValueSatisfying(run -> {
            assertThat(run.getSuspiciousReasons()).isNull();
            assertThat(run.getStartGold()).isEqualTo(18);
        });
        assertThat(suspiciousCount(account)).isZero();
    }

    // ---------- 도우미 ----------

    private record Account(long id, String token) {
    }

    // 게스트 계정을 만들어 로그인한 뒤 계정 번호와 토큰을 돌려준다(SyncApiTests와 같은 순서)
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

    // 결산 로그를 거치지 않고, 진행도의 Gold를 DB에 직접 정한다.
    // 풀이 auto-commit을 꺼 두므로 트랜잭션 안에서 써야 커밋된다.
    private void giveGold(Account account, long gold) {
        transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.update("UPDATE player_progress SET gold = ? WHERE account_id = ?", gold, account.id));
    }

    // 계정에 남은 의심 표시의 수(의심이 있는 판 하나당 1)
    private int suspiciousCount(Account account) {
        return jdbcTemplate.queryForObject(
                "SELECT suspicious_count FROM player_progress WHERE account_id = ?", Integer.class, account.id);
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

    // 샘플 RUN_START 로그(log-run-start.json)를 바탕으로 seq와 payload 값만 바꾼 로그. 시작 공급은 소행성만 한 종류다
    private static ObjectNode runStart(long seq, String runId, long startGold, int startLevel, float timeLimit,
            int asteroidCount) throws IOException {
        ObjectNode log = (ObjectNode) JSON.readTree(sample("log-run-start.json"));
        log.put("seq", seq);
        ObjectNode payload = (ObjectNode) log.get("payload");
        payload.put("runId", runId);
        payload.put("startGold", startGold);
        payload.put("startLevel", startLevel);
        payload.put("timeLimitSeconds", timeLimit);
        ((ObjectNode) payload.get("startSupply").get(0)).put("count", asteroidCount);
        return log;
    }

    // 샘플 NODE_PURCHASE 로그(log-node-purchase.json)를 바탕으로 seq와 payload만 바꾼 로그
    private static ObjectNode purchase(long seq, String nodeId, int rank, long cost) throws IOException {
        ObjectNode log = (ObjectNode) JSON.readTree(sample("log-node-purchase.json"));
        log.put("seq", seq);
        ObjectNode payload = (ObjectNode) log.get("payload");
        payload.put("nodeId", nodeId);
        payload.put("rank", rank);
        payload.put("cost", cost);
        return log;
    }

    // 샘플 RESET 로그(log-reset.json)의 seq만 바꾼 로그
    private static ObjectNode reset(long seq) throws IOException {
        ObjectNode log = (ObjectNode) JSON.readTree(sample("log-reset.json"));
        log.put("seq", seq);
        return log;
    }

    // 로그들을 {"logs": [...]} 요청 본문으로 묶는다
    private static String request(ObjectNode... logs) {
        ArrayNode array = JSON.createArrayNode();
        for (ObjectNode log : logs) {
            array.add(log);
        }
        return JSON.createObjectNode().set("logs", array).toString();
    }

    private static String sample(String name) throws IOException {
        try (InputStream in = RunStartSyncTests.class.getResourceAsStream("/sync-samples/" + name)) {
            assertThat(in).as(name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

}
