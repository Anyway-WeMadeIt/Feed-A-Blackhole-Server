package org.example.feedablackhole.progress.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 산 노드 하나: 노드 ID와 산 Rank. 진행 상태 응답(GET /me/progress, 동기화 응답)이 쓴다.
 */
public record NodeRankDto(
        @NotBlank @Size(max = 64) String nodeId,
        @NotNull @Min(1) Integer rank) {
}
