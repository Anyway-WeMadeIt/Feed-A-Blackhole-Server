package org.example.feedablackhole.node.content;

import java.math.BigDecimal;

/**
 * 업그레이드 수치 하나의 정의(UpgradeStats 시트의 한 행).
 * 수치의 값은 기본값 + 산 효과 값의 합을 [min, max]로 자른 것이다. min·max가 null이면 그쪽은 제한이 없다.
 * 소수는 시트에 적힌 십진수를 그대로 BigDecimal로 보관해, 합산에서 부동소수점 오차가 쌓이지 않게 한다.
 *
 * @param statId       시트의 StatId(예: breaker.critChance). 클라이언트 코드의 수치와 이름으로 짝지어지므로 바꾸면 안 된다.
 * @param defaultValue 효과가 없을 때의 값. 늘어난 양의 기준점이며 min·max 자르기에 쓴다.
 */
public record UpgradeStatDefinition(
        String statId,
        StatValueType valueType,
        StatUnit unit,
        BigDecimal defaultValue,
        BigDecimal min,
        BigDecimal max) {

    /** 값을 [min, max] 안으로 자른다. */
    public BigDecimal clamp(BigDecimal value) {
        BigDecimal clamped = value;
        if (min != null && clamped.compareTo(min) < 0) {
            clamped = min;
        }
        if (max != null && clamped.compareTo(max) > 0) {
            clamped = max;
        }
        return clamped;
    }

}
