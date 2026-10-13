package org.example.feedablackhole.battle.content;

/**
 * 이정표 하나(클라이언트 HqMilestone). 판이 이 Level에 닿으면 판이 끝나고, 결산이 잔액을 목표 잔액까지 채운다.
 * 전장 배율(fieldScale)은 검증에 쓰지 않아 내보내지 않는다.
 */
public record HqMilestone(int level, long targetGold) {

    /** 이정표 보상: 잔액을 목표 잔액까지 채우는 양(0 이상). */
    public long rewardFor(long currentGold) {
        return Math.max(0, targetGold - currentGold);
    }

}
