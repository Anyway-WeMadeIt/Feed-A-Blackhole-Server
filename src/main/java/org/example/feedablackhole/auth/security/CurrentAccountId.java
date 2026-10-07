package org.example.feedablackhole.auth.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

/**
 * 컨트롤러 파라미터에 붙이면 액세스 토큰의 sub(계정 ID)를 Long으로 바꿔 넣어 준다.
 * 인증된 요청만 컨트롤러에 도달하므로 값이 null인 경우는 없다. 파라미터 타입은 Long이어야 한다(long이면 주입되지 않는다).
 *
 * <p>@AuthenticationPrincipal이 인증 정보의 principal(Jwt)에서 expression을 계산해 넣는 기능을 이용한다.
 * 계정 ID를 쓰는 곳마다 Long.parseLong(jwt.getSubject())를 반복하지 않으려는 용도다.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@AuthenticationPrincipal(expression = "T(java.lang.Long).valueOf(subject)")
public @interface CurrentAccountId {
}
