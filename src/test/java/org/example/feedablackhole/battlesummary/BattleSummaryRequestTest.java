package org.example.feedablackhole.battlesummary;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

// 요청을 실제 JSON으로 읽고 검사해 본다. Spring과 DB 없이 돈다.
// 계약 예시(contract/battle-summary.sample.json)는 Unity 레포의 것을 복사해 둔 것이다. 계약을 고치면 함께 덮어쓴다.
class BattleSummaryRequestTest {

    private static final JsonMapper JSON_MAPPER = new JsonMapper();
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    // 계약 예시는 검사를 통과하고, 필요한 값이 엔티티로 그대로 옮겨진다. 모르는 키(nodes, kills 등)는 무시한다.
    @Test
    void readsContractSample() throws IOException {
        String json = new ClassPathResource("contract/battle-summary.sample.json")
                .getContentAsString(StandardCharsets.UTF_8);
        BattleSummaryRequest request = JSON_MAPPER.readValue(json, BattleSummaryRequest.class);

        assertThat(violatedFields(request)).isEmpty();

        Instant receivedAt = Instant.now();
        BattleSummary summary = request.toEntity(json, receivedAt);
        assertThat(summary.getBattleId()).isEqualTo("3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b");
        assertThat(summary.getBattleIndex()).isEqualTo(27);
        assertThat(summary.getStartedAt()).isEqualTo(Instant.parse("2026-10-05T03:00:00Z"));
        assertThat(summary.getReceivedAt()).isEqualTo(receivedAt);
        assertThat(summary.getRawJson()).isEqualTo(json);
    }

    // 게임이 값을 채우지 않고 보낸 경우(JsonUtility가 새 DTO를 쓴 모양). 빈 문자열과 0이 걸린다.
    // contentVersion은 아직 ""가 정상이고, schemaVersion은 DTO 기본값(1)이 들어 있다.
    @Test
    void rejectsUnfilledDto() {
        String json = """
                {"schemaVersion":1,"battleId":"","installId":"","battleIndex":0,"buildVersion":"",
                "contentVersion":"","startedAtUtc":"","endedAtUtc":"","playedSeconds":0.0,
                "startGrowthStage":0,"nodes":[],"kills":[],"totalKills":0,"earnedGold":0,
                "settledGold":0,"reachedLevel":0,"reachedMilestone":false}
                """;
        BattleSummaryRequest request = JSON_MAPPER.readValue(json, BattleSummaryRequest.class);

        assertThat(violatedFields(request))
                .containsExactlyInAnyOrder("battleId", "installId", "battleIndex", "buildVersion", "startedAtUtc");
    }

    // 키가 빠지면 null로 읽혀 모두 걸린다(int였다면 0이 되어 빠진 줄 모른다).
    @Test
    void rejectsMissingKeys() {
        BattleSummaryRequest request = JSON_MAPPER.readValue("{}", BattleSummaryRequest.class);

        assertThat(violatedFields(request)).containsExactlyInAnyOrder(
                "schemaVersion", "battleId", "installId", "battleIndex",
                "buildVersion", "contentVersion", "startedAtUtc");
    }

    // 대문자 UUID, 칼럼보다 긴 버전 문자열은 받지 않는다.
    @Test
    void rejectsMalformedValues() {
        String uuid = UUID.randomUUID().toString();
        BattleSummaryRequest request = new BattleSummaryRequest(1, uuid.toUpperCase(Locale.ROOT), uuid, 1,
                "1".repeat(33), "c".repeat(65), Instant.now());

        assertThat(violatedFields(request))
                .containsExactlyInAnyOrder("battleId", "buildVersion", "contentVersion");
    }

    private static Set<String> violatedFields(BattleSummaryRequest request) {
        return VALIDATOR.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }
}
