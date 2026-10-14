package org.example.feedablackhole.sync.handler;

import org.example.feedablackhole.sync.log.payload.RunStartPayload;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 처리기 단위 테스트용 가짜 SyncContext. 진행도(Gold, 성장도, 산 노드, 열린 판)를 메모리에 들고 있고,
 * 변경 메서드는 실제 구현(JpaSyncContext)과 같은 규칙으로 값을 바꾼다. DB와 엔티티는 필요 없다.
 *
 * <p>테스트는 with... 메서드로 시작 상태를 만들고(변경으로 세지 않는다), 처리기를 부른 뒤
 * 값과 {@link #changes()}를 확인한다. 거부한 로그는 changes()가 0이어야 한다(거부할 때는 아무것도 바꾸지 않는다).
 */
final class FakeSyncContext implements SyncContext {

    private long accountId = 1;
    private long seq = 1;
    private long gold;
    private int growthStage;
    private final Map<String, Integer> nodeRanks = new LinkedHashMap<>(); // 처음 산 순서
    private OpenRun openRun;                                              // 열린 판. 없으면 null
    private final Set<String> usedRunIds = new HashSet<>();

    // 변경 메서드를 부른 횟수와, 처리기가 남긴 기록(테스트가 확인한다)
    private int changes;
    private int abandonedRuns;
    private List<SuspicionReason> suspicions = List.of();
    private boolean settled;
    private long settledGold;
    private int settledNextGrowthStage;

    // ---------- 테스트가 시작 상태를 만드는 메서드(변경으로 세지 않는다) ----------

    FakeSyncContext withSeq(long seq) {
        this.seq = seq;
        return this;
    }

    FakeSyncContext withGold(long gold) {
        this.gold = gold;
        return this;
    }

    FakeSyncContext withGrowthStage(int growthStage) {
        this.growthStage = growthStage;
        return this;
    }

    // 산 노드를 더한다. 처음 산 순서대로 쌓인다
    FakeSyncContext withNode(String nodeId, int rank) {
        nodeRanks.put(nodeId, rank);
        return this;
    }

    // 열린 판이 있는 상태로 만든다. 그 runId는 이미 쓴 것이 된다
    FakeSyncContext withOpenRun(OpenRun openRun) {
        this.openRun = openRun;
        usedRunIds.add(openRun.runId());
        return this;
    }

    // 이미 닫힌 판이 쓴 runId를 더한다(중복 runId 확인용)
    FakeSyncContext withUsedRunId(String runId) {
        usedRunIds.add(runId);
        return this;
    }

    // ---------- 테스트가 처리 결과를 확인하는 메서드 ----------

    // 변경 메서드를 부른 횟수. 거부한 로그는 0이어야 한다
    int changes() {
        return changes;
    }

    // 포기로 닫힌 판의 수(RESET, abandonOpenRun)
    int abandonedRuns() {
        return abandonedRuns;
    }

    // 판을 열 때 받은 의심 사유(openRun이 마지막으로 불린 때)
    List<SuspicionReason> suspicions() {
        return suspicions;
    }

    // 결산으로 판을 닫았는가, 그때 받은 값
    boolean settled() {
        return settled;
    }

    long settledGold() {
        return settledGold;
    }

    int settledNextGrowthStage() {
        return settledNextGrowthStage;
    }

    // ---------- 읽기 ----------

    @Override
    public long accountId() {
        return accountId;
    }

    @Override
    public long seq() {
        return seq;
    }

    @Override
    public long gold() {
        return gold;
    }

    @Override
    public int growthStage() {
        return growthStage;
    }

    // 읽기 전용 복사본. 처리기가 이것을 고쳐 진행도를 바꾸는 일이 없게 한다
    @Override
    public Map<String, Integer> nodeRanks() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(nodeRanks));
    }

    @Override
    public Optional<OpenRun> openRun() {
        return Optional.ofNullable(openRun);
    }

    @Override
    public boolean runIdUsed(String runId) {
        return usedRunIds.contains(runId);
    }

    // ---------- 변경 ----------

    @Override
    public void spendGold(long amount) {
        if (amount < 0 || amount > gold) {
            throw new IllegalArgumentException("쓸 수 없는 Gold다: " + amount + "(잔액 " + gold + ")");
        }
        changes++;
        gold -= amount;
    }

    @Override
    public void earnGold(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Gold는 0 이상 더한다: " + amount);
        }
        changes++;
        gold = Math.addExact(gold, amount);
    }

    @Override
    public void setNodeRank(String nodeId, int rank) {
        if (rank < 1) {
            throw new IllegalArgumentException("Rank는 1 이상이다: " + rank);
        }
        changes++;
        nodeRanks.put(nodeId, rank); // 새 노드는 끝에 붙고, 이미 있는 노드는 제자리에서 바뀐다
    }

    @Override
    public void setGrowthStage(int growthStage) {
        changes++;
        this.growthStage = growthStage;
    }

    @Override
    public void resetProgress() {
        changes++;
        closeOpenRunAsAbandoned();
        gold = 0;
        growthStage = 0;
        nodeRanks.clear();
    }

    @Override
    public void abandonOpenRun() {
        if (openRun == null) {
            return; // 열린 판이 없으면 아무것도 하지 않는다(변경으로 세지 않는다)
        }
        changes++;
        closeOpenRunAsAbandoned();
    }

    @Override
    public void openRun(RunStartPayload payload, List<SuspicionReason> suspicions) {
        if (openRun != null) {
            throw new IllegalStateException("열린 판이 있다. 판을 열기 전에 abandonOpenRun()을 불러야 한다.");
        }
        changes++;
        // 시작 Gold·성장도는 클라이언트가 보낸 값이 아니라 지금 서버 진행도 값이다
        openRun = new OpenRun(payload.runId(), payload.seed(), growthStage, gold, seq);
        usedRunIds.add(payload.runId());
        this.suspicions = List.copyOf(suspicions);
    }

    @Override
    public void settleOpenRun(long settledGold, int nextGrowthStage) {
        if (openRun == null) {
            throw new IllegalStateException("열린 판이 없다.");
        }
        changes++;
        settled = true;
        this.settledGold = settledGold;
        this.settledNextGrowthStage = nextGrowthStage;
        openRun = null; // Gold·성장도 반영은 earnGold, setGrowthStage로 따로 한다
    }

    // 열린 판이 있으면 포기로 닫는다(변경 횟수는 부른 메서드가 센다)
    private void closeOpenRunAsAbandoned() {
        if (openRun != null) {
            abandonedRuns++;
            openRun = null;
        }
    }

}
