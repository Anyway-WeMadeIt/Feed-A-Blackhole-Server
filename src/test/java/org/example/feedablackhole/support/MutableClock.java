package org.example.feedablackhole.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 테스트가 시간을 앞당길 수 있는 시계. 서버 코드(토큰 발급·만료 검사, Auditing)가 같은 시계를 쓰므로
 * advance로 "15분이 지났다", "31일이 지났다"를 기다리지 않고 재현할 수 있다.
 * 시간은 앞으로만 간다. 서버의 요청 처리 스레드와 테스트 스레드가 함께 읽으므로 스레드 안전하다.
 */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

    public void advance(Duration duration) {
        if (duration.isNegative()) {
            throw new IllegalArgumentException("시간은 뒤로 갈 수 없다: " + duration);
        }
        now.updateAndGet(current -> current.plus(duration));
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

}
