package org.example.feedablackhole.battlesummary;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;

// POST /api/v1/battle-summaries의 본문(클라이언트의 BattleSummaryDto) 중 서버가 칼럼으로 쓰는 값만 읽는다.
// 나머지 키(nodes, kills 등)는 읽지 않고 원문 JSON에 그대로 남긴다.
// 숫자를 int가 아니라 Integer로 받는다. int면 키가 빠졌을 때 0이 되어 빠졌다는 사실이 사라진다.
// 글자 수 상한은 V1 테이블의 칼럼 길이와 같다. 넘치면 DB 오류가 아니라 400으로 답하려는 것이다.
@JsonIgnoreProperties(ignoreUnknown = true)
public record BattleSummaryRequest(
        @NotNull @Positive Integer schemaVersion,
        @NotNull @Pattern(regexp = UUID_PATTERN, message = UUID_MESSAGE) String battleId,
        @NotNull @Pattern(regexp = UUID_PATTERN, message = UUID_MESSAGE) String installId,
        @NotNull @Positive Integer battleIndex,
        @NotBlank @Size(max = 32) String buildVersion,
        // 콘텐츠 버전은 공급 방식을 정하기 전까지 ""로 온다.
        @NotNull @Size(max = 64) String contentVersion,
        // JsonUtility가 빈 값으로 쓰는 ""는 null로 읽혀 @NotNull에 걸린다.
        @NotNull Instant startedAtUtc
) {

    // Unity의 Guid.ToString() 모양: 소문자, 하이픈 포함 36자.
    private static final String UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";
    private static final String UUID_MESSAGE = "소문자 UUID(하이픈 포함 36자)여야 한다.";

    // 검사를 통과한 요청만 바꾼다(통과하지 않았으면 null이 있을 수 있다). 원문과 받은 시각은 서버가 채운다.
    public BattleSummary toEntity(String rawJson, Instant receivedAt) {
        return new BattleSummary(battleId, installId, schemaVersion, buildVersion, contentVersion,
                battleIndex, startedAtUtc, receivedAt, rawJson);
    }
}
