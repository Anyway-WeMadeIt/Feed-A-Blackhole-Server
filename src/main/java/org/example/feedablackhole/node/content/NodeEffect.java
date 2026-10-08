package org.example.feedablackhole.node.content;

import java.math.BigDecimal;

/**
 * 한 Rank가 수치 하나에 주는 효과. 값은 시트 단위 그대로다(단위는 수치 정의가 가진다).
 */
public record NodeEffect(String statId, BigDecimal value) {
}
