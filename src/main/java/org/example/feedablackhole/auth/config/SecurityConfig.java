package org.example.feedablackhole.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * API 접근 규칙. 토큰 없이 열려 있는 경로는 인증 API(/api/v1/auth/**)뿐이고, 나머지는 유효한 액세스 토큰이 있어야 한다.
 * 새로 토큰 없이 열어야 하는 경로(예: /system/status)가 생기면 여기에 추가한다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, RestAuthenticationEntryPoint entryPoint)
            throws Exception {
        http
                // 쿠키·세션이 아니라 Authorization 헤더의 토큰으로 인증하므로 CSRF 보호와 세션이 필요 없다.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        // /error: 서블릿 컨테이너가 오류를 이 경로로 넘길 때 인증 때문에 가려지지 않도록 연다.
                        .requestMatchers("/api/v1/auth/**", "/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling.authenticationEntryPoint(entryPoint))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(entryPoint)
                        .jwt(Customizer.withDefaults()));
        return http.build();
    }

}
