package org.example.feedablackhole.progress;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.example.feedablackhole.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 진행 상태 불러오기·저장·초기화 API를 실제 MySQL까지 포함해 끝에서 끝까지 확인한다.
 * 테스트마다 트랜잭션이 롤백되어 서로 영향을 주지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class ProgressApiTests {

    private static final String PROGRESS = "/api/v1/me/progress";
    private static final String RESET = "/api/v1/me/progress/reset";

    @Autowired
    private MockMvc mockMvc;

    // ---------- 불러오기 ----------

    @Test
    void newAccountStartsFromTheInitialProgress() throws Exception {
        String token = newAccountToken();

        mockMvc.perform(get(PROGRESS).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.gold").value(0))
                .andExpect(jsonPath("$.growthStage").value(0))
                .andExpect(jsonPath("$.nodes").isEmpty())
                .andExpect(jsonPath("$.updatedAt").isString());
    }

    @Test
    void progressApisRequireAnAccessToken() throws Exception {
        mockMvc.perform(get(PROGRESS)).andExpect(status().isUnauthorized());
        mockMvc.perform(put(PROGRESS).contentType(MediaType.APPLICATION_JSON).content(body(0, 0, 0)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(RESET)).andExpect(status().isUnauthorized());
    }

    // ---------- 저장 ----------

    @Test
    void saveReplacesTheProgressAndBumpsTheRevision() throws Exception {
        String token = newAccountToken();

        save(token, 0, 1_250_000L, 2, "timer-01:1", "timer-02:2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.gold").value(1_250_000L))
                .andExpect(jsonPath("$.growthStage").value(2))
                .andExpect(jsonPath("$.nodes[0].nodeId").value("timer-01"))
                .andExpect(jsonPath("$.nodes[1].rank").value(2));

        // 다시 불러와도 같은 값이다.
        mockMvc.perform(get(PROGRESS).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.gold").value(1_250_000L))
                .andExpect(jsonPath("$.nodes.length()").value(2));
    }

    @Test
    void goldMayDecreaseBecausePurchasesSpendIt() throws Exception {
        String token = newAccountToken();
        save(token, 0, 1000, 0).andExpect(status().isOk());

        save(token, 1, 998, 0, "timer-01:1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gold").value(998));
    }

    @Test
    void rankCanGoUpAndNewNodesAreAppendedInRequestOrder() throws Exception {
        String token = newAccountToken();
        save(token, 0, 0, 0, "timer-01:1").andExpect(status().isOk());

        // 요청의 노드 순서가 달라도 이미 산 노드의 자리는 유지되고, 새 노드가 그 뒤에 요청 순서대로 붙는다.
        save(token, 1, 0, 0, "timer-03:1", "timer-01:2", "timer-02:1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[0].nodeId").value("timer-01"))
                .andExpect(jsonPath("$.nodes[0].rank").value(2))
                .andExpect(jsonPath("$.nodes[1].nodeId").value("timer-03"))
                .andExpect(jsonPath("$.nodes[2].nodeId").value("timer-02"));
    }

    @Test
    void saveWithAStaleRevisionIsRejectedAndChangesNothing() throws Exception {
        String token = newAccountToken();
        save(token, 0, 100, 1).andExpect(status().isOk());

        // revision이 1인데 0을 기준으로 저장하려는 낡은 요청
        save(token, 0, 999, 5, "timer-01:1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("STALE_PROGRESS"));

        mockMvc.perform(get(PROGRESS).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.gold").value(100))
                .andExpect(jsonPath("$.nodes").isEmpty());
    }

    @Test
    void growthStageCannotDecrease() throws Exception {
        String token = newAccountToken();
        save(token, 0, 0, 3).andExpect(status().isOk());

        save(token, 1, 0, 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PROGRESS_REGRESSION"));
    }

    @Test
    void boughtNodeCannotBeRemovedOrLowered() throws Exception {
        String token = newAccountToken();
        save(token, 0, 0, 0, "timer-01:2", "timer-02:1").andExpect(status().isOk());

        save(token, 1, 0, 0, "timer-01:2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PROGRESS_REGRESSION"));
        save(token, 1, 0, 0, "timer-01:1", "timer-02:1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PROGRESS_REGRESSION"));
    }

    @Test
    void saveRejectsMalformedRequests() throws Exception {
        String token = newAccountToken();

        save(token, 0, -1, 0).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        save(token, 0, 0, -1).andExpect(status().isBadRequest());
        save(token, -1, 0, 0).andExpect(status().isBadRequest());
        save(token, 0, 0, 0, "timer-01:0").andExpect(status().isBadRequest());
        save(token, 0, 0, 0, " :1").andExpect(status().isBadRequest());
        save(token, 0, 0, 0, "a".repeat(65) + ":1").andExpect(status().isBadRequest());
        save(token, 0, 0, 0, "timer-01:1", "timer-01:2")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        // 필드 누락
        mockMvc.perform(put(PROGRESS).header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"gold\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void saveRejectsTooManyNodes() throws Exception {
        String token = newAccountToken();
        String[] nodes = new String[1001];
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = "node-" + i + ":1";
        }

        save(token, 0, 0, 0, nodes).andExpect(status().isBadRequest());
    }

    // ---------- 계정 분리 ----------

    @Test
    void eachAccountOnlySeesAndChangesItsOwnProgress() throws Exception {
        String first = newAccountToken();
        String second = newAccountToken();
        save(first, 0, 777, 1, "timer-01:1").andExpect(status().isOk());

        mockMvc.perform(get(PROGRESS).header(HttpHeaders.AUTHORIZATION, bearer(second)))
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.gold").value(0))
                .andExpect(jsonPath("$.nodes").isEmpty());
        // 두 번째 계정의 저장이 첫 번째 계정의 revision과 무관하게 자기 기준으로 처리된다.
        save(second, 0, 5, 0).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
        mockMvc.perform(get(PROGRESS).header(HttpHeaders.AUTHORIZATION, bearer(first)))
                .andExpect(jsonPath("$.gold").value(777));
    }

    // ---------- 초기화(새 게임) ----------

    @Test
    void resetReturnsTheProgressToTheStartAndBumpsTheRevision() throws Exception {
        String token = newAccountToken();
        save(token, 0, 5000, 2, "timer-01:1", "timer-02:1").andExpect(status().isOk());

        mockMvc.perform(post(RESET).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.gold").value(0))
                .andExpect(jsonPath("$.growthStage").value(0))
                .andExpect(jsonPath("$.nodes").isEmpty());

        mockMvc.perform(get(PROGRESS).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.gold").value(0))
                .andExpect(jsonPath("$.nodes").isEmpty());
    }

    @Test
    void saveBasedOnTheRevisionBeforeResetIsRejected() throws Exception {
        String token = newAccountToken();
        save(token, 0, 5000, 2).andExpect(status().isOk());
        mockMvc.perform(post(RESET).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());

        // 초기화 전에 받아 둔 진행 상태(revision 1)를 기준으로 한 저장이 초기화를 되돌리지 못한다.
        save(token, 1, 5000, 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("STALE_PROGRESS"));
    }

    @Test
    void progressCanGrowAgainFromTheStartAfterReset() throws Exception {
        String token = newAccountToken();
        save(token, 0, 0, 3, "timer-01:1").andExpect(status().isOk());
        mockMvc.perform(post(RESET).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());

        // 초기화로 성장도와 노드가 없어졌으므로 낮은 값으로 새로 저장할 수 있다.
        save(token, 2, 10, 1, "timer-02:1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.growthStage").value(1))
                .andExpect(jsonPath("$.nodes.length()").value(1));
    }

    // ---------- 도우미 ----------

    private String newAccountToken() throws Exception {
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
        return JsonPath.read(tokens, "$.accessToken");
    }

    // nodes: "노드ID:Rank" 형식
    private ResultActions save(String token, long baseRevision, long gold, int growthStage, String... nodes)
            throws Exception {
        MockHttpServletRequestBuilder request = put(PROGRESS)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(baseRevision, gold, growthStage, nodes));
        return mockMvc.perform(request);
    }

    private static String body(long baseRevision, long gold, int growthStage, String... nodes) {
        String nodesJson = Arrays.stream(nodes)
                .map(node -> {
                    int colon = node.lastIndexOf(':');
                    return "{\"nodeId\":\"%s\",\"rank\":%s}".formatted(node.substring(0, colon), node.substring(colon + 1));
                })
                .collect(Collectors.joining(",", "[", "]"));
        return "{\"baseRevision\":%d,\"gold\":%d,\"growthStage\":%d,\"nodes\":%s}"
                .formatted(baseRevision, gold, growthStage, nodesJson);
    }

    private static String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }

}
