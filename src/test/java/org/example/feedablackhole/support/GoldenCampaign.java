package org.example.feedablackhole.support;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.zip.GZIPInputStream;

/**
 * 클라이언트가 실제로 판을 돌려 만든 골든 캠페인 로그(계약 3.8a, fixtures/golden-campaign.json 또는 .json.gz).
 * 로그, 로그마다의 체크포인트(적용 뒤 Gold와 성장도), 최종 진행도, 캠페인 범위를 읽고, 조작할 로그를 역할(초반, 이정표, 중반, 후반 등)로 고른다.
 *
 * <p>이 클래스는 콘텐츠 값(노드 id, 종류 id, 숫자, seq, 성장도 수)을 쓰지 않는다. 위치는 파일의 내용에서 정하고, 값은 서버 콘텐츠로 계산하는 것이
 * 이 테스트의 원칙이다(계약 C39). 그래서 밸런스가 바뀌어도 픽스처만 다시 내보내면 코드를 고치지 않아도 된다.
 *
 * <p>인덱스는 0부터 센 로그 위치다. 로그의 seq는 인덱스 + 1이다(파일이 1부터 1씩 늘도록 보장한다).
 */
public final class GoldenCampaign {

    // 클래스패스 기준 위치. 크기가 커지면 .gz로 내보낸다(계약 C42)
    private static final String JSON_PATH = "/fixtures/golden-campaign.json";
    private static final String GZIP_PATH = JSON_PATH + ".gz";

    // 로그의 float(timeLimitSeconds 등)가 글자 그대로 남도록 BigDecimal로 읽는다
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private final JsonNode root;
    private final List<ObjectNode> logs = new ArrayList<>();

    private GoldenCampaign(JsonNode root) {
        this.root = root;
        for (JsonNode log : root.get("logs")) {
            logs.add((ObjectNode) log);
        }
    }

    /** 로그 한 번 적용한 뒤의 진행도(체크포인트). */
    public record Checkpoint(long seq, long gold, int growthStage) {
    }

    /** 어느 시점의 진행도: Gold, 성장도, 산 노드 Rank(처음 산 순서). */
    public record State(long gold, int growthStage, Map<String, Integer> ranks) {
    }

    /**
     * 픽스처를 읽는다. 없으면 클라이언트에서 내보내라는 메시지로 실패한다.
     */
    public static GoldenCampaign load() {
        byte[] bytes = read(JSON_PATH, false);
        if (bytes == null) {
            bytes = read(GZIP_PATH, true);
        }
        if (bytes == null) {
            throw new IllegalStateException("골든 캠페인 로그가 없다(%s 또는 %s). 클라이언트에서 BlackHole > Sync Fixtures > Export Campaign Log...를 실행해 "
                    .formatted(JSON_PATH, GZIP_PATH) + "서버 저장소에 커밋한다.");
        }
        return new GoldenCampaign(JSON.readTree(new String(bytes, StandardCharsets.UTF_8)));
    }

    // ---------- 파일의 메타 ----------

    public String contentVersion() {
        return root.get("contentVersion").asString();
    }

    public int battles() {
        return root.get("campaign").get("battles").asInt();
    }

    public int maxGrowthStage() {
        return root.get("campaign").get("maxGrowthStage").asInt();
    }

    public int reachedGrowthStage() {
        return root.get("campaign").get("reachedGrowthStage").asInt();
    }

    /** 마지막 이정표에 닿았는가(닿았다면 클리어 뒤 판이 로그에 들어 있다). */
    public boolean cleared() {
        return root.get("campaign").get("cleared").asBoolean();
    }

    public int postClearBattles() {
        return root.get("campaign").get("postClearBattles").asInt();
    }

    public String stopReason() {
        return root.get("campaign").get("stopReason").asString();
    }

    public int finalGrowthStage() {
        return root.get("final").get("growthStage").asInt();
    }

    public long finalGold() {
        return root.get("final").get("gold").longValue();
    }

