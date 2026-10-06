package org.example.feedablackhole.common.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 애플리케이션 전체가 쓰는 시계. 현재 시각이 필요한 코드는 Instant.now()를 직접 부르지 않고 이 Clock을 주입받는다.
 * JPA Auditing과 서비스 로직이 같은 시계로 시간을 재도록 하기 위한 설정.
 */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

}
