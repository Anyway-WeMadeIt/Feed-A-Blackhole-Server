package org.example.feedablackhole.battle.rules;

/**
 * 크기 규칙(클라이언트 SizeRule): 크기 k인 적의 Gold·EXP에 곱하는 배율을 정한다.
 */
public final class SizeRule {

    // 노드를 사지 않은 크기
    public static final int BASE = 1;

    // 노드 저작 실수를 막는 상한
    public static final int MAX = 20;

    // 크기가 1 오를 때마다 늘어나는 배율. float 상수라서 곱셈이 float으로 된다
    private static final float STAT_STEP = 1.0f;

    private SizeRule() {
    }

    // Gold·EXP 배율: 1 + (크기 - 1) × 1.0. 크기 2면 2배, 3이면 3배
    public static float statMultiplier(int size) {
        return 1 + (require(size) - BASE) * STAT_STEP;
    }

    // 크기가 1 ~ 20 안인지 확인하고 그대로 돌려준다. 밖이면 IllegalArgumentException
    private static int require(int size) {
        if (size < BASE || size > MAX) {
            throw new IllegalArgumentException("크기는 " + BASE + "부터 " + MAX + "까지다. 받은 값: " + size);
        }
        return size;
    }

}
