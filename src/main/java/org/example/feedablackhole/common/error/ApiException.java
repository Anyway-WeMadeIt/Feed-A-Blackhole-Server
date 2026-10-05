package org.example.feedablackhole.common.error;

import java.util.List;
import lombok.Getter;

// 정해 둔 에러 코드로 답할 때 던진다. ApiExceptionHandler가 그 코드의 상태와 ErrorResponse로 바꾼다.
@Getter
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<ErrorResponse.InvalidField> errors;

    public ApiException(ErrorCode errorCode) {
        this(errorCode, List.of());
    }

    public ApiException(ErrorCode errorCode, List<ErrorResponse.InvalidField> errors) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.errors = List.copyOf(errors);
    }
}
