package org.example.feedablackhole.node.content;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 콘텐츠의 버전. 파일의 글자가 아니라 **불러온 의미**(수치, 노드, 비용, 효과, 배치, 선)로 계산한다.
 * 의미가 같으면 버전도 같다: Memo나 그 오른쪽에 더한 분석용 칸, 머리칸 순서, 줄바꿈 종류, BOM, 같은 값의 다른 표기("12"와 "12.0"),
 * 선을 어느 쪽 노드에 적었는지는 버전을 바꾸지 않는다. 비용, 효과, 수치 정의, 노드의 칸이나 시작 여부, 선, 순서가 바뀌면 달라진다.
 * 클라이언트가 이 버전으로 변하지 않는 부분을 캐시하므로, 의미가 그대로인 편집으로 캐시가 무효화되지 않게 하려는 것이다.
 */
public final class NodeContentVersion {

    private static final int HEX_LENGTH = 16;
    // 항목 안의 칸과 항목 사이를 나누는 제어 문자(노드 ID 등에 나올 수 없는 글자라 섞여 읽히지 않는다).
    private static final char FIELD = '\u001f';
    private static final char RECORD = '\u001e';

    private NodeContentVersion() {
    }

    public static String of(NodeContent content, List<NodePlacement> placements, NodeGraph graph) {
        StringBuilder canonical = new StringBuilder();

        for (UpgradeStatDefinition stat : content.stats()) {
            record(canonical, "stat", stat.statId(), stat.valueType().name(), stat.unit().name(),
                    number(stat.defaultValue()), number(stat.min()), number(stat.max()));
        }

        for (NodeDefinition node : content.nodes()) {
            record(canonical, "node", node.id(), String.valueOf(node.maxRank()));
            for (NodeRankDefinition rank : node.ranks()) {
                record(canonical, "rank", node.id(), String.valueOf(rank.rank()), String.valueOf(rank.cost()));
                for (NodeEffect effect : rank.effects()) {
                    record(canonical, "effect", node.id(), String.valueOf(rank.rank()), effect.statId(), number(effect.value()));
                }
            }
        }

        for (NodePlacement placement : placements) {
            record(canonical, "place", placement.id(), String.valueOf(placement.start()),
                    String.valueOf(placement.x()), String.valueOf(placement.y()));
        }

        // 선은 방향이 없으므로, 어느 쪽에 적었는지가 아니라 이어진 쌍(배치 순서가 앞선 노드가 먼저)으로 센다.
        for (NodeLink link : graph.links()) {
            record(canonical, "link", link.a(), link.b());
        }

        return hash(canonical.toString());
    }

    private static void record(StringBuilder into, String kind, String... fields) {
        into.append(kind);
        for (String field : fields) {
            into.append(FIELD).append(field);
        }
        into.append(RECORD);
    }

    // 같은 값은 같은 글자로: 12, 12.0, 12.00은 모두 "12"다. 없는 값(제한 없음)은 빈 글자다.
    private static String number(BigDecimal value) {
        if (value == null) {
            return "";
        }
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, HEX_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

}
