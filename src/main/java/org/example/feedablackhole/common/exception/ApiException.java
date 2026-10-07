package org.example.feedablackhole.common.exception;

import lombok.Getter;

/**
 * 규칙 위반을 클라이언트에 알려야 할 때 던지는 예외. GlobalExceptionHandler가 오류 응답으로 바꾼다.
 */
@Getter
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

}
