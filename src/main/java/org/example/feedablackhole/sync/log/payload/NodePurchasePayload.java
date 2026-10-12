package org.example.feedablackhole.sync.log.payload;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * NODE_PURCHASE의 payload(docs/server-sync-contracts.md 3.3). 엔진이 형식 검사를 마친 뒤 처리기에 넘긴다.
 *
 * @param rank 구매 후 Rank
 */
public record NodePurchasePayload(
        @NotNull @Size(min = 1, max = 64) String nodeId,
        @NotNull @Min(1) Integer rank,
        @NotNull @PositiveOrZero Long cost) {
}
