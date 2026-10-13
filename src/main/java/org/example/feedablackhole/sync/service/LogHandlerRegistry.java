package org.example.feedablackhole.sync.service;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.example.feedablackhole.sync.handler.LogHandler;
import org.example.feedablackhole.sync.log.LogType;
import org.springframework.stereotype.Component;

/**
 * 로그 타입 → 처리기. 처리기는 Spring 빈으로 등록된 LogHandler들이다(docs/server-sync-contracts.md 4.3).
 *
 * <p>같은 타입의 처리기가 둘 이상이거나 payload 타입이 계약과 다르면 서버를 시작하지 않는다.
 * 처리기가 없는 타입은 경고만 남긴다(처리기를 작업마다 나눠 구현하는 동안). 그 타입의 로그가 오면 서버 오류(500)다.
 */
@Slf4j
@Component
public class LogHandlerRegistry {

    private final Map<LogType, LogHandler<?>> handlers = new EnumMap<>(LogType.class);

    public LogHandlerRegistry(List<LogHandler<?>> handlers) {
        for (LogHandler<?> handler : handlers) {
            LogType type = handler.type();
            if (this.handlers.put(type, handler) != null) {
                throw new IllegalStateException("로그 타입 " + type + "의 처리기가 둘 이상이다.");
            }
            Class<?> expected = LogParser.PAYLOAD_TYPES.get(type);
            if (!expected.equals(handler.payloadType())) {
                throw new IllegalStateException("로그 타입 " + type + "의 payload 타입은 " + expected.getSimpleName()
                        + "이어야 한다: " + handler.payloadType().getSimpleName());
            }
        }
        List<LogType> missing = Arrays.stream(LogType.values()).filter(type -> !this.handlers.containsKey(type)).toList();
        if (!missing.isEmpty()) {
            log.warn("처리기가 없는 로그 타입: {}. 이 타입의 로그가 오면 서버 오류가 된다.", missing);
        }
    }

    public Optional<LogHandler<?>> find(LogType type) {
        return Optional.ofNullable(handlers.get(type));
    }

}
