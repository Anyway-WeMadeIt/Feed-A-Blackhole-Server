package org.example.feedablackhole.battle.content;

/**
 * 적 종류에 속하지 않는 수치의 statId(클라이언트 UpgradeStat.Timer, UpgradeStat.GrowthTime).
 *
 * @param timer      제한 시간에 더하는 수치
 * @param growthTime 블랙홀 Level업마다 제한 시간에 더하는 수치
 */
public record GlobalStatIds(String timer, String growthTime) {
}
