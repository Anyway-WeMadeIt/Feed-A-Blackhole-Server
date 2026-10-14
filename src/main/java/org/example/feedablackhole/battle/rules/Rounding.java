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

    /** float을 반올림해 int로 만든다(C#의 Whole). */
    static int whole(float value) {
        return Math.toIntExact(awayFromZero(value));
    }

}
