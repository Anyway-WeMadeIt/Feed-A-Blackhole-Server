package org.example.feedablackhole.battle.content;

/**
 * 판 규칙(클라이언트 BattleRules 에셋).
 *
 * @param timeLimitSeconds     노드 반영 전 한 판의 제한 시간(초)
 * @param killTimeBonusSeconds 적이 파괴될 때 시간 추가에 성공하면 더하는 초
 */
public record BattleRulesDefinition(float timeLimitSeconds, float killTimeBonusSeconds) {
}
