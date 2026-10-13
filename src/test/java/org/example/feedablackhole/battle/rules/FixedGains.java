package org.example.feedablackhole.battle.rules;

import java.util.Map;

/**
 * 수치마다 늘어난 양을 정해 주는 테스트용 UpgradeValues. 정하지 않은 수치는 늘어난 양이 0이다.
 * valueOf는 지원하지 않는다(gainOf만 쓰는 코드를 확인하는 데 쓴다).
 */
record FixedGains(Map<String, Float> gains) implements UpgradeValues {

    @Override
    public float valueOf(String statId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public float gainOf(String statId) {
        return gains.getOrDefault(statId, 0f);
    }

}
