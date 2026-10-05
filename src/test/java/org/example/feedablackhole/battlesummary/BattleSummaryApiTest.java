package org.example.feedablackhole.battlesummary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.example.feedablackhole.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

// 실제 MySQL(Testcontainers)까지 거쳐 API를 확인한다. 클라이언트가 받는 그대로 상태 코드와 본문을 본다.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BattleSummaryApiTest {

    private static final String PATH = "/api/v1/battle-summaries";
    private static final String SAMPLE_BATTLE_ID = "3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BattleSummaryRepository repository;

    // 처음 받은 판은 201, 같은 판을 다시 보내면 200.
    @Test
    void createsThenAcknowledgesResend() throws Exception {
        String battleId = UUID.randomUUID().toString();
        String body = sampleWith(battleId);

        send(body).andExpect(status().isCreated());
        send(body).andExpect(status().isOk());

        assertThat(repository.existsByBattleId(battleId)).isTrue();
    }

    // JSON 객체가 아니면 INVALID_JSON: 빈 본문, 깨진 JSON, 배열, null.
    @ParameterizedTest
    @ValueSource(strings = {"", "{\"battleId\":", "[]", "null"})
    void rejectsBodyThatIsNotJsonObject(String body) throws Exception {
        send(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_JSON"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    // 검증에 걸린 필드를 errors에 담아 INVALID_FIELD로 답한다.
    @Test
    void rejectsInvalidField() throws Exception {
        String body = sampleWith(UUID.randomUUID().toString())
                .replace("\"battleIndex\": 27", "\"battleIndex\": 0");

        send(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("battleIndex"));
    }

    // 형식이 틀려 읽지 못한 값도 INVALID_FIELD로 답한다.
    @Test
    void rejectsValueOfWrongFormat() throws Exception {
        String body = sampleWith(UUID.randomUUID().toString())
                .replace("2026-10-05T03:00:00.0000000Z", "yesterday");

        send(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD"))
                .andExpect(jsonPath("$.errors[0].field").value("startedAtUtc"));
    }

    // JSON이 아닌 Content-Type은 415. 클라이언트는 설정 문제로 보고 멈춘다.
    @Test
    void rejectsNonJsonContentType() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.TEXT_PLAIN).content(sampleWith(UUID.randomUUID().toString())))
                .andExpect(status().isUnsupportedMediaType());
    }

    private ResultActions send(String body) throws Exception {
        return mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // 계약 예시의 battleId만 바꾼다. 테스트끼리 같은 판으로 겹치지 않게 하려는 것이다.
    private static String sampleWith(String battleId) throws IOException {
        return new ClassPathResource("contract/battle-summary.sample.json")
                .getContentAsString(StandardCharsets.UTF_8)
                .replace(SAMPLE_BATTLE_ID, battleId);
    }
}
