package org.example.feedablackhole.common.error;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// 컨트롤러에서 나온 예외를 ErrorResponse로 바꾼다.
// 여기서 다루지 않는 예외(404·405·415 등)는 Spring 기본 응답으로 나간다. 클라이언트는 상태 코드만 보므로 충분하다.
// 모든 예외(Exception)를 잡지 않는다. 그러면 415·404 같은 설정 문제까지 500으로 바뀌어 클라이언트가 잘못 판단한다.
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException e) {
        return respond(e.getErrorCode(), e.getErrors());
    }

    // 본문이 비어 Spring이 읽지 못했다.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable() {
        return respond(ErrorCode.INVALID_JSON, List.of());
    }

    private static ResponseEntity<ErrorResponse> respond(ErrorCode code, List<ErrorResponse.InvalidField> errors) {
        log.warn("요청을 받지 않았다: {} {}", code, errors);
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code, errors));
    }
}
