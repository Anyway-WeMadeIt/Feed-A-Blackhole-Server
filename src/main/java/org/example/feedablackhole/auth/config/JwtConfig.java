package org.example.feedablackhole.auth.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * 액세스 토큰(JWT)을 만드는 인코더와 검증하는 디코더. 서버 한 대가 만들고 검증하므로 대칭 키(HS256)를 쓴다.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    @Bean
    JwtEncoder jwtEncoder(JwtProperties properties) {
        // 알고리즘은 호출부(encode)에서 전달하는 헤더를 보고 결정된다.
        // 인코더는 공급원(ImmutableSecret)에서 맞는 키를 찾아 서명한다..
        // 이 인코더는 대칭 키 하나만 가지고 있어서, HS256 알고리즘을 명시해주지 않으면 실패한다.
        //      Spring의 기본 알고리즘은 RS256(공개키 방식)이라, 인코더가 보유한 키와 맞지 않는다.
        return new NimbusJwtEncoder(new ImmutableSecret<>(signingKey(properties)));
    }

    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties, Clock clock) {
        // HS256 알고리즘만 사용하도록 고정한다.
        // alg: none(서명 없음)이나 다른 알고리즘을 통한 검증 우회를 방지한다.
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(signingKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        // 같은 서버가 발급하고 검증하므로 시계 허용 오차(기본 60초)는 두지 않고, 앱 전체가 쓰는 Clock으로 만료를 잰다.
        JwtTimestampValidator timestampValidator = new JwtTimestampValidator(Duration.ZERO);
        timestampValidator.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestampValidator,
                new JwtIssuerValidator(properties.issuer())));
        return decoder;
    }

    private static SecretKey signingKey(JwtProperties properties) {
        return new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

}
