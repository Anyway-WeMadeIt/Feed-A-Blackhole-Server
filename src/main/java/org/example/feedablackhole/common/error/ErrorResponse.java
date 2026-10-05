package org.example.feedablackhole.common.error;

import java.util.List;

// 에러 응답 본문. Unity의 ErrorResponseDto({code, message, errors[{field, reason}]})와 같은 모양이다.
// 클라이언트는 이 본문을 로그에만 남긴다.
public record ErrorResponse(String code, String message, List<InvalidField> errors) {

    // 잘못된 필드 하나와 그 이유.
    public record InvalidField(String field, String reason) {
    }

    // errors가 없어도 null이 아니라 []로 나간다.
    public static ErrorResponse of(ErrorCode code, List<InvalidField> errors) {
        return new ErrorResponse(code.name(), code.getMessage(), List.copyOf(errors));
    }
}
