package org.example.feedablackhole.auth.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.example.feedablackhole.common.dto.ErrorResponse;
import org.example.feedablackhole.common.exception.ErrorCode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * "인증되지 않은 사용자를 어떻게 인증 절차로 안내할 것인가"를 정한다.
 * 인증이 필요한 API에 토큰이 없거나(만료·위조 포함) 유효하지 않을 때의 응답. 공통 오류 형식 그대로 401을 돌려준다.
 *      전통적인 웹사이트에서는 로그인 페이지로 리다이렉트하지만, REST API에선 리다이렉트할 곳이 없으니 401 응답을 돌려준다.
 * 보안 필터는 컨트롤러보다 앞에서 동작해 GlobalExceptionHandler가 닿지 않으므로 여기서 직접 만든다.
 */
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        ErrorCode code = ErrorCode.UNAUTHORIZED;
        response.setStatus(code.getStatus().value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
                objectMapper.writeValueAsString(ErrorResponse.of(code.name(), code.getMessage())));
    }

}
