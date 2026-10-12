package org.example.feedablackhole.sync.handler;

import org.example.feedablackhole.sync.log.LogType;

/**
 * 로그 타입 하나의 처리기(docs/server-sync-contracts.md 4.1, 4.3). Spring 빈으로 등록하며, 타입마다 정확히 하나여야 한다.
 *
 * <p>엔진이 seq·형식·콘텐츠 버전 검사를 마친 뒤 부른다. 그래서 payload는 3.3의 형식을 만족한다(null인 필드가 없다).
 * 처리기는 타입별 규칙을 검사하고, 통과하면 {@link SyncContext}의 변경 메서드로 진행도를 바꾼다.
 * <b>거부할 때는 아무것도 바꾸지 않는다.</b> 검사를 모두 마친 뒤에만 변경 메서드를 부른다.
 *
 * @param <P> payload 타입(sync.log.payload)
 */
public interface LogHandler<P> {

    LogType type();

    Class<P> payloadType();

    HandleResult handle(P payload, SyncContext context);

}
