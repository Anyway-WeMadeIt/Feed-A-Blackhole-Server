package org.example.feedablackhole.sync.log.payload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * SETTLEMENT의 payload(docs/server-sync-contracts.md 3.3). 엔진이 형식 검사를 마친 뒤 처리기에 넘긴다.
 * kills의 (enemyId, tier, size, golden) 중복과 playedSeconds의 유한성은 엔진이 따로 검사한다.
 */
public record SettlementPayload(
        @NotNull @Pattern(regexp = PayloadPatterns.UUID) String runId,
        @NotNull @PositiveOrZero Float playedSeconds,
        @NotNull @Size(max = 2000) List<@NotNull @Valid KillItem> kills,
        @NotNull @PositiveOrZero Integer goldenCritCount,
        @NotNull @PositiveOrZero Long goldenCritBonusGold,
        @NotNull @PositiveOrZero Long earnedGold,
        @NotNull @PositiveOrZero Long exp,
        @NotNull @PositiveOrZero Integer reachedLevel,
        @NotNull Boolean reachedMilestone,
        @NotNull @PositiveOrZero Integer timeBonusCount,
        @NotNull @PositiveOrZero Long settledGold,
        @NotNull @PositiveOrZero Integer nextGrowthStage) {

    public SettlementPayload {
        kills = kills == null ? null : List.copyOf(kills);
    }

    /** 처치 장부의 칸 하나: (종류, 색 등급, 크기, 황금 여부)마다의 처치 수. */
    public record KillItem(
            @NotNull @Size(min = 1, max = 32) String enemyId,
            @NotNull @PositiveOrZero Integer tier,
            @NotNull @Min(1) Integer size,
            @NotNull Boolean golden,
            @NotNull @Min(1) Integer count) {
    }

}
