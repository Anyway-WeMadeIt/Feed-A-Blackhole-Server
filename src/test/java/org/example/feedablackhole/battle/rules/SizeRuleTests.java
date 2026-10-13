package org.example.feedablackhole.battle.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * SizeRule이 크기로 Gold·EXP 배율을 클라이언트와 같게 계산하는지 확인하는 테스트.
 */
class SizeRuleTests {

    // 배율은 1 + (크기 - 1) × 1.0: 크기 1은 1배, 2는 2배, 3은 3배
    @Test
    void multiplierGrowsLinearlyWithSize() {
        assertThat(SizeRule.statMultiplier(1)).isEqualTo(1f);
        assertThat(SizeRule.statMultiplier(2)).isEqualTo(2f);
        assertThat(SizeRule.statMultiplier(3)).isEqualTo(3f);
        assertThat(SizeRule.statMultiplier(20)).isEqualTo(20f);
    }

    // 크기는 1 ~ 20이라서, 그 밖이면 예외
    @Test
    void sizeOutsideTheRangeIsRejected() {
        assertThatThrownBy(() -> SizeRule.statMultiplier(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SizeRule.statMultiplier(21)).isInstanceOf(IllegalArgumentException.class);
    }

}
