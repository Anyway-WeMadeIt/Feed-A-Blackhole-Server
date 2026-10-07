package org.example.feedablackhole.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * 클라이언트에 내려가는 오류 코드. 클라이언트는 이 code로 분기하고, message는 디버깅용이다.
 * 새 오류를 추가하면 docs/api-spec.md의 오류 표도 함께 고친다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "인증 정보가 올바르지 않습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 Content-Type입니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;

    /**
     * Spring MVC가 스스로 던지는 오류(잘못된 JSON, 없는 경로 등)를 우리 오류 코드로 옮길 때 쓴다.
     */
    public static ErrorCode fromStatus(HttpStatusCode statusCode) {
        for (ErrorCode code : values()) {
            if (code.status.value() == statusCode.value()) {
                return code;
            }
        }
        return statusCode.is4xxClientError() ? INVALID_REQUEST : INTERNAL_ERROR;
    }

}
