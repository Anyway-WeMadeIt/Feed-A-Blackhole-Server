package org.example.feedablackhole.sync.handler;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.example.feedablackhole.battle.rules.BattleRules;
import org.example.feedablackhole.battle.rules.RunSetup;
import org.example.feedablackhole.sync.log.LogType;
import org.example.feedablackhole.sync.log.payload.SettlementPayload;
import org.springframework.stereotype.Component;

/**
 * SETTLEMENT 처리기. 결산의 값들이 서로 일관적인지 확인하고(SettlementValidator), 통과하면
 * 서버가 계산한 결산 금액과 성장도를 진행도에 반영하고 열린 판을 닫는다.
 *
 * <p>판 설정은 열린 판의 시작 성장도와 지금 산 노드로 계산한다. 열린 판이 있을 때 노드 구매가 오면 구매 처리기가
 * 그 판을 포기로 닫으므로(계약 C31) 결산이 통과하는 판은 시작 뒤에 노드가 바뀌지 않았다. 지금의 노드는 판을 시작할 때의 노드와 같다.
 */
@Component
@RequiredArgsConstructor
public final class SettlementHandler implements LogHandler<SettlementPayload> {

    private final BattleRules rules;

    private final SettlementValidator validator = new SettlementValidator();

    @Override
    public LogType type() {
        return LogType.SETTLEMENT;
    }

    @Override
    public Class<SettlementPayload> payloadType() {
        return SettlementPayload.class;
    }

    @Override
    public HandleResult handle(SettlementPayload payload, SyncContext context) {
        // S1을 먼저 확인해야 열린 판의 성장도로 판 설정을 만들 수 있다
        Optional<SettlementValidator.Violation> violation = validator.checkRunOpen(payload, context);
        if (violation.isPresent()) {
            return rejected(violation);
        }

        OpenRun openRun = context.openRun().orElseThrow();
        RunSetup setup = rules.setupFor(openRun.startGrowthStage(), rules.upgradesFor(context.nodeRanks()));

        violation = validator.validate(payload, setup, context.gold());
        if (violation.isPresent()) {
            return rejected(violation);
        }

        // 검사를 모두 통과했다. 서버가 계산한 값을 적용한다
        long settledGold = SettlementValidator.expectedSettledGold(payload, setup, context.gold());
        int nextGrowthStage = SettlementValidator.expectedNextGrowthStage(payload, setup);

        context.earnGold(settledGold);
        context.setGrowthStage(nextGrowthStage);
        context.settleOpenRun(settledGold, nextGrowthStage);

        return HandleResult.applied();
    }

    private static HandleResult rejected(Optional<SettlementValidator.Violation> violation) {
        SettlementValidator.Violation found = violation.orElseThrow();
        return HandleResult.rejected(found.code(), found.message());
    }

}
