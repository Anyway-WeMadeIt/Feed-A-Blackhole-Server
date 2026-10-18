package org.example.feedablackhole.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.example.feedablackhole.TestcontainersConfiguration;
import org.example.feedablackhole.progress.repository.PlayerProgressRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 진행 상태 불러오기 API(GET /me/progress)를 실제 MySQL까지 포함해 확인한다.
 * 진행 상태는 동기화 API(POST /me/sync)로만 바뀌고, 옛 저장(PUT)·초기화(POST reset) API는 없어졌다(W1-7).
 * 테스트마다 트랜잭션이 롤백되어 서로 영향을 주지 않는다. 스프링 테스트는 샘플 콘텐츠 묶음 위에서 돈다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class ProgressApiTests {

    private static final String PROGRESS = "/api/v1/me/progress";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PlayerProgressRepository progressRepository;

    // 새 계정은 처음 상태다: Gold 0, 성장도 0, 산 노드 없음, 반영한 로그 없음(lastAppliedSeq 0)
    @Test
    void newAccountStartsFromTheInitialProgress() throws Exception {
        String token = newAccountToken();

        mockMvc.perform(get(PROGRESS).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastAppliedSeq").value(0))
                .andExpect(jsonPath("$.gold").value(0))
                .andExpect(jsonPath("$.growthStage").value(0))
                .andExpect(jsonPath("$.nodes").isEmpty())
                .andExpect(jsonPath("$.updatedAt").isString())
                .andExpect(jsonPath("$.revision").doesNotExist());
    }

    // 토큰 없이는 불러올 수 없다
    @Test
    void progressRequiresAnAccessToken() throws Exception {
        mockMvc.perform(get(PROGRESS)).andExpect(status().isUnauthorized());
    }

    // 옛 저장(PUT)·초기화(POST reset) API는 없다. 진행도는 동기화 API로만 바뀐다
    @Test
    void theOldSaveAndResetApisAreGone() throws Exception {
        String token = newAccountToken();

        mockMvc.perform(put(PROGRESS).header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
        mockMvc.perform(post(PROGRESS + "/reset").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // 각 계정은 자기 진행도만 본다
    @Test
    void eachAccountOnlySeesItsOwnProgress() throws Exception {
        String first = newAccountToken();
        String second = newAccountToken();
        setGold(first, 777);

        mockMvc.perform(get(PROGRESS).header(HttpHeaders.AUTHORIZATION, bearer(second)))
                .andExpect(jsonPath("$.gold").value(0))
                .andExpect(jsonPath("$.nodes").isEmpty());
        mockMvc.perform(get(PROGRESS).header(HttpHeaders.AUTHORIZATION, bearer(first)))
                .andExpect(jsonPath("$.gold").value(777));
    }

    // 동기화로 반영한 로그의 seq가 lastAppliedSeq로 나오고, 동기화 응답의 progress와 같은 값이다
    @Test
    void progressMatchesWhatTheSyncApplied() throws Exception {
        String token = newAccountToken();

        String synced = mockMvc.perform(post("/api/v1/me/sync")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resetRequest(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedThroughSeq").value(1))
                .andReturn().getResponse().getContentAsString();
        String loaded = mockMvc.perform(get(PROGRESS).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastAppliedSeq").value(1))
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<Object>read(loaded, "$")).isEqualTo(JsonPath.<Object>read(synced, "$.progress"));
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

    // 토큰의 계정 진행도의 Gold를 바꾼다. 같은 트랜잭션의 엔티티를 바꿔야 GET이 그 값을 본다
    private void setGold(String token, long gold) throws Exception {
        String me = mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andReturn().getResponse().getContentAsString();
        long accountId = ((Number) JsonPath.read(me, "$.accountId")).longValue();
        progressRepository.findById(accountId).orElseThrow().changeGold(gold);
    }

    // 샘플 RESET 로그(log-reset.json)의 seq만 바꾼 동기화 요청 본문
    private static String resetRequest(long seq) throws IOException {
        ObjectNode log = (ObjectNode) JSON.readTree(sample("log-reset.json"));
        log.put("seq", seq);
        ArrayNode logs = JSON.createArrayNode();
        logs.add(log);
        return JSON.createObjectNode().set("logs", logs).toString();
    }

    private static String sample(String name) throws IOException {
        try (InputStream in = ProgressApiTests.class.getResourceAsStream("/sync-samples/" + name)) {
            assertThat(in).as(name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }

}
