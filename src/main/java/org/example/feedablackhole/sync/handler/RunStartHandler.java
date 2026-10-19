package org.example.feedablackhole.sync.handler;

import lombok.RequiredArgsConstructor;
import org.example.feedablackhole.battle.content.SupplyEntry;
import org.example.feedablackhole.battle.rules.BattleRules;
import org.example.feedablackhole.battle.rules.RunSetup;
import org.example.feedablackhole.battle.rules.UpgradeValues;
import org.example.feedablackhole.sync.log.LogType;
import org.example.feedablackhole.sync.log.RejectCode;
import org.example.feedablackhole.sync.log.payload.RunStartPayload;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * RUN_START(판 시작) 로그의 처리기. 진행도(Gold, 산 노드)에는 영향이 없다(계획서 6.2).
 * 열린 판이 있으면 포기로 닫고(결정 D5), 서버 진행도로 계산한 값과 클라이언트가 보낸 값을 비교해
 * 다르면 거부하지 않고 의심 사유만 남긴 채 판을 연다(결정 D8). runId가 이미 쓰인 값이면 거부한다.
 */
@Component
@RequiredArgsConstructor
public final class RunStartHandler implements LogHandler<RunStartPayload> {

    // 서버가 판의 정답(시작 Level, 제한 시간, 시작 공급)을 계산할 때 쓰는 전투 규칙(W1-4)
    private final BattleRules battleRules;

    // 이 처리기가 맡는 로그 타입. 엔진이 이 값으로 처리기를 찾는다
    @Override
    public LogType type() {
        return LogType.RUN_START;
    }

    // 이 로그의 payload 타입. 계약(3.3)의 타입과 다르면 서버가 시작하지 않는다
    @Override
    public Class<RunStartPayload> payloadType() {
        return RunStartPayload.class;
    }

    // 판을 시작한다. 이미 쓴 runId면 거부하고, 아니면 서버 계산과 비교해 의심 사유를 모은 뒤 판을 연다
    @Override
    public HandleResult handle(RunStartPayload payload, SyncContext context) {
        // 이미 쓰인 runId는 거부한다(DUPLICATE_RUN_ID). 거부할 때는 진행도를 바꾸면 안 되므로 열린 판을 닫기 전에 확인한다
        if (context.runIdUsed(payload.runId())) {
            return HandleResult.rejected(RejectCode.DUPLICATE_RUN_ID, "이미 쓴 runId다: " + payload.runId());
        }

        // 서버가 가진 산 노드와 성장도로 이 판의 정답을 계산한다. 클라이언트가 보낸 값이 아니라 서버 값을 쓴다
        UpgradeValues upgrades = battleRules.upgradesFor(context.nodeRanks());
        RunSetup setup = battleRules.setupFor(context.growthStage(), upgrades);

        // 클라이언트가 보낸 값과 서버의 정답을 비교한다. 다르면 거부하지 않고 의심 사유만 모은다(사유 목록의 선언 순서대로)
        List<SuspicionReason> suspicions = new ArrayList<>();
        if (payload.startGrowthStage() != context.growthStage()) {
            suspicions.add(SuspicionReason.START_GROWTH_STAGE_MISMATCH);
        }

        if (payload.startGold() != context.gold()) {
            suspicions.add(SuspicionReason.START_GOLD_MISMATCH);
        }

        if (payload.startLevel() != setup.startLevel()) {
            suspicions.add(SuspicionReason.START_LEVEL_MISMATCH);
        }

        // 제한 시간은 0.001초 이내의 차이는 같다고 본다(계약 4.4, 결정 C22)
        if (Math.abs((double) payload.timeLimitSeconds() - setup.timeLimitSeconds()) > 0.001) {
            suspicions.add(SuspicionReason.TIME_LIMIT_MISMATCH);
        }

        // 시작 공급은 종류, 수, 순서까지 같아야 한다. payload의 항목(SupplyItem)을 서버 쪽 타입(SupplyEntry)으로 바꿔 비교한다
        List<SupplyEntry> clientSupply = payload.startSupply().stream()
                .map(item -> new SupplyEntry(item.enemyId(), item.count()))
                .toList();

        if (!clientSupply.equals(setup.startSupply())) {
            suspicions.add(SuspicionReason.START_SUPPLY_MISMATCH);
        }

        // 열린 판이 있으면 포기로 닫고(보상 없음) 새 판을 연다. 의심 사유는 판에 기록만 되고 처리는 계속된다
        context.abandonOpenRun();
        context.openRun(payload, suspicions);
        return HandleResult.applied();
    }

}
