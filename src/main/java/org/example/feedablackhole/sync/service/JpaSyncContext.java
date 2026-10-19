package org.example.feedablackhole.sync.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.example.feedablackhole.progress.entity.PlayerNodeRank;
import org.example.feedablackhole.progress.entity.PlayerProgress;
import org.example.feedablackhole.progress.repository.PlayerNodeRankRepository;
import org.example.feedablackhole.sync.entity.BattleRun;
import org.example.feedablackhole.sync.handler.OpenRun;
import org.example.feedablackhole.sync.handler.SuspicionReason;
import org.example.feedablackhole.sync.handler.SyncContext;
import org.example.feedablackhole.sync.log.payload.RunStartPayload;
import org.example.feedablackhole.sync.repository.BattleRunRepository;

/**
 * 잠근 진행도 엔티티 위의 SyncContext(docs/server-sync-contracts.md 4.3). 요청 하나 동안만 쓴다.
 * 바꾸는 메서드마다 변경 횟수를 세어, 엔진이 "거부했는데 바꾼" 처리기를 찾게 한다.
 */
class JpaSyncContext implements SyncContext {

    private final PlayerProgress progress;
    private final PlayerNodeRankRepository nodeRankRepository;
    private final BattleRunRepository runRepository;

    // 산 노드(처음 산 순서). 엔티티를 그대로 들고 고친다.
    private final Map<String, PlayerNodeRank> nodes;

    private BattleRun openRun;
    private long seq;
    private int changes;

    JpaSyncContext(PlayerProgress progress, List<PlayerNodeRank> nodes,
            PlayerNodeRankRepository nodeRankRepository, BattleRunRepository runRepository) {
        this.progress = progress;
        this.nodeRankRepository = nodeRankRepository;
        this.runRepository = runRepository;
        this.nodes = new LinkedHashMap<>();
        nodes.forEach(node -> this.nodes.put(node.getNodeId(), node));
        this.openRun = progress.getOpenRunId() == null ? null
                : runRepository.findById(progress.getOpenRunId())
                        .orElseThrow(() -> new IllegalStateException("열린 판이 없다. id=" + progress.getOpenRunId()));
    }

    /** 다음 로그를 처리하기 전에 엔진이 부른다. */
    void begin(long seq) {
        this.seq = seq;
    }

    /** 지금까지 바꾼 횟수. */
    int changes() {
        return changes;
    }

    List<PlayerNodeRank> nodeEntities() {
        return List.copyOf(nodes.values());
    }

    // ---------- 읽기 ----------

    @Override
    public long accountId() {
        return progress.getAccountId();
    }

    @Override
    public long seq() {
        return seq;
    }

    @Override
    public long gold() {
        return progress.getGold();
    }

    @Override
    public int growthStage() {
        return progress.getGrowthStage();
    }

    @Override
    public Map<String, Integer> nodeRanks() {
        return Collections.unmodifiableMap(nodes.values().stream().collect(Collectors.toMap(
                PlayerNodeRank::getNodeId, PlayerNodeRank::getRank, (left, right) -> left, LinkedHashMap::new)));
    }

    @Override
    public Optional<OpenRun> openRun() {
        if (openRun == null) {
            return Optional.empty();
        }
        return Optional.of(new OpenRun(openRun.getRunId(), openRun.getSeed(), openRun.getStartGrowthStage(),
                openRun.getStartGold(), openRun.getStartedSeq()));
    }

    @Override
    public boolean runIdUsed(String runId) {
        return runRepository.existsByAccountIdAndRunId(progress.getAccountId(), runId);
    }

    // ---------- 변경 ----------

    @Override
    public void spendGold(long amount) {
        if (amount < 0 || amount > progress.getGold()) {
            throw new IllegalArgumentException("쓸 수 없는 Gold다: " + amount + "(잔액 " + progress.getGold() + ")");
        }
        changed();
        progress.changeGold(progress.getGold() - amount);
    }

    @Override
    public void earnGold(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Gold는 0 이상 더한다: " + amount);
        }
        changed();
        progress.changeGold(Math.addExact(progress.getGold(), amount));
    }

    @Override
    public void setNodeRank(String nodeId, int rank) {
        if (rank < 1) {
            throw new IllegalArgumentException("Rank는 1 이상이다: " + rank);
        }
        changed();
        PlayerNodeRank existing = nodes.get(nodeId);
        if (existing != null) {
            existing.changeRank(rank);
        } else {
            nodes.put(nodeId, nodeRankRepository.save(PlayerNodeRank.of(progress.getAccount(), nodeId, rank)));
        }
    }

    @Override
    public void setGrowthStage(int growthStage) {
        changed();
        progress.changeGrowthStage(growthStage);
    }

    @Override
    public void resetProgress() {
        changed();
        abandonOpenRun();
        progress.changeGold(0);
        progress.changeGrowthStage(PlayerProgress.INITIAL_GROWTH_STAGE);
        // 하나씩 지운다. 벌크 삭제(deleteAllByAccountId)는 영속성 컨텍스트를 비워 잠근 진행도 엔티티까지 떼어 내기 때문이다.
        // 산 노드 수만큼 DELETE가 나가지만, 노드 수 상한이 분명하고(최대 전체 노드 수) 새 게임 요청은 게임 흐름 상 드물기 때문에 수용한다.
        nodeRankRepository.deleteAll(nodes.values());
        nodeRankRepository.flush();
        nodes.clear();
    }

    @Override
    public void abandonOpenRun() {
        if (openRun == null) {
            return;
        }
        changed();
        openRun.abandon(seq);
        openRun = null;
        progress.closeRun();
    }

    @Override
    public void openRun(RunStartPayload payload, List<SuspicionReason> suspicions) {
        if (openRun != null) {
            throw new IllegalStateException("열린 판이 있다. 먼저 abandonOpenRun()을 부른다.");
        }
        changed();
        String reasons = suspicions.isEmpty() ? null
                : suspicions.stream().map(Enum::name).collect(Collectors.joining(","));
        openRun = runRepository.save(BattleRun.open(progress.getAccount(), payload.runId(), payload.seed(),
                progress.getGrowthStage(), progress.getGold(), seq, reasons));
        progress.openRun(openRun.getId());
        if (!suspicions.isEmpty()) {
            progress.countSuspiciousRun();
        }
    }

    @Override
    public void settleOpenRun(long settledGold, int nextGrowthStage) {
        if (openRun == null) {
            throw new IllegalStateException("열린 판이 없다.");
        }
        changed();
        openRun.settle(seq, settledGold, nextGrowthStage);
        openRun = null;
        progress.closeRun();
    }

    private void changed() {
        changes++;
    }

}