    /** 최종 산 노드(처음 산 순서). */
    public Map<String, Integer> finalRanks() {
        Map<String, Integer> ranks = new LinkedHashMap<>();
        for (JsonNode node : root.get("final").get("nodes")) {
            ranks.put(node.get("nodeId").asString(), node.get("rank").asInt());
        }
        return ranks;
    }

    // ---------- 로그 ----------

    public int size() {
        return logs.size();
    }

    /** 로그 하나의 복사본. 고쳐 써도 원본은 그대로다. */
    public ObjectNode log(int index) {
        return logs.get(index).deepCopy();
    }

    public String type(int index) {
        return logs.get(index).get("type").asString();
    }

    /** 로그 글자(재직렬화). */
    public String json(int index) {
        return logs.get(index).toString();
    }

    /** index 이상 end 미만의 로그 글자들. */
    public String[] jsons(int from, int end) {
        String[] result = new String[end - from];
        for (int i = from; i < end; i++) {
            result[i - from] = json(i);
        }
        return result;
    }

    public JsonNode payload(int index) {
        return logs.get(index).get("payload");
    }

    /** 이 로그를 적용한 뒤의 진행도(체크포인트). 클라이언트 ProgressState에서 읽은 값이다. */
    public Checkpoint checkpoint(int index) {
        JsonNode row = root.get("checkpoints").get(index);
        return new Checkpoint(row.get(0).longValue(), row.get(1).longValue(), row.get(2).asInt());
    }

    /**
     * 로그를 index까지 적용한 뒤의 진행도. index가 -1이면 처음(Gold 0, 성장도 0, 노드 없음).
     * Gold와 성장도는 체크포인트에서, 산 노드는 구매 로그(RESET이면 비움)에서 읽는다.
     */
    public State stateAfter(int index) {
        Map<String, Integer> ranks = new LinkedHashMap<>();
        for (int i = 0; i <= index; i++) {
            String type = type(i);
            if ("RESET".equals(type)) {
                ranks.clear();
            } else if ("NODE_PURCHASE".equals(type)) {
                ranks.put(payload(i).get("nodeId").asString(), payload(i).get("rank").asInt());
            }
        }
        if (index < 0) {
            return new State(0, 0, ranks);
        }
        Checkpoint checkpoint = checkpoint(index);
        return new State(checkpoint.gold(), checkpoint.growthStage(), ranks);
    }

    // ---------- 역할로 위치 고르기(계약 3.8a의 선택기) ----------

