package org.example.feedablackhole.sync.log.payload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * RUN_START의 payload(docs/server-sync-contracts.md 3.3). 엔진이 형식 검사를 마친 뒤 처리기에 넘긴다.
 * startSupply의 enemyId 중복과 timeLimitSeconds의 유한성은 엔진이 따로 검사한다.
 */
public record RunStartPayload(
        @NotNull @Pattern(regexp = PayloadPatterns.UUID) String runId,
        @NotNull Integer seed,
        @NotNull @PositiveOrZero Integer startGrowthStage,
        @NotNull @PositiveOrZero Long startGold,
        @NotNull @PositiveOrZero Integer startLevel,
        @NotNull @Positive Float timeLimitSeconds,
        @NotNull @Size(max = 20) List<@NotNull @Valid SupplyItem> startSupply) {

    public RunStartPayload {
        startSupply = startSupply == null ? null : List.copyOf(startSupply);
    }

    public record SupplyItem(
            @NotNull @Size(min = 1, max = 32) String enemyId,
            @NotNull @Min(1) Integer count) {
    }

}
