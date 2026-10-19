package org.example.feedablackhole.battle.content;

/**
 * 색 등급 한 줄: 크기 1의 베이스 Gold·EXP(0 이상). 크기 k는 둘 모두에 k를 곱한다.
 */
public record EnemyTier(long gold, long exp) {
}
