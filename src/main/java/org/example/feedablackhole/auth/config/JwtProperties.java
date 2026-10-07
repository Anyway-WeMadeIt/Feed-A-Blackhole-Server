package org.example.feedablackhole.auth.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * application.properties의 app.jwt.* 설정. 값이 올바르지 않으면 서버가 시작되지 않는다.
 *
 * @param secret          HS256 서명 키. 256비트(32바이트) 이상이어야 한다.
 * @param issuer          토큰 발급자(iss). 검증 때 같은 값인지 확인한다.
 * @param accessTokenTtl  액세스 토큰 수명. 짧게 둔다(서버가 취소할 수 없으므로).
 * @param refreshTokenTtl 리프레시 토큰 수명.
 */
@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        @NotBlank @Size(min = 32, message = "app.jwt.secret(JWT_SECRET)은 32자 이상이어야 합니다.") String secret,
        @NotBlank String issuer,
        @NotNull Duration accessTokenTtl,
        @NotNull Duration refreshTokenTtl) {
}
