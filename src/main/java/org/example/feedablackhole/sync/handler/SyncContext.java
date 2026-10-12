package org.example.feedablackhole.sync.handler;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.example.feedablackhole.sync.log.payload.RunStartPayload;

/**
 * 처리 중인 계정의 진행도(docs/server-sync-contracts.md 4.3). 엔진이 잠근 엔티티 위에 구현한다.
 * 처리기는 이것만으로 진행도를 읽고 바꾼다(저장소와 트랜잭션은 모른다).
 *
 * <p>변경 메서드는 검사를 모두 통과한 뒤에만 부른다. 거부하는 로그에서 변경 메서드를 부르면 엔진이 버그로 보고 요청 전체를 롤백한다.
 */
public interface SyncContext {

    long accountId();

    /** 지금 처리 중인 로그의 seq. */
    long seq();

    // ---------- 읽기 ----------

    long gold();

    int growthStage();

    /** 산 노드(nodeId → rank). 처음 산 순서이고 읽기 전용이다. 사지 않은 노드는 없다. */
    Map<String, Integer> nodeRanks();

    Optional<OpenRun> openRun();

    /** 이 계정에서 이미 쓴 runId인가(열린 판, 닫힌 판 모두). */
    boolean runIdUsed(String runId);

    // ---------- 변경 (검사를 모두 통과한 뒤에만) ----------

    /** Gold를 쓴다. amount는 0 이상, gold() 이하. */
    void spendGold(long amount);

    /** Gold를 더한다. amount는 0 이상. */
    void earnGold(long amount);

    /** 노드의 Rank를 정한다. 새 노드면 처음 산 순서의 끝에 붙는다. rank는 1 이상. */
    void setNodeRank(String nodeId, int rank);

    void setGrowthStage(int growthStage);

    /** 새 게임: Gold 0, 성장도 0, 산 노드 없음. 열린 판이 있으면 포기로 닫는다. lastAppliedSeq는 그대로 이어진다. */
    void resetProgress();

    /** 열린 판을 포기로 닫는다. 열린 판이 없으면 아무것도 하지 않는다. */
    void abandonOpenRun();

    /**
     * 판을 연다. 시작 Gold·성장도는 지금 서버 진행도 값으로 적는다. 열린 판이 있으면 먼저 abandonOpenRun()을 부른 뒤 부른다.
     *
     * @param suspicions 판 시작 값이 서버 계산과 다른 사유. 없으면 빈 목록. 하나 이상이면 계정의 의심 수가 1 오른다
     */
    void openRun(RunStartPayload payload, List<SuspicionReason> suspicions);

    /**
     * 열린 판을 결산 완료로 닫는다(기록만). 진행도 반영은 earnGold, setGrowthStage로 따로 한다.
     *
     * @param settledGold     서버가 계산한 결산 금액
     * @param nextGrowthStage 서버가 계산한 결산 후 성장도
     */
    void settleOpenRun(long settledGold, int nextGrowthStage);

}
