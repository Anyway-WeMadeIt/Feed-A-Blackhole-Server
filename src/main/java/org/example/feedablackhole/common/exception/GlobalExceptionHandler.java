package org.example.feedablackhole.common.exception;

import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.example.feedablackhole.common.dto.ErrorResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 모든 오류를 ErrorResponse 형식으로 통일한다.
 * ResponseEntityExceptionHandler를 상속해, Spring MVC가 던지는 표준 예외(잘못된 JSON, 없는 경로, 허용되지 않는 메서드 등)도
 * handleExceptionInternal 한 곳에서 같은 형식으로 처리한다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> handleApiException(ApiException e) {
        ErrorCode code = e.getErrorCode();
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code.name(), e.getMessage()));
        // 같은 ErrorCode에 대해서 다른 예외 메시지를 전달하기 위해 code.getMessage()가 아닌 e.getMessage()를 사용한다.
        // ApiException은 서버가 정의한 예외로, 메시지 역시 직접 설정하므로, 서버의 정보가 의도치 않게 클라이언트로 노출될 우려가 없다.
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("처리되지 않은 예외", e);
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code.name(), code.getMessage()));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(ErrorCode.INVALID_REQUEST.name(), message));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ErrorCode code = ErrorCode.fromStatus(statusCode);
        return ResponseEntity.status(statusCode)
                .headers(headers)
                .body(ErrorResponse.of(code.name(), code.getMessage()));
    }

}
