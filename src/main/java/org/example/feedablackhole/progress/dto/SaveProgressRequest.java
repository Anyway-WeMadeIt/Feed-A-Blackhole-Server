package org.example.feedablackhole.progress.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 진행 상태 저장 요청. 진행 상태 전체를 새 값으로 교체한다(같은 요청을 다시 보내도 결과가 같다).
 *
 * @param baseRevision 이 저장이 기준으로 삼은 서버의 revision(마지막으로 받은 진행 상태의 값). 서버의 현재 값과 다르면 거부한다.
 * @param gold         저장할 Gold. 구매로 줄 수 있어서 줄어도 된다.
 * @param growthStage  저장할 성장도. 지금보다 작으면 거부한다.
 * @param nodes        산 노드 전체. 지금까지 산 노드가 빠지거나 Rank가 작아지면 거부한다. 새로 산 노드는 목록 순서대로 이어 붙는다.
 */
public record SaveProgressRequest(
        @NotNull @PositiveOrZero Long baseRevision,
        @NotNull @PositiveOrZero Long gold,
        @NotNull @PositiveOrZero Integer growthStage,
        @NotNull @Size(max = SaveProgressRequest.MAX_NODES) @Valid List<NodeRankDto> nodes) {

    /** 한 번에 받는 노드 수의 상한. 콘텐츠의 노드 수보다 넉넉하게 잡은 값이다. */
    public static final int MAX_NODES = 1000;

}
