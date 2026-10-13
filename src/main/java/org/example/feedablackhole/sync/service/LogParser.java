package org.example.feedablackhole.sync.service;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.example.feedablackhole.sync.log.LogType;
import org.example.feedablackhole.sync.log.payload.NodePurchasePayload;
import org.example.feedablackhole.sync.log.payload.ResetPayload;
import org.example.feedablackhole.sync.log.payload.RunStartPayload;
import org.example.feedablackhole.sync.log.payload.SettlementPayload;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 로그 하나의 공통 필드와 payload를 읽고 형식을 검사한다(docs/server-sync-contracts.md 3.3).
 * 어긋나면 그 로그는 MALFORMED_LOG다. 통과한 payload는 null인 필드가 없다.
 */
@Component
@RequiredArgsConstructor
public class LogParser {

    static final Map<LogType, Class<?>> PAYLOAD_TYPES = Map.of(
            LogType.NODE_PURCHASE, NodePurchasePayload.class,
            LogType.RESET, ResetPayload.class,
            LogType.RUN_START, RunStartPayload.class,
            LogType.SETTLEMENT, SettlementPayload.class);

    private static final int OCCURRED_AT_MAX = 40;
    private static final int BUILD_VERSION_MAX = 32;
    private static final int CONTENT_VERSION_MAX = 64;

    private final ObjectMapper objectMapper;
    private final Validator validator;

    /** 로그 하나를 읽은 결과. error가 null이 아니면 형식 오류이고 type·payload는 null일 수 있다. */
    public record ParsedLog(
            long seq,
            String typeText,
            String contentVersion,
            String buildVersion,
            LogType type,
            Object payload,
            String error) {

        public boolean malformed() {
            return error != null;
        }

    }

    public ParsedLog parse(IncomingLog log) {
        JsonNode raw = log.raw();
        String typeText = text(raw, "type");
        String contentVersion = text(raw, "contentVersion");
        String buildVersion = text(raw, "buildVersion");
        String occurredAt = text(raw, "occurredAtUtc");

        String error = null;
        LogType type = typeOf(typeText);
        if (type == null) {
            error = "type: RUN_START, SETTLEMENT, NODE_PURCHASE, RESET 중 하나여야 합니다.";
        } else if (occurredAt == null || occurredAt.isEmpty() || occurredAt.length() > OCCURRED_AT_MAX) {
            error = "occurredAtUtc: 1~" + OCCURRED_AT_MAX + "자 문자열이 필요합니다.";
        } else if (buildVersion == null || buildVersion.length() > BUILD_VERSION_MAX) {
            error = "buildVersion: 0~" + BUILD_VERSION_MAX + "자 문자열이 필요합니다.";
        } else if (contentVersion == null || contentVersion.length() > CONTENT_VERSION_MAX) {
            error = "contentVersion: 0~" + CONTENT_VERSION_MAX + "자 문자열이 필요합니다.";
        }

        Object payload = null;
        if (error == null) {
            JsonNode payloadNode = raw.get("payload");
            if (payloadNode == null || !payloadNode.isObject()) {
                error = "payload: 객체가 필요합니다.";
            } else {
                try {
                    payload = objectMapper.readerFor(PAYLOAD_TYPES.get(type))
                            .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                            .readValue(payloadNode);
                    error = check(payload);
                } catch (JacksonException e) {
                    error = "payload: 필드 타입이 맞지 않습니다(" + e.getOriginalMessage() + ").";
                }
            }
        }

        return new ParsedLog(log.seq(), typeText, contentVersion, buildVersion,
                error == null ? type : null, error == null ? payload : null, error);
    }

    /**
     * Bean Validation(필수, 범위, 길이, UUID 형식)에 더해 중복 항목과 실수의 유한성을 본다.
     * 컨트롤러에서 @Valid 등으로 검증하지 않고 서비스 메서드 내에서 Validator를 사용하여 검증한다.
     * 로그는 리스트로 묶여서 오지만, 검사는 개별 로그 단위로 진행하기 때문
     * 검증하는 방식 자체는 동일하다. 검증 대상 클래스에 붙은 어노테이션을 기준으로 검사한다.
     * 만약 컨트롤러에서 검증한다면?
     *      컨트롤러 레벨에서의 검증은 항상 MethodArgumentNotValidException을 발생시켜 요청 전체에 대해 400 응답을 내린다.
     *      클라이언트는 400을 버그로 보고 로그를 지우지 않아 다음 실행해도 같은 요청이 같은 400을 받고, 영원히 동기화가 막힌다.
     */
    private String check(Object payload) {
        Set<ConstraintViolation<Object>> violations = validator.validate(payload);
        // Bean Validation 검증
        if (!violations.isEmpty()) {
            return violations.stream()
                    .map(violation -> "payload." + violation.getPropertyPath() + ": " + violation.getMessage())
                    .sorted()
                    .collect(Collectors.joining(", "));
        }
        // 중복과 유한성 검증
        if (payload instanceof RunStartPayload start) {
            if (!Float.isFinite(start.timeLimitSeconds())) {
                return "payload.timeLimitSeconds: 유한한 값이어야 합니다.";
            }
            if (hasDuplicates(start.startSupply().stream().map(RunStartPayload.SupplyItem::enemyId).toList())) {
                return "payload.startSupply: enemyId가 중복되었습니다.";
            }
        }
        if (payload instanceof SettlementPayload settlement) {
            if (!Float.isFinite(settlement.playedSeconds())) {
                return "payload.playedSeconds: 유한한 값이어야 합니다.";
            }
            List<String> keys = settlement.kills().stream()
                    .map(kill -> kill.enemyId() + "/" + kill.tier() + "/" + kill.size() + "/" + kill.golden())
                    .toList();
            if (hasDuplicates(keys)) {
                return "payload.kills: (enemyId, tier, size, golden)이 중복되었습니다.";
            }
        }
        return null;
    }

    private static boolean hasDuplicates(List<String> values) {
        return new HashSet<>(values).size() != values.size();
    }

    private static LogType typeOf(String text) {
        if (text == null) {
            return null;
        }
        for (LogType type : LogType.values()) {
            if (type.name().equals(text)) {
                return type;
            }
        }
        return null;
    }

    // 글자 필드. 없거나 글자가 아니면 null.
    private static String text(JsonNode raw, String field) {
        JsonNode node = raw.get(field);
        return node != null && node.isString() ? node.stringValue() : null;
    }

}
