package org.example.feedablackhole.common.config;

import java.time.Clock;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA Auditing 활성화. 감사 시각을 시스템 시계가 아니라 Clock 빈에서 가져오므로, 서비스와 같은 시계를 쓴다(테스트에서 고정 가능).
 * 메인 클래스가 아니라 여기에 두는 이유: 메인 클래스에 두면 JPA가 없는 슬라이스 테스트(@WebMvcTest 등)가 깨진다.
 */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig {

    @Bean
    DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(clock.instant());
    }

}