    /** 모든 SETTLEMENT의 인덱스(순서대로). */
    public List<Integer> settlements() {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < logs.size(); i++) {
            if ("SETTLEMENT".equals(type(i))) {
                result.add(i);
            }
        }
        return result;
    }

    /** 클리어 뒤 판을 뺀 SETTLEMENT의 인덱스(클리어하지 못한 파일은 settlements()와 같다). */
    public List<Integer> battleSettlements() {
        List<Integer> all = settlements();
        return all.subList(0, all.size() - postClearBattles());
    }

    /** 초반: 처음 나오는 SETTLEMENT. */
    public OptionalInt firstSettlement() {
        List<Integer> list = battleSettlements();
        return list.isEmpty() ? OptionalInt.empty() : OptionalInt.of(list.get(0));
    }

    /** 이정표: reachedMilestone이 true인 첫 SETTLEMENT. */
    public OptionalInt milestoneSettlement() {
        for (int index : settlements()) {
            if (payload(index).get("reachedMilestone").asBoolean()) {
                return OptionalInt.of(index);
            }
        }
        return OptionalInt.empty();
    }

    /** 중반: 클리어 뒤 판을 뺀 SETTLEMENT 목록의 가운데 번째(floor(n / 2)). */
    public OptionalInt middleSettlement() {
        List<Integer> list = battleSettlements();
        return list.isEmpty() ? OptionalInt.empty() : OptionalInt.of(list.get(list.size() / 2));
    }

    /** 후반: 클리어 뒤 판을 뺀 마지막 SETTLEMENT. */
    public OptionalInt lastSettlement() {
        List<Integer> list = battleSettlements();
        return list.isEmpty() ? OptionalInt.empty() : OptionalInt.of(list.get(list.size() - 1));
    }

    /** 황금: kills에 golden:true가 있는 SETTLEMENT 중 중반에 가장 가까운 것. */
    public OptionalInt goldenSettlement() {
        OptionalInt middle = middleSettlement();
        if (middle.isEmpty()) {
            return OptionalInt.empty();
        }
        int best = -1;
        for (int index : battleSettlements()) {
            if (hasGoldenKill(index) && (best < 0 || Math.abs(index - middle.getAsInt()) < Math.abs(best - middle.getAsInt()))) {
                best = index;
            }
        }
        return best < 0 ? OptionalInt.empty() : OptionalInt.of(best);
    }

    /** 비용이 1 이상인 NODE_PURCHASE의 인덱스(순서대로). 구매 조작은 이 중에서 조건을 만족하는 것을 고른다. */
    public List<Integer> costlyPurchases() {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < logs.size(); i++) {
            if (isCostlyPurchase(i)) {
                result.add(i);
            }
        }
        return result;
    }

    /** 마지막 단계(클리어 파일만): 클리어 뒤 판의 첫 SETTLEMENT. */
    public OptionalInt postClearSettlement() {
        if (!cleared() || postClearBattles() == 0) {
            return OptionalInt.empty();
        }
        List<Integer> all = settlements();
        return OptionalInt.of(all.get(all.size() - postClearBattles()));
    }

    /** SETTLEMENT 바로 앞의 RUN_START 인덱스. 생성기가 항상 그렇게 이어 쓴다. */
    public int runStartBefore(int settlementIndex) {
        int index = settlementIndex - 1;
        if (!"RUN_START".equals(type(index))) {
            throw new IllegalStateException("결산 바로 앞이 RUN_START가 아니다: 인덱스 " + settlementIndex);
        }
        return index;
    }

    // ---------- 로그 고치기 ----------

    /** 로그의 바깥 필드(contentVersion 등) 하나를 바꾼 복사본. */
    public static ObjectNode withLogField(ObjectNode log, String field, Object value) {
        ObjectNode copy = log.deepCopy();
        copy.set(field, JSON.valueToTree(value));
        return copy;
    }

    /** payload 필드(exp, cost 등) 하나를 바꾼 복사본. */
    public static ObjectNode withPayloadField(ObjectNode log, String field, Object value) {
        ObjectNode copy = log.deepCopy();
        ((ObjectNode) copy.get("payload")).set(field, JSON.valueToTree(value));
        return copy;
    }

    /** payload 필드 여러 개를 한꺼번에 바꾼 복사본(노드 구매를 다른 노드로 바꿀 때 쓴다). */
    public static ObjectNode withPayloadFields(ObjectNode log, Map<String, Object> fields) {
        ObjectNode copy = log.deepCopy();
        ObjectNode payload = (ObjectNode) copy.get("payload");
        fields.forEach((field, value) -> payload.set(field, JSON.valueToTree(value)));
        return copy;
    }

    /** seq를 바꾼 복사본. */
    public static ObjectNode withSeq(ObjectNode log, long seq) {
        return withLogField(log, "seq", seq);
    }

    /** 객체 하나를 JSON 노드로 만든다(kills에 항목을 더할 때 쓴다). */
    public static JsonNode node(Object value) {
        return JSON.valueToTree(value);
    }

    // ---------- 내부 ----------

    private boolean hasGoldenKill(int settlementIndex) {
        for (JsonNode kill : payload(settlementIndex).get("kills")) {
            if (kill.get("golden").asBoolean()) {
                return true;
            }
        }
        return false;
    }

    private boolean isCostlyPurchase(int index) {
        return "NODE_PURCHASE".equals(type(index)) && payload(index).get("cost").longValue() >= 1;
    }

    // 클래스패스 파일의 바이트. 없으면 null
    private static byte[] read(String path, boolean gzip) {
        try (InputStream in = GoldenCampaign.class.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            if (gzip) {
                try (GZIPInputStream unzipped = new GZIPInputStream(in)) {
                    return unzipped.readAllBytes();
                }
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
