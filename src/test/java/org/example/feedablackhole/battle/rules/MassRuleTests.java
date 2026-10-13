package org.example.feedablackhole.battle.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * MassRule이 질량(%)으로 색 비율을 클라이언트와 같게 계산하는지 확인하는 테스트.
 * 기대값은 클라이언트 MassRule 주석의 예시(6색 기준)다.
 */
class MassRuleTests {

    // 질량이 100% 이하면 첫 색(빨강)만 나온다
    @Test
    void baseMassGivesOnlyTheFirstTier() {
        assertThat(MassRule.tierRatios(100f, 6)).containsExactly(1f, 0f, 0f, 0f, 0f, 0f);
        assertThat(MassRule.tierRatios(0f, 6)).containsExactly(1f, 0f, 0f, 0f, 0f, 0f);
    }

    // 150%는 빨:주 = 3:1, 200%는 1:1
    @Test
    void ratiosBlendNeighbouringTiers() {
        assertThat(MassRule.tierRatios(150f, 6)).containsExactly(0.75f, 0.25f, 0f, 0f, 0f, 0f);
        assertThat(MassRule.tierRatios(200f, 6)).containsExactly(0.5f, 0.5f, 0f, 0f, 0f, 0f);
    }

    // 250%는 빨:주:노 = 1:2:1 (동시에 나오는 색은 최대 3개)
    @Test
    void atMostThreeTiersAppearAtOnce() {
        assertThat(MassRule.tierRatios(250f, 6)).containsExactly(0.25f, 0.5f, 0.25f, 0f, 0f, 0f);
    }

    // 700% 이상은 마지막 색(보라)만 나온다. 그 위로는 차이가 없다
    @Test
    void hugeMassGivesOnlyTheLastTier() {
        assertThat(MassRule.tierRatios(700f, 6)).containsExactly(0f, 0f, 0f, 0f, 0f, 1f);
        assertThat(MassRule.tierRatios(1000f, 6)).containsExactly(0f, 0f, 0f, 0f, 0f, 1f);
    }

    // 색이 하나뿐인 종류(혜성)는 질량과 상관없이 그 색이 전부다
    @Test
    void aSingleTierAlwaysGetsEverything() {
        assertThat(MassRule.tierRatios(100f, 1)).containsExactly(1f);
        assertThat(MassRule.tierRatios(500f, 1)).containsExactly(1f);
    }

    // 어떤 질량이든 비율의 합은 1이다
    @Test
    void ratiosAlwaysSumToOne() {
        for (float mass = 0; mass <= 1000; mass += 12.5f) {
            float sum = 0;
            for (float ratio : MassRule.tierRatios(mass, 6)) {
                sum += ratio;
            }
            assertThat(sum).as("질량 %s", mass).isCloseTo(1f, org.assertj.core.data.Offset.offset(1e-6f));
        }
    }

    // 음수·무한·NaN 질량과 색 0개는 예외
    @Test
    void invalidInputIsRejected() {
        assertThatThrownBy(() -> MassRule.tierRatios(-1f, 6)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MassRule.tierRatios(Float.NaN, 6)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MassRule.tierRatios(Float.POSITIVE_INFINITY, 6)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MassRule.tierRatios(100f, 0)).isInstanceOf(IllegalArgumentException.class);
    }

}
