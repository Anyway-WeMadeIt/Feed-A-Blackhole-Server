package org.example.feedablackhole.common.dto;

/**
 * 모든 오류 응답의 형식: { "error": { "code": "...", "message": "..." } }
 */
public record ErrorResponse(Error error) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(new Error(code, message));
    }

    public record Error(String code, String message) {
    }

}
