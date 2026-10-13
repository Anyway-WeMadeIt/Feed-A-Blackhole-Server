package org.example.feedablackhole.sync.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.example.feedablackhole.common.exception.ApiException;
import org.example.feedablackhole.common.exception.ErrorCode;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 동기화 요청 본문을 로그 목록으로 읽는다. 요청 전체를 거부하는 경우만 여기서 가린다(docs/server-sync-contracts.md 3.4, 3.5):
 * <ul>
 *   <li>413: 본문이 1 MB 초과, 로그가 500개 초과</li>
 *   <li>400: JSON이 아님, logs가 없거나 배열이 아님, 로그가 객체가 아님, seq를 읽을 수 없거나 1 미만, 요청 안에서 seq가 1씩 증가하지 않음</li>
 * </ul>
 * 그 밖의 로그 형식 오류는 그 로그의 MALFORMED_LOG다(LogParser).
 */
@Component
@RequiredArgsConstructor
public class SyncRequestReader {

    public static final int MAX_LOGS = 500;
    public static final int MAX_BODY_BYTES = 1024 * 1024;

    private final ObjectMapper objectMapper;

    public List<IncomingLog> read(String body) {
        if (body == null || body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            if (body == null) {
                throw invalid("본문이 없습니다.");
            }
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "본문이 " + MAX_BODY_BYTES + "바이트를 넘었습니다.");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JacksonException e) {
            throw invalid("JSON 형식이 아닙니다.");
        }

        JsonNode logs = root == null ? null : root.get("logs");
        if (logs == null || !logs.isArray()) {
            throw invalid("logs: 배열이 필요합니다.");
        }
        if (logs.size() > MAX_LOGS) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "logs: " + MAX_LOGS + "개를 넘었습니다.");
        }

        List<IncomingLog> result = new ArrayList<>(logs.size());
        Long previous = null;
        for (int i = 0; i < logs.size(); i++) {
            JsonNode log = logs.get(i);
            long seq = seqOf(log, i);
            if (previous != null && seq != previous + 1) {
                throw invalid("logs[" + i + "].seq: 앞 로그의 seq + 1이어야 합니다.");
            }
            result.add(new IncomingLog(seq, log));
            previous = seq;
        }
        return result;
    }

    private static long seqOf(JsonNode log, int index) {
        if (log == null || !log.isObject()) {
            throw invalid("logs[" + index + "]: 객체가 필요합니다.");
        }
        JsonNode seq = log.get("seq");
        if (seq == null || !seq.isIntegralNumber() || !seq.canConvertToLong() || seq.longValue() < 1) {
            throw invalid("logs[" + index + "].seq: 1 이상의 정수가 필요합니다.");
        }
        return seq.longValue();
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.INVALID_REQUEST, message);
    }

}
