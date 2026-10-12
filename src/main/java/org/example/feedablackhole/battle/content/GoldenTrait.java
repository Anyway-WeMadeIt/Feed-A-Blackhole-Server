package org.example.feedablackhole.battle.content;

/**
 * 황금 성질의 수치(클라이언트 GoldenDefinition).
 *
 * @param multiplier      황금 적의 Gold 배율
 * @param critChance      처치 때 보너스 치명이 날 확률(0 ~ 1)
 * @param critRewardScale 보너스 치명의 Gold 배율(황금 적의 Gold × 이 값을 더 준다)
 */
public record GoldenTrait(float multiplier, float critChance, float critRewardScale) {
}
