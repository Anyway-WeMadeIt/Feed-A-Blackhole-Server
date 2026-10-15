package org.example.feedablackhole.sync.handler;

import org.example.feedablackhole.sync.log.LogType;
import org.example.feedablackhole.sync.log.payload.ResetPayload;
import org.springframework.stereotype.Component;

/**
 * RESET(새 게임) 로그의 처리기. 검증 없이 진행도를 처음 상태로 되돌린다(계획서 6.2).
 * 엔진(LogHandlerRegistry)이 로그 타입으로 이 처리기를 찾아 부른다.
 */
@Component
public final class ResetHandler implements LogHandler<ResetPayload> {

    // 이 처리기가 맡는 로그 타입. 엔진이 이 값으로 처리기를 찾는다
    @Override
    public LogType type() {
        return LogType.RESET;
    }

    // 이 로그의 payload 타입. 계약(3.3)의 타입과 다르면 서버가 시작하지 않는다
    @Override
    public Class<ResetPayload> payloadType() {
        return ResetPayload.class;
    }

    // 진행도를 초기화한다: Gold 0, 성장도 0, 산 노드 없음, 열린 판은 포기로 닫는다.
    // 검사할 규칙이 없어서 거부하는 경우가 없고, 항상 반영한다
    @Override
    public HandleResult handle(ResetPayload payload, SyncContext context) {
        context.resetProgress();
        return HandleResult.applied();
    }
}
