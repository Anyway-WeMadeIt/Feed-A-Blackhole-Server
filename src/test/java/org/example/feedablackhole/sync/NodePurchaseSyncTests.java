package org.example.feedablackhole.sync;

import com.jayway.jsonpath.JsonPath;
import org.example.feedablackhole.TestcontainersConfiguration;
import org.example.feedablackhole.sync.entity.SyncLogRecord;
import org.example.feedablackhole.sync.entity.SyncLogResult;
import org.example.feedablackhole.sync.log.RejectCode;
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
 * 실제 처리기(NODE_PURCHASE, RESET)를 엔진과 MySQL, 진짜 JpaSyncContext까지 포함해 확인한다(W1-3).
 * 단위 테스트(NodePurchaseHandlerTests)는 가짜 진행도로 규칙을 보고, 여기서는 엔진이 처리기를 부르고 진행도를 저장하는 흐름을 본다.
 * 스프링 테스트는 샘플 콘텐츠 묶음 위에서 돈다(build.gradle의 test 설정).
 *
 * <p>Gold는 결산 로그를 거치지 않고 테스트가 DB에 직접 넣는다. 테스트마다 새 계정을 만들어 서로 영향을 주지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class NodePurchaseSyncTests {

    private static final String SYNC = "/api/v1/me/sync";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SyncLogRecordRepository logRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // timer-01(비용 2)을 사면 Gold가 쓰이고 산 노드가 응답에 나온다. 계약 3.8 샘플의 seq 3과 같은 구매다
    @Test
    void purchaseIsAppliedAndSavedInTheProgress() throws Exception {
        Account account = newAccount();
        giveGold(account, 20);

        sync(account, request(purchase(1, "timer-01", 1, 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedThroughSeq").value(1))
                .andExpect(jsonPath("$.rejected").doesNotExist())
                .andExpect(jsonPath("$.progress.gold").value(18))
                .andExpect(jsonPath("$.progress.nodes[0].nodeId").value("timer-01"))
                .andExpect(jsonPath("$.progress.nodes[0].rank").value(1));

        assertThat(results(account)).containsExactly(SyncLogResult.APPLIED);
    }

    // Gold가 모자라면 거부하고, 진행도는 그대로이며 그 로그는 거부로 기록된다
    @Test
    void rejectedPurchaseKeepsTheProgress() throws Exception {
        Account account = newAccount();
        giveGold(account, 1);

        sync(account, request(purchase(1, "timer-01", 1, 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedThroughSeq").value(0))
                .andExpect(jsonPath("$.rejected.seq").value(1))
                .andExpect(jsonPath("$.rejected.type").value("NODE_PURCHASE"))
                .andExpect(jsonPath("$.rejected.code").value("NOT_ENOUGH_GOLD"))
                .andExpect(jsonPath("$.progress.gold").value(1))
                .andExpect(jsonPath("$.progress.nodes").isEmpty());

        assertThat(results(account)).containsExactly(SyncLogResult.REJECTED);
        assertThat(logRepository.findAllByAccountIdOrderByIdAsc(account.id).get(0).getRejectCode())
                .isEqualTo(RejectCode.NOT_ENOUGH_GOLD);
    }

    // 거부된 로그에서 멈춘다: 앞의 구매는 반영되고, 거부된 로그와 그 뒤 로그는 반영되지 않는다
    @Test
    void stopsAtTheFirstRejectedPurchase() throws Exception {
        Account account = newAccount();
        giveGold(account, 100);

        sync(account, request(
                purchase(1, "timer-01", 1, 2),
                purchase(2, "breaker.damage-01", 1, 99),   // 비용이 서버(6)와 다르다
                purchase(3, "breaker.radius-01", 1, 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedThroughSeq").value(1))
                .andExpect(jsonPath("$.rejected.seq").value(2))
                .andExpect(jsonPath("$.rejected.code").value("COST_MISMATCH"))
                .andExpect(jsonPath("$.progress.gold").value(98))
                .andExpect(jsonPath("$.progress.nodes[0].nodeId").value("timer-01"));

        assertThat(results(account)).containsExactly(
                SyncLogResult.APPLIED, SyncLogResult.REJECTED, SyncLogResult.NOT_PROCESSED);
    }

    // 산 순서가 보존된다(timer-01 다음 breaker.damage-01)
    @Test
    void nodesKeepThePurchaseOrder() throws Exception {
        Account account = newAccount();
        giveGold(account, 100);

        sync(account, request(
                purchase(1, "timer-01", 1, 2),
                purchase(2, "breaker.damage-01", 1, 6)))
                .andExpect(jsonPath("$.appliedThroughSeq").value(2))
                .andExpect(jsonPath("$.progress.gold").value(92))
                .andExpect(jsonPath("$.progress.nodes[0].nodeId").value("timer-01"))
                .andExpect(jsonPath("$.progress.nodes[1].nodeId").value("breaker.damage-01"));
    }

    // 새 게임(RESET): Gold, 성장도, 산 노드가 모두 처음 상태가 된다. 그 뒤 로그의 seq는 이어진다
    @Test
    void resetClearsTheProgressAndKeepsTheSequence() throws Exception {
        Account account = newAccount();
        giveGold(account, 20);

        sync(account, request(purchase(1, "timer-01", 1, 2), reset(2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedThroughSeq").value(2))
                .andExpect(jsonPath("$.rejected").doesNotExist())
                .andExpect(jsonPath("$.progress.lastAppliedSeq").value(2))
                .andExpect(jsonPath("$.progress.gold").value(0))
                .andExpect(jsonPath("$.progress.growthStage").value(0))
                .andExpect(jsonPath("$.progress.nodes").isEmpty());

        assertThat(results(account)).containsExactly(SyncLogResult.APPLIED, SyncLogResult.APPLIED);
    }

    // RESET 뒤에는 산 노드가 없어서, 같은 노드를 다시 살 수 있다(Gold가 있다면)
    @Test
    void aNodeCanBeBoughtAgainAfterReset() throws Exception {
        Account account = newAccount();
        giveGold(account, 20);
        sync(account, request(purchase(1, "timer-01", 1, 2), reset(2)));
        giveGold(account, 20);

        sync(account, request(purchase(3, "timer-01", 1, 2)))
                .andExpect(jsonPath("$.appliedThroughSeq").value(3))
                .andExpect(jsonPath("$.rejected").doesNotExist())
                .andExpect(jsonPath("$.progress.gold").value(18))
                .andExpect(jsonPath("$.progress.nodes[0].nodeId").value("timer-01"));
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

    // 결산 로그를 거치지 않고, 진행도의 Gold를 DB에 직접 정한다
    private void giveGold(Account account, long gold) {
        jdbcTemplate.update("UPDATE player_progress SET gold = ? WHERE account_id = ?", gold, account.id);
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
        try (InputStream in = NodePurchaseSyncTests.class.getResourceAsStream("/sync-samples/" + name)) {
            assertThat(in).as(name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

}
