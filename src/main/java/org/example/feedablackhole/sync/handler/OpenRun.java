package org.example.feedablackhole.sync.handler;

/**
 * 열린 판. 시작 Gold·성장도는 판을 열 때의 서버 진행도 값이다.
 *
 * @param startedSeq 판을 연 RUN_START의 seq
 */
public record OpenRun(String runId, int seed, int startGrowthStage, long startGold, long startedSeq) {
}
