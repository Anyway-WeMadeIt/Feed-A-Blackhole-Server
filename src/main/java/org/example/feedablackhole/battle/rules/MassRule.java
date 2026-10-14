package org.example.feedablackhole.battle.rules;

/**
 * 질량 규칙(클라이언트 MassRule): 한 종류의 질량(%)이 그 종류의 색 분포를 정한다.
 */
public final class MassRule {

    // 노드를 사지 않은 질량(%)
    public static final float BASE = 100;

    // 겹침을 재는 구간의 길이
    private static final double WINDOW = 2;

    private MassRule() {
    }

    /**
     * 질량 mass(%)에서 색 tierCount개의 비율을 계산한다(합은 1).
     *
     * @return 색 등급 순서대로의 비율. 0이면 그 색은 나올 수 없다
     */
    public static float[] tierRatios(float mass, int tierCount) {
        if (Float.isNaN(mass) || Float.isInfinite(mass) || mass < 0) {
            throw new IllegalArgumentException("질량은 0 이상의 유한한 값이어야 한다. 받은 값: " + mass + "%.");
        }
        if (tierCount < 1) {
            throw new IllegalArgumentException("색이 하나 이상 필요하다. 받은 값: " + tierCount);
        }

        // 질량을 배수로 바꾼다(float을 double로 넓혀 계산). 구간은 [low, high]
        double high = mass / 100.0;
        double low = high - WINDOW;
        float[] ratios = new float[tierCount];

        // 색 tier의 칸 [tier, tier+1]과 구간이 겹치는 길이 ÷ 2
        for (int tier = 0; tier < tierCount; tier++) {
            ratios[tier] = (float) (overlap(low, high, tier, tier + 1) / WINDOW);
        }

        // 축 밖으로 나간 부분: 0 아래는 첫 색에, 색 수 위는 마지막 색에 붙인다
        ratios[0] += (float) (Math.max(0, Math.min(high, 0) - low) / WINDOW);
        ratios[tierCount - 1] += (float) (Math.max(0, high - Math.max(low, tierCount)) / WINDOW);

        return ratios;
    }

    // 두 구간 [a0, a1]과 [b0, b1]이 겹치는 길이(겹치지 않으면 0)
    private static double overlap(double a0, double a1, double b0, double b1) {
        return Math.max(0, Math.min(a1, b1) - Math.max(a0, b0));
    }

}
