package org.example.feedablackhole.battle.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 반올림 도우미. 클라이언트의 Math.Round(value, MidpointRounding.AwayFromZero)와 같다(.5에서 0에서 먼 쪽).
 */
final class Rounding {

    private Rounding() {
    }

    /** double을 가까운 정수로 반올림한다. .5는 0에서 먼 쪽(양수는 올림, 음수는 내림). */
    static long awayFromZero(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("반올림할 수 없는 값이다: " + value);
        }
        // new BigDecimal(double)은 double의 정확한 값을 그대로 담는다. HALF_UP은 .5에서 0에서 먼 쪽이다
        return new BigDecimal(value).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /**
     * 값에 배율을 곱해 반올림한다. 클라이언트 EnemyDefinition.Multiply(value, (double) factor)와 같다:
     * long × double로 곱하고(float 배율을 double로 넓힌다), .5는 0에서 먼 쪽. 크기 배율과 황금 배율을 곱할 때 쓴다.
     */
    static long scaled(long value, float factor) {
        return awayFromZero(value * (double) factor);
    }

    /**
     * 황금 치명 보너스. 클라이언트 DeathRewards.CritBonusOf와 같다: long × float은 float 곱셈이다(C28).
     * double로 바꿔 곱하면 float 정밀도(2^24)를 넘는 큰 Gold에서 값이 달라진다.
     */
    static long critBonus(long gold, float critRewardScale) {
        return awayFromZero(gold * critRewardScale);
    }

    /** float을 반올림해 int로 만든다(C#의 Whole). */
    static int whole(float value) {
        return Math.toIntExact(awayFromZero(value));
    }

}
