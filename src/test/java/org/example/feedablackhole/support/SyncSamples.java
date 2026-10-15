package org.example.feedablackhole.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 계약 3.8의 샘플 요청(src/test/resources/sync-samples/sync-request.json)에서 로그를 꺼내 주는 테스트용 도우미.
 * 로그는 JSON 글자로 돌려주고, 같은 로그는 몇 번을 꺼내도 같은 글자다(재전송해도 같은 글자여야 한다, 계약 3.1).
 * 샘플의 로그: seq 1 RUN_START, 2 SETTLEMENT, 3 NODE_PURCHASE, 4 RUN_START, 5 SETTLEMENT, 6 RESET.
 *
 * <p>시나리오마다 샘플을 복사해 값을 손으로 고치지 않는다. 값을 일부러 바꾼 로그가 필요하면
 * {@link #withLogField}, {@link #withPayloadField}로 필드 하나만 바꿔 만든다.
 */
public final class SyncSamples {

    // 클래스패스 기준 샘플 요청의 위치(src/test/resources 아래)
    private static final String REQUEST = "/sync-samples/sync-request.json";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SyncSamples() {
    }

    /**
     * 샘플 요청의 모든 로그(seq 1~6).
     */
    public static String[] request() {
        return logs(1, Integer.MAX_VALUE);
    }

    /**
     * 샘플 요청에서 seq가 fromSeq ~ toSeq(양 끝 포함)인 로그를 seq 순서대로 돌려준다. 하나만 필요하면 logs(3, 3)이다.
     * 배열로 돌려주므로 FakeClient.sync(String...)에 그대로 넘기면 항상 {"logs":[...]}로 감싸진다.
     */
    public static String[] logs(int fromSeq, int toSeq) {
        List<String> selected = new ArrayList<>();
        for (JsonNode log : read().get("logs")) {
            int seq = log.get("seq").intValue();
            if (seq >= fromSeq && seq <= toSeq) {
                selected.add(log.toString());
            }
        }

        return selected.toArray(new String[0]);
    }

    /**
     * 로그의 바깥 필드(seq, contentVersion 등) 하나를 바꾼 로그. 거짓 contentVersion 같은 로그를 만들 때 쓴다.
     */
    public static String withLogField(String logJson, String field, Object value) {
        ObjectNode log = (ObjectNode) JSON.readTree(logJson);
        log.set(field, JSON.valueToTree(value));

        return log.toString();
    }

    /**
     * 로그의 payload 필드(startGold, cost 등) 하나를 바꾼 로그. 의심 표시용 RUN_START 같은 로그를 만들 때 쓴다.
     */
    public static String withPayloadField(String logJson, String field, Object value) {
        ObjectNode log = (ObjectNode) JSON.readTree(logJson);
        ((ObjectNode) log.get("payload")).set(field, JSON.valueToTree(value));

        return log.toString();
    }

    // 샘플 요청 파일을 읽어 JSON 트리로 만든다
    private static JsonNode read() {
        try (InputStream in = SyncSamples.class.getResourceAsStream(REQUEST)) {
            if (in == null) {
                throw new IllegalStateException("샘플이 없다: " + REQUEST);
            }
            return JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
