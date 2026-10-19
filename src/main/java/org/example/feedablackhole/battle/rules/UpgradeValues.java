package org.example.feedablackhole.battle.rules;

/**
 * 산 노드로 계산한 수치 값(클라이언트 UpgradeStatValues). 값은 시트 단위 그대로다(Percent면 125 = 125%).
 * 없는 statId면 IllegalArgumentException.
 */
public interface UpgradeValues {

    /** 기본값 + 산 효과 값의 합을 [min, max]로 자른 값: (float) min(max, max(min, (double) default + sum)). */
    float valueOf(String statId);

    /** 기본값에서 늘어난 양: valueOf(statId) - default (float 뺄셈). 노드를 사지 않았으면 0. */
    float gainOf(String statId);

}
