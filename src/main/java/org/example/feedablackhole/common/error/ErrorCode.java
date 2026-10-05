package org.example.feedablackhole.common.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// 응답 본문의 code. 상태 코드와 기본 메시지를 함께 정한다.
// 클라이언트는 상태 코드만으로 처리를 정한다: 400은 "이 통계 하나의 문제"라 rejected/로 옮긴다.
@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    INVALID_JSON(HttpStatus.BAD_REQUEST, "본문을 JSON 객체로 읽을 수 없다."),
    INVALID_FIELD(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않다.");

    private final HttpStatus status;
    private final String message;
}
