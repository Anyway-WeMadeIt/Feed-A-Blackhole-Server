package org.example.feedablackhole.progress.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 산 노드 하나: 노드 ID와 산 Rank. 저장 요청과 진행 상태 응답이 같은 형식을 쓴다.
 */
public record NodeRankDto(
        @NotBlank @Size(max = 64) String nodeId,
        @NotNull @Min(1) Integer rank) {
}
