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

    /**
     * 오류 코드의 기본 메시지 대신 상황에 맞는 설명을 담을 때. 클라이언트는 code로 분기하고 message는 디버깅용이다.
     */
    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

}
