package org.example.feedablackhole.node.content;

import java.math.BigDecimal;
import java.util.List;

/**
 * 시트 4개의 행(검사 전 값). 형식(머리칸, 글자, 숫자)만 읽은 것이고, 콘텐츠 규칙은 NodeContentLoader가 본다.
 * row는 시트의 행 번호다(머리칸이 1행).
 */
public record NodeContentData(
        List<StatRow> stats,
        List<NodeRow> nodes,
        List<CostRow> costs,
        List<EffectRow> effects) {

    public record StatRow(
            int row,
            String statId,
            String valueType,
            String unit,
            BigDecimal defaultValue,
            String aggregation,
            BigDecimal min,
            BigDecimal max) {
    }

    public record NodeRow(int row, String nodeId, int rankCount) {
    }

    public record CostRow(int row, String nodeId, int rank, long cost) {
    }

    public record EffectRow(int row, String nodeId, int rank, String statId, BigDecimal value, String unit) {
    }

}
