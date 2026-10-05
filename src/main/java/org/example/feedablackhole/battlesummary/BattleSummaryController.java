package org.example.feedablackhole.battlesummary;

import jakarta.validation.Validator;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.example.feedablackhole.common.error.ApiException;
import org.example.feedablackhole.common.error.ErrorCode;
import org.example.feedablackhole.common.error.ErrorResponse.InvalidField;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.json.JsonMapper;

// 클라이언트가 보낸 전투 요약을 받는다. 처음 받은 판은 201, 이미 받은 판은 200으로 답한다.
// 본문을 문자열로 받는다. 원문을 그대로 보관하려면 Spring이 객체로 바꾸기 전의 글자가 필요하다.
// 잘못된 요청은 모두 400(ApiException)이어야 한다. 500이면 클라이언트 큐가 그 통계에서 멈춘다.
@RestController
@RequiredArgsConstructor
public class BattleSummaryController {

    private final JsonMapper jsonMapper;
    private final Validator validator;
    private final BattleSummaryService service;

    @PostMapping(path = "/api/v1/battle-summaries", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> receive(@RequestBody String body) {
        BattleSummaryRequest request = read(body);
        validate(request);

        ReceiveResult result = service.receive(request.toEntity(body, Instant.now()));
        HttpStatus status = result == ReceiveResult.CREATED ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).build();
    }

    // 값의 형식이 틀리면(숫자 자리에 글자, 시각이 아닌 문자열 등) INVALID_FIELD, JSON 객체가 아니면 INVALID_JSON.
    private BattleSummaryRequest read(String body) {
        BattleSummaryRequest request;
        try {
            request = jsonMapper.readValue(body, BattleSummaryRequest.class);
        } catch (MismatchedInputException e) {
            if (e.getPath().isEmpty()) {
                // 본문 전체가 객체가 아니다(배열, 문자열 등).
                throw new ApiException(ErrorCode.INVALID_JSON);
            }
            String field = e.getPath().getLast().getPropertyName();
            throw new ApiException(ErrorCode.INVALID_FIELD, List.of(new InvalidField(field, "형식이 맞지 않는다.")));
        } catch (JacksonException e) {
            throw new ApiException(ErrorCode.INVALID_JSON);
        }

        if (request == null) {
            // 본문이 null 하나뿐이다.
            throw new ApiException(ErrorCode.INVALID_JSON);
        }
        return request;
    }

    private void validate(BattleSummaryRequest request) {
        List<InvalidField> errors = validator.validate(request).stream()
                .map(violation -> new InvalidField(violation.getPropertyPath().toString(), violation.getMessage()))
                .sorted(Comparator.comparing(InvalidField::field))
                .toList();

        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_FIELD, errors);
        }
    }
}
