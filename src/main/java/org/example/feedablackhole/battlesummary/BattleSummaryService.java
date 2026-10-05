package org.example.feedablackhole.battlesummary;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

// receive 자체에는 트랜잭션을 두지 않는다.
// saveAndFlush가 자신의 트랜잭션에서 INSERT를 즉시 DB에 반영하므로,
// unique 충돌이 나면 그 트랜잭션이 롤백된 뒤 여기서 예외를 받을 수 있다.
// 이후 다시 조회해 실제 중복 battleId인지 확인한다.
@Service
@RequiredArgsConstructor
public class BattleSummaryService {

    private final BattleSummaryRepository repository;

    public ReceiveResult receive(BattleSummary summary) {
        if (repository.existsByBattleId(summary.getBattleId())) {
            return ReceiveResult.ALREADY_RECEIVED;
        }

        try {
            repository.saveAndFlush(summary);
            return ReceiveResult.CREATED;
        } catch (DataIntegrityViolationException e) {
            // 확인과 저장 사이에 같은 판이 먼저 저장됐다(동시 재전송).
            if (repository.existsByBattleId(summary.getBattleId())) {
                return ReceiveResult.ALREADY_RECEIVED;
            }
            // 같은 판이 없으면 다른 제약 위반(값이 너무 긺 등)이다. 숨기지 않는다.
            throw e;
        }
    }
}
