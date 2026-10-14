package org.example.feedablackhole.battle.rules;

import java.util.Map;

/**
 * 수치마다 늘어난 양을 정해 주는 테스트용 UpgradeValues. 정하지 않은 수치는 늘어난 양이 0이다.
 * valueOf는 지원하지 않는다. 그래서 valueOf를 부르는 코드(종류별 구성 등)에 닿기 전에
 * 예외로 끝나는 경우나, 질량 수치가 없는 종류만 만드는 경우에만 쓸 수 있다.
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
