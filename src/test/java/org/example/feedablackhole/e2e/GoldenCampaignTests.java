package org.example.feedablackhole.e2e;

import org.assertj.core.api.SoftAssertions;
import org.example.feedablackhole.TestcontainersConfiguration;
import org.example.feedablackhole.battle.content.BattleContent;
import org.example.feedablackhole.battle.content.EnemyKindDefinition;
import org.example.feedablackhole.battle.rules.ContentBattleRules;
import org.example.feedablackhole.battle.rules.KindSetup;
import org.example.feedablackhole.battle.rules.RunSetup;
import org.example.feedablackhole.content.ContentManifest;
import org.example.feedablackhole.node.content.NodeDefinition;
import org.example.feedablackhole.node.content.NodeTree;
import org.example.feedablackhole.support.FakeClient;
import org.example.feedablackhole.support.GoldenCampaign;
import org.example.feedablackhole.support.GoldenCampaign.State;
import org.example.feedablackhole.sync.entity.BattleRun;
import org.example.feedablackhole.sync.entity.SyncLogRecord;
import org.example.feedablackhole.sync.entity.SyncLogResult;
import org.example.feedablackhole.sync.repository.BattleRunRepository;
import org.example.feedablackhole.sync.repository.SyncLogRecordRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 골든 캠페인 로그(계약 3.8a, W4-2b): 클라이언트가 실제로 판을 돌려 만든 로그 전체를 실제 서버(HTTP, DB, 실제 콘텐츠 묶음)에 넣는다.
 * 골든 로그는 모두 통과하고 서버가 계산한 진행도가 로그마다의 체크포인트와 최종 진행도에서 클라이언트와 같아야 하고,
 * 조작한 로그는 정해진 seq와 코드에서 멈춰야 한다.
 *
 * <p>이 테스트는 콘텐츠 값(노드 id, 종류 id, 숫자, seq, 성장도 수)을 코드에 쓰지 않는다(계약 C39). 조작할 위치는 파일의 내용에서 역할(초반, 이정표,
 * 중반, 후반 등)로 고르고, 조작 값은 서버 콘텐츠(ContentBattleRules, NodeTree)로 계산한다. 조건을 만족하는 로그나 노드가 없어 적용할 수 없는 조작은
 * 건너뛰지 않고 실패한다. 밸런스가 바뀌면 클라이언트에서 픽스처를 다시 내보내기만 하면 이 테스트가 코드 수정 없이 통과해야 한다.
 *
 * <p>샘플 묶음이 아니라 실제 묶음(src/main/resources/content) 위에서 돈다(계약 C30, C32). 그래서 스프링 컨텍스트가 다른 E2E와 따로 뜬다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "blackhole.content.location=classpath:content/")
@Import(TestcontainersConfiguration.class)
class GoldenCampaignTests {

    // 요청 하나에 담는 로그 수: 오프라인과 온라인이 자주 바뀌어 조금씩 끊어 보내는 상황. 어긋난 지점도 좁게 잡힌다
    private static final int FREQUENT_CHUNK = 20;

    // 접두 구간을 보낼 때 쓰는 로그 수(조작마다 접두 구간을 다시 보내므로 요청 수를 줄인다. 이 값은 검증 대상이 아니다)
    private static final int REPLAY_CHUNK = 100;

    // 서버 한도(계약 3.5): 요청당 로그 500개, 본문 1MB. 클라이언트도 이 한도까지 채워 보낸다
    private static final int MAX_LOGS_PER_REQUEST = 500;
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    // 요청 본문의 뼈대 {"logs":[ ]} 의 바이트 수
    private static final int REQUEST_ENVELOPE_BYTES = 11;

    // 로그의 contentVersion을 다르게 바꿀 때 쓰는 값
    private static final String OTHER_CONTENT_VERSION = "0000000000000000";

    // 다른 값으로 바꾸는 runId(형식은 올바른 소문자 UUID여야 형식 검사를 통과한다)
    private static final String OTHER_RUN_ID = "00000000-0000-4000-8000-0000deadbeef";

    // 서버 콘텐츠에 없는 이름
    private static final String NO_SUCH_ENEMY = "no-such-enemy";
    private static final String NO_SUCH_NODE = "no-such-node";

    // S8 상한을 넘기려고 더하는 초
    private static final double OVER_TIME = 1.0;

    // 기본 조작 번호(계약 3.8a의 조작표). 클리어한 파일은 22, 23이 더해진다. 10, 20, 24는 거부하지 않는 조작이라 따로 확인한다
    private static final Set<Integer> BASE_REJECTION_CASES = Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 12, 13, 14, 15, 16, 17, 18, 19, 21);
    private static final Set<Integer> CLEARED_REJECTION_CASES = Set.of(22, 23);

    private static GoldenCampaign campaign;

    @LocalServerPort
    private int port;

    @Autowired
    private ContentBattleRules rules;

    @Autowired
    private NodeTree nodeTree;

    @Autowired
    private BattleContent battleContent;

    @Autowired
    private ContentManifest manifest;

    @Autowired
    private SyncLogRecordRepository logRepository;

    @Autowired
    private BattleRunRepository runRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void loadCampaign() {
        campaign = GoldenCampaign.load();
    }

    // 버전이 다르면 값을 비교해도 의미가 없다. 가장 먼저, 가장 분명하게 알린다
    @BeforeEach
    void fixtureMatchesTheServerContent() {
        assertThat(campaign.contentVersion())
                .as("골든 캠페인 로그의 콘텐츠 버전(%s)이 서버 콘텐츠 버전(%s)과 다르다. 픽스처 재생성 필요: 클라이언트에서 "
                        + "BlackHole > Sync Fixtures > Export All Server Fixtures...를 다시 실행해 서버 저장소에 커밋한다.",
                        campaign.contentVersion(), manifest.contentVersion())
                .isEqualTo(manifest.contentVersion());
    }

    // ---------- 골든 통과 ----------

    /**
     * 골든 로그를 20개씩 끊어서 자주 보내도 모두 반영된다(오프라인과 온라인이 자주 바뀌어 조금씩 보내는 경우). 응답마다 서버 진행도가 그 시점의
     * 체크포인트(Gold, 성장도)와 같고, 끝에서는 클라이언트의 최종 진행도와 완전히 같다. 의심 표시는 하나도 없어야 한다.
     * 어긋나면 로그를 하나씩 보내 처음 어긋나는 seq를 알린다.
     */
    @Test
    void goldenCampaignSentInSmallFrequentRequestsMatchesEveryCheckpoint() {
        assertCampaignAppliedInRequests(fixedRanges(FREQUENT_CHUNK));
    }

    /**
     * 골든 로그를 요청 한도(로그 500개, 본문 1MB)까지 채워서 보내도 모두 반영된다(오프라인으로 쌓인 로그를 한꺼번에 보내는 경우). 클라이언트가
     * 나누는 규칙(앞에서부터 한도를 넘지 않을 때까지 채운다)과 같고, 한도에 가까운 큰 요청을 서버가 한 번에 처리하는 경로를 실제 HTTP와 DB로 확인한다.
     * 한도만큼 채운 요청이 하나는 있어야 이 테스트가 의미가 있다.
     */
    @Test
    void goldenCampaignSentInLimitSizedRequestsMatchesEveryCheckpoint() {
        List<int[]> ranges = limitSizedRanges();

        int largest = ranges.stream().mapToInt(range -> range[1] - range[0]).max().orElse(0);
        assertThat(largest)
                .as("로그 %d개를 한도(%d개)만큼 채운 요청이 있어야 한다. 로그가 커져 본문 1MB에 먼저 닿는다면 이 테스트의 의도를 다시 정한다",
                        campaign.size(), MAX_LOGS_PER_REQUEST)
                .isEqualTo(Math.min(MAX_LOGS_PER_REQUEST, campaign.size()));

        assertCampaignAppliedInRequests(ranges);
    }

    // ranges(로그 인덱스 [시작, 끝) 목록)대로 나눠 새 계정에 보내며, 요청마다 거부가 없고 체크포인트와 같은지, 끝에서 최종 진행도와 같은지 확인한다
    private void assertCampaignAppliedInRequests(List<int[]> ranges) {
        FakeClient client = startedClient();

        for (int[] range : ranges) {
            int start = range[0];
            int end = range[1];
            FakeClient.Response response = client.sync(campaign.jsons(start, end));

            try {
                assertThat(response.status()).as("요청 본문: %s", response.body()).isEqualTo(200);
                assertThat(response.<Object>read("$.rejected")).as("거부된 로그 (seq %d~%d를 보냈다)", start + 1, end).isNull();
                assertThat(response.number("$.appliedThroughSeq")).isEqualTo(end);
                assertThat(serverState(response, "$.progress")).isEqualTo(campaign.stateAfter(end - 1));
            } catch (AssertionError error) {
                throw new AssertionError(firstDivergence(end) + "\n" + error.getMessage(), error);
            }
        }

        // 끝: 서버 진행도가 클라이언트의 최종 진행도와 완전히 같다(노드는 처음 산 순서까지)
        State server = serverState(client.loadProgress().expect(200), "$");
        assertThat(server.gold()).isEqualTo(campaign.finalGold());
        assertThat(server.growthStage()).isEqualTo(campaign.finalGrowthStage());
        assertThat(server.ranks()).containsExactlyEntriesOf(campaign.finalRanks());

        // 정상 로그라서 의심 표시가 없고, 모든 로그가 반영(APPLIED)으로 기록된다
        assertThat(suspiciousCount(client)).isZero();
        assertThat(results(client)).hasSize(campaign.size()).containsOnly(SyncLogResult.APPLIED);

        // 마지막 요청을 그대로 다시 보내도(재전송) 아무것도 바뀌지 않는다
        int[] last = ranges.get(ranges.size() - 1);
        FakeClient.Response resent = client.sync(campaign.jsons(last[0], last[1])).expect(200);
        assertThat(resent.<Object>read("$.rejected")).isNull();
        assertThat(serverState(resent, "$.progress")).isEqualTo(campaign.stateAfter(campaign.size() - 1));
    }

    // 로그를 size개씩 끊은 구간 목록
    private static List<int[]> fixedRanges(int size) {
        List<int[]> ranges = new ArrayList<>();
        for (int start = 0; start < campaign.size(); start += size) {
            ranges.add(new int[] {start, Math.min(start + size, campaign.size())});
        }
        return ranges;
    }

    // 요청 한도(로그 500개, 본문 1MB)를 넘지 않을 때까지 앞에서부터 채운 구간 목록. 클라이언트(SyncSender)가 나누는 규칙과 같다
    private static List<int[]> limitSizedRanges() {
        List<int[]> ranges = new ArrayList<>();
        int start = 0;
        while (start < campaign.size()) {
            int end = start;
            long bytes = REQUEST_ENVELOPE_BYTES;
            while (end < campaign.size() && end - start < MAX_LOGS_PER_REQUEST) {
                long next = bytes + campaign.json(end).getBytes(StandardCharsets.UTF_8).length + (end > start ? 1 : 0);
                if (next > MAX_BODY_BYTES) {
                    break;
                }
                bytes = next;
                end++;
            }
            if (end == start) {
                throw new IllegalStateException("로그 하나(seq " + (start + 1) + ")가 본문 한도(1MB)보다 크다");
            }
            ranges.add(new int[] {start, end});
            start = end;
        }
        return ranges;
    }

    /**
     * 이정표를 하나 이상 지난 캠페인이어야 한다(이정표를 하나도 지나지 않은 빈 캠페인이 통과하지 못하게). 덮은 범위는 출력으로 남긴다.
     * 마지막 이정표에 닿지 못한 콘텐츠는 그 이후 분기를 골든이 덮지 않는다(W1-6 단위 테스트가 맡는다).
     * 실제 서버 검증 동작을 확인하는 테스트가 아니라, 서버 동작을 검토할 수 있는 최소한의 데이터가 준비되어 있는지를 확인하는 테스트이다.
     */
    @Test
    void campaignPassesAtLeastOneMilestone() {
        System.out.printf("[골든] 판 %d개, 로그 %d개, 성장도 %d/%d%s, 끝난 이유 %s%n",
                campaign.battles(), campaign.size(), campaign.reachedGrowthStage(), campaign.maxGrowthStage(),
                campaign.cleared() ? "(클리어, 마지막 이정표 이후 분기 포함)" : "(마지막 이정표 이후 분기는 W1-6 단위 테스트가 맡는다)",
                campaign.stopReason());

        assertThat(campaign.reachedGrowthStage()).as("골든 캠페인이 지난 이정표 수").isGreaterThanOrEqualTo(1);
        assertThat(campaign.milestoneSettlement()).as("이정표에 닿은 결산").isPresent();
    }

    // ---------- 거부하는 조작 ----------

    /**
     * 조작한 로그는 정해진 seq와 코드에서 멈춘다(계약 3.8a의 조작표). 조작 하나마다 같은 확인을 한다:
     * 거부된 seq와 코드, 반영된 마지막 번호, 같은 요청에서 거부된 로그 뒤의 로그가 반영되지 않았는지, 진행도가 거부 직전 체크포인트와 같은지.
     * 거부는 상태를 바꾸지 않으므로 같은 위치의 조작은 접두 구간을 한 번만 보내고 연달아 시도한다.
     * 적용할 수 없는 조작은 건너뛰지 않고 실패하며, 표의 모든 조작이 실행되었는지도 확인한다.
     */
    @Test
    void tamperedLogsAreRejectedAtTheExpectedSeqAndCode() {
        Plan plan = rejectionPlan();
        Set<Integer> executed = new TreeSet<>();
        List<String> failures = new ArrayList<>(plan.notApplicable());

        plan.byPosition().forEach((position, attempts) -> {
            FakeClient client = clientWithPrefix(position);

            // 상태를 바꾸는 조작(결산을 한 번 더 보내기)은 위치마다 하나이고 맨 뒤에서 한다
            attempts.sort((a, b) -> Boolean.compare(a.mutating(), b.mutating()));
            if (attempts.stream().filter(Attempt::mutating).count() > 1) {
                throw new IllegalStateException("같은 위치(seq " + (position + 1) + ")에 상태를 바꾸는 조작이 둘 이상이다. 위치 선택이 겹쳤다.");
            }
            for (Attempt attempt : attempts) {
                executed.add(attempt.number());
                try {
                    SoftAssertions.assertSoftly(soft -> run(soft, client, attempt));
                } catch (AssertionError error) {
                    failures.add(error.getMessage());
                }
            }
        });

        Set<Integer> expected = new TreeSet<>(BASE_REJECTION_CASES);
        if (campaign.cleared()) {
            expected.addAll(CLEARED_REJECTION_CASES);
        }
        Set<Integer> missing = new TreeSet<>(expected);
        missing.removeAll(executed);
        if (!missing.isEmpty()) {
            failures.add("실행되지 않은 조작 번호: " + missing);
        }

        assertThat(failures).as("조작표(계약 3.8a) 중 실패한 것").isEmpty();
    }

    // ---------- 거부하지 않는 조작 ----------

    /**
     * 조작 10: 판의 RUN_START와 SETTLEMENT 사이에 유효한 구매가 끼면 구매는 통과하고(열린 판을 포기로 닫는다) 이어지는 결산이 RUN_NOT_OPEN이다(C31).
     * 골든 로그의 구매 정책은 살 수 있는 노드를 모두 사므로 판 시작 때는 살 수 있는 노드가 없다. 그래서 새 구매를 만들지 않고, 판 시작 바로 앞의 구매 하나를
     * 판 시작 뒤로 옮긴다(RUN_START, 구매, SETTLEMENT 순서). 구매는 그대로 유효하다(판 시작은 진행도를 바꾸지 않는다).
     * 옮긴 구매 때문에 판 시작 로그의 startGold가 서버 값과 달라 의심 표시가 남지만 거부 사유는 아니다.
     */
    @Test
    void aPurchaseBetweenRunStartAndSettlementClosesTheRun() {
        // 중반 결산에 가장 가까운 판 중 판 시작 바로 앞에 구매가 있는 판(캠페인 후반에는 살 노드가 남지 않아 구매가 없을 수 있다)
        int middle = middleSettlementIndex();
        int runStart = -1;
        for (int settlement : campaign.battleSettlements()) {
            int candidate = campaign.runStartBefore(settlement);
            boolean purchaseBefore = candidate > 0 && "NODE_PURCHASE".equals(campaign.type(candidate - 1));
            if (purchaseBefore && (runStart < 0 || Math.abs(settlement - middle) < Math.abs(runStart + 1 - middle))) {
                runStart = candidate;
            }
        }
        assertThat(runStart).as("판 시작 바로 앞에 구매가 있는 판이 없다").isPositive();

        // 접두 구간은 그 구매의 앞까지다. 구매(인덱스 runStart - 1)와 판 시작(runStart)의 순서를 바꿔 보낸다
        int purchase = runStart - 1;
        int settlement = runStart + 1;
        long seq = purchase + 1L;
        ObjectNode movedRunStart = GoldenCampaign.withSeq(campaign.log(runStart), seq);
        ObjectNode movedPurchase = GoldenCampaign.withSeq(campaign.log(purchase), seq + 1);
        ObjectNode settlementLog = GoldenCampaign.withSeq(campaign.log(settlement), seq + 2);

        FakeClient client = clientWithPrefix(purchase);
        FakeClient.Response response = client.sync(movedRunStart.toString(), movedPurchase.toString(), settlementLog.toString()).expect(200);

        // 판 시작과 구매는 반영되고 그 뒤의 결산이 거부된다
        assertThat(response.number("$.appliedThroughSeq")).isEqualTo(seq + 1);
        assertThat(response.number("$.rejected.seq")).isEqualTo(seq + 2);
        assertThat(response.text("$.rejected.type")).isEqualTo("SETTLEMENT");
        assertThat(response.text("$.rejected.code")).isEqualTo("RUN_NOT_OPEN");

        // 구매의 효과는 원래 구매(체크포인트)와 같다
        assertThat(serverState(response, "$.progress")).isEqualTo(campaign.stateAfter(purchase));
    }

    /**
     * 조작 20: RUN_START의 값이 서버 계산과 다르면(시작 레벨, 제한 시간, 시작 공급) 거부하지 않고 의심 표시를 남긴 채 계속 진행한다.
     * 이어지는 정상 로그를 끝까지 보내면 최종 진행도가 클라이언트와 같고, 의심 표시는 그 판 하나(1)뿐이다.
     */
    @Test
    void suspiciousRunStartsAreMarkedAndTheCampaignContinues() {
        int settlement = middleSettlementIndex();
        int runStart = campaign.runStartBefore(settlement);
        ObjectNode original = campaign.log(runStart);
        JsonNode payload = original.get("payload");

        ObjectNode supplyChanged = original.deepCopy();
        ObjectNode firstSupply = (ObjectNode) supplyChanged.get("payload").get("startSupply").get(0);
        firstSupply.put("count", firstSupply.get("count").asInt() + 1);

        Map<String, ObjectNode> variants = new LinkedHashMap<>();
        variants.put("START_LEVEL_MISMATCH", GoldenCampaign.withPayloadField(original, "startLevel", payload.get("startLevel").asInt() + 1));
        variants.put("TIME_LIMIT_MISMATCH", GoldenCampaign.withPayloadField(original, "timeLimitSeconds",
                payload.get("timeLimitSeconds").decimalValue().add(BigDecimal.ONE)));
        variants.put("START_SUPPLY_MISMATCH", supplyChanged);

        SoftAssertions.assertSoftly(soft -> variants.forEach((reason, tampered) -> {
            FakeClient client = clientWithPrefix(runStart);
            List<String> rest = new ArrayList<>();
            rest.add(tampered.toString());
            rest.addAll(List.of(campaign.jsons(runStart + 1, campaign.size())));

            // 나머지 로그를 모두 한 번에 보내면 한도(500)를 넘을 수 있어 나눠 보낸다
            FakeClient.Response last = null;
            for (int from = 0; from < rest.size(); from += REPLAY_CHUNK) {
                last = client.sync(rest.subList(from, Math.min(from + REPLAY_CHUNK, rest.size())).toArray(new String[0]));
                soft.assertThat(last.status()).as("%s: 요청 본문 %s", reason, last.body()).isEqualTo(200);
                if (last.status() != 200) {
                    return;
                }
                soft.assertThat(last.<Object>read("$.rejected")).as("%s: 거부 없이 계속되어야 한다", reason).isNull();
            }

            State server = serverState(client.loadProgress().expect(200), "$");
            soft.assertThat(server.gold()).as("%s: 최종 Gold", reason).isEqualTo(campaign.finalGold());
            soft.assertThat(server.growthStage()).as("%s: 최종 성장도", reason).isEqualTo(campaign.finalGrowthStage());
            soft.assertThat(server.ranks()).as("%s: 최종 노드", reason).containsExactlyEntriesOf(campaign.finalRanks());
            soft.assertThat(suspiciousCount(client)).as("%s: 의심 표시(판 하나당 1)", reason).isEqualTo(1);

            String runId = payload.get("runId").asString();
            BattleRun run = runRepository.findByAccountIdAndRunId(accountId(client), runId).orElseThrow();
            soft.assertThat(run.getSuspiciousReasons()).as("%s: 판의 의심 사유", reason).contains(reason);
        }));
    }

    /**
     * 조작 24(한계 고정): 장부를 일관되게 부풀리면(처치 하나를 더 센 만큼 exp, earnedGold, settledGold도 올린다) 서버는 막지 못한다.
     * 서버가 판 내용을 재현하지 못하고 통계 검사(S10)가 없기 때문이다(계획서 10장). 이 동작을 고정해 두고, S10을 넣을 때 의도된 변경인지 알게 한다.
     */
    @Test
    void aConsistentlyInflatedLedgerIsNotCaught() {
        InflatedSettlement inflated = inflatedSettlement();
        FakeClient client = clientWithPrefix(inflated.index());

        FakeClient.Response response = client.sync(inflated.log().toString()).expect(200);

        assertThat(response.<Object>read("$.rejected")).as("한계 고정: 일관되게 부풀린 결산은 반영된다").isNull();
        assertThat(response.number("$.appliedThroughSeq")).isEqualTo(inflated.index() + 1L);

        State before = campaign.stateAfter(inflated.index());
        assertThat(serverState(response, "$.progress"))
                .isEqualTo(new State(before.gold() + inflated.extraGold(), before.growthStage(), before.ranks()));
    }

    // ---------- 조작표 만들기 ----------

    // 조작 하나: 접두 구간 position(로그 개수) 뒤에 보내는 로그들과 기대
    private record Attempt(
            int number,
            String name,
            int position,
            List<ObjectNode> logs,
            int status,
            long rejectedSeq,
            String rejectedType,
            String code,
            long appliedThroughSeq,
            State stateAfter,
            boolean mutating) {
    }

    // 조작표를 위치(접두 구간 길이)별로 묶은 것과, 조건을 만족하지 못해 적용할 수 없었던 조작
    private record Plan(Map<Integer, List<Attempt>> byPosition, List<String> notApplicable) {
    }

    // 조건을 만족하는 로그나 노드가 없어 조작을 만들 수 없을 때(건너뛰지 않고 실패로 모은다)
    private static final class NotApplicable extends RuntimeException {

        NotApplicable(String reason) {
            super(reason);
        }

    }

    // 결산을 일관되게 부풀린 로그와 늘어난 Gold
    private record InflatedSettlement(int index, ObjectNode log, long extraGold) {
    }

    private Plan rejectionPlan() {
        Map<Integer, List<Attempt>> byPosition = new TreeMap<>();
        List<String> notApplicable = new ArrayList<>();

        record Case(int number, String name, Supplier<List<Attempt>> build) {
        }

        List<Case> cases = new ArrayList<>();

        // 조작 1, 3, 9: 초반·중반·후반
        for (String role : List.of("first", "middle", "last")) {
            cases.add(new Case(1, "earnedGold +1 (" + role + ")", () -> List.of(settlementRejection(1, role, "earnedGold +1",
                    index -> GoldenCampaign.withPayloadField(campaign.log(index), "earnedGold", payloadLong(index, "earnedGold") + 1),
                    "SETTLEMENT_GOLD_MISMATCH"))));
            cases.add(new Case(3, "exp +1 (" + role + ")", () -> List.of(settlementRejection(3, role, "exp +1",
                    index -> GoldenCampaign.withPayloadField(campaign.log(index), "exp", payloadLong(index, "exp") + 1),
                    "SETTLEMENT_EXP_MISMATCH"))));
            cases.add(new Case(9, "runId를 다른 값으로 (" + role + ")", () -> List.of(settlementRejection(9, role, "다른 runId",
                    index -> GoldenCampaign.withPayloadField(campaign.log(index), "runId", OTHER_RUN_ID),
                    "RUN_NOT_OPEN"))));
            cases.add(new Case(9, "같은 결산을 새 seq로 한 번 더 (" + role + ")", () -> List.of(duplicateSettlement(role))));
        }

        // 조작 2, 4: 이정표와 중반
        for (String role : List.of("milestone", "middle")) {
            cases.add(new Case(2, "settledGold +1 (" + role + ")", () -> List.of(settlementRejection(2, role, "settledGold +1",
                    index -> GoldenCampaign.withPayloadField(campaign.log(index), "settledGold", payloadLong(index, "settledGold") + 1),
                    "SETTLEMENT_RESULT_MISMATCH"))));
            cases.add(new Case(4, "reachedMilestone 뒤집기 (" + role + ")", () -> List.of(settlementRejection(4, role, "reachedMilestone 뒤집기",
                    index -> GoldenCampaign.withPayloadField(campaign.log(index), "reachedMilestone", !payloadBoolean(index, "reachedMilestone")),
                    "SETTLEMENT_EXP_MISMATCH"))));
        }
        cases.add(new Case(4, "reachedLevel +1 (middle)", () -> List.of(settlementRejection(4, "middle", "reachedLevel +1",
                index -> GoldenCampaign.withPayloadField(campaign.log(index), "reachedLevel", (int) payloadLong(index, "reachedLevel") + 1),
                "SETTLEMENT_EXP_MISMATCH"))));

        // 조작 5: 불가능한 kills 항목을 더한다
        cases.add(new Case(5, "kills 불가능한 항목", this::impossibleKills));

        // 조작 6, 7, 8
        cases.add(new Case(6, "goldenCritCount 초과", () -> List.of(settlementRejection(6, "golden", "치명 횟수 초과",
                index -> GoldenCampaign.withPayloadField(campaign.log(index), "goldenCritCount", eligibleCrits(index) + 1),
                "SETTLEMENT_GOLD_MISMATCH"))));
        cases.add(new Case(7, "timeBonusCount 초과", () -> List.of(settlementRejection(7, "middle", "시간 추가 횟수 초과",
                index -> GoldenCampaign.withPayloadField(campaign.log(index), "timeBonusCount", eligibleTimeBonus(index) + 1),
                "SETTLEMENT_TIME_EXCEEDED"))));
        cases.add(new Case(8, "playedSeconds 초과", () -> List.of(settlementRejection(8, "middle", "S8 상한 초과",
                index -> GoldenCampaign.withPayloadField(campaign.log(index), "playedSeconds", timeBound(index) + OVER_TIME),
                "SETTLEMENT_TIME_EXCEEDED"))));

        // 조작 11~16: 구매
        cases.add(new Case(11, "cost -1", () -> List.of(purchaseRejection(11, false, "비용 -1", state -> true,
                (index, state) -> GoldenCampaign.withPayloadField(campaign.log(index), "cost", payloadLong(index, "cost") - 1),
                "COST_MISMATCH"))));
        cases.add(new Case(12, "rank +1", () -> List.of(purchaseRejection(12, false, "rank +1", state -> true,
                (index, state) -> GoldenCampaign.withPayloadField(campaign.log(index), "rank", (int) payloadLong(index, "rank") + 1),
                "RANK_MISMATCH"))));
        cases.add(new Case(13, "드러나지 않은 노드", () -> List.of(purchaseRejection(13, false, "숨은 노드", state -> hiddenNode(state).isPresent(),
                (index, state) -> replaceNode(index, hiddenNode(state).orElseThrow(), state), "NODE_HIDDEN"))));
        cases.add(new Case(14, "Gold가 모자란 노드", () -> List.of(purchaseRejection(14, false, "Gold 부족", state -> unaffordableNode(state).isPresent(),
                (index, state) -> replaceNode(index, unaffordableNode(state).orElseThrow(), state), "NOT_ENOUGH_GOLD"))));
        cases.add(new Case(15, "최대 Rank인 노드", () -> List.of(purchaseRejection(15, true, "최대 Rank", state -> maxedNode(state).isPresent(),
                (index, state) -> replaceNode(index, maxedNode(state).orElseThrow(), state), "MAX_RANK_REACHED"))));
        cases.add(new Case(16, "없는 노드", () -> List.of(purchaseRejection(16, false, "없는 노드", state -> true,
                (index, state) -> GoldenCampaign.withPayloadFields(campaign.log(index),
                        Map.of("nodeId", NO_SUCH_NODE, "rank", 1, "cost", 1)),
                "UNKNOWN_NODE"))));

        // 조작 17, 18, 19, 21
        cases.add(new Case(17, "로그 하나를 빼고 다음 로그", () -> List.of(skippedLog())));
        cases.add(new Case(18, "인접한 두 로그의 순서 바꿈", () -> List.of(swappedLogs())));
        cases.add(new Case(19, "contentVersion (결산)", () -> List.of(settlementRejection(19, "middle", "다른 contentVersion",
                index -> GoldenCampaign.withLogField(campaign.log(index), "contentVersion", OTHER_CONTENT_VERSION),
                "CONTENT_VERSION_MISMATCH"))));
        cases.add(new Case(19, "contentVersion (구매)", () -> List.of(purchaseRejection(19, false, "다른 contentVersion", state -> true,
                (index, state) -> GoldenCampaign.withLogField(campaign.log(index), "contentVersion", OTHER_CONTENT_VERSION),
                "CONTENT_VERSION_MISMATCH"))));
        cases.add(new Case(21, "이미 쓴 runId", () -> List.of(duplicateRunId())));

        // 조작 22, 23: 마지막 이정표 이후 분기(클리어한 파일만)
        if (campaign.cleared()) {
            cases.add(new Case(22, "마지막 단계의 settledGold", () -> List.of(settlementRejection(22, "postClear", "settledGold ≠ earnedGold",
                    index -> GoldenCampaign.withPayloadField(campaign.log(index), "settledGold", payloadLong(index, "earnedGold") + 1),
                    "SETTLEMENT_RESULT_MISMATCH"))));
            cases.add(new Case(23, "마지막 단계의 reachedMilestone", () -> List.of(settlementRejection(23, "postClear", "마지막 단계에서 이정표 도달",
                    index -> GoldenCampaign.withPayloadField(campaign.log(index), "reachedMilestone", true),
                    "SETTLEMENT_EXP_MISMATCH"))));
        }

        for (Case c : cases) {
            try {
                for (Attempt attempt : c.build().get()) {
                    byPosition.computeIfAbsent(attempt.position(), key -> new ArrayList<>()).add(attempt);
                }
            } catch (NotApplicable e) {
                notApplicable.add("#%d %s: 적용할 수 없다 — %s".formatted(c.number(), c.name(), e.getMessage()));
            }
        }

        return new Plan(byPosition, notApplicable);
    }

    // 결산 하나를 tamper로 바꿔 보내고 role 위치에서 거부되는지 보는 조작. 같은 요청에 뒤의 로그를 함께 보낸다
    private Attempt settlementRejection(int number, String role, String name,
            IntFunction<ObjectNode> tamper, String code) {
        int index = settlementByRole(role);
        return rejectionAt(number, name + " [" + role + "]", index, tamper.apply(index), "SETTLEMENT", code);
    }

    // 구매 하나를 바꿔 보내는 조작. 조건(그 시점의 진행도에서 조작할 수 있는가)을 만족하는 구매 중 기준에 가장 가까운 것을 쓴다:
    // late가 false면 중반 결산에 가장 가까운 구매, true면 후반 결산보다 앞에서 가장 가까운 구매
    private Attempt purchaseRejection(int number, boolean late, String name, Predicate<State> condition,
            BiFunction<Integer, State, ObjectNode> tamper, String code) {
        int index = late ? lateCandidate(condition) : middleCandidate(condition);
        return rejectionAt(number, name, index, tamper.apply(index, campaign.stateAfter(index - 1)), "NODE_PURCHASE", code);
    }

    // 중반 결산에 가장 가까운 구매 중 condition을 만족하는 것(비용이 1 이상인 구매만)
    private int middleCandidate(Predicate<State> condition) {
        int middle = middleSettlementIndex();
        int best = -1;
        for (int index : campaign.costlyPurchases()) {
            if (condition.test(campaign.stateAfter(index - 1)) && (best < 0 || Math.abs(index - middle) < Math.abs(best - middle))) {
                best = index;
            }
        }
        if (best < 0) {
            throw new NotApplicable("조건을 만족하는 구매가 없다");
        }
        return best;
    }

    // 후반(마지막) 결산보다 앞에 있는 구매 중 condition을 만족하는 마지막 것
    private int lateCandidate(Predicate<State> condition) {
        int last = required(campaign.lastSettlement(), "후반 결산");
        List<Integer> purchases = campaign.costlyPurchases();
        for (int i = purchases.size() - 1; i >= 0; i--) {
            int index = purchases.get(i);
            if (index < last && condition.test(campaign.stateAfter(index - 1))) {
                return index;
            }
        }
        throw new NotApplicable("후반 결산 앞에 조건을 만족하는 구매가 없다");
    }

    // index 자리에 tampered를 넣어 보내면 seq index + 1에서 code로 거부되고 거부 직전 진행도가 그대로인 조작
    private Attempt rejectionAt(int number, String name, int index, ObjectNode tampered, String type, String code) {
        List<ObjectNode> logs = new ArrayList<>();
        logs.add(tampered);
        if (index + 1 < campaign.size()) {
            logs.add(campaign.log(index + 1));
        }
        return new Attempt(number, name, index, logs, 200, index + 1L, type, code, index, campaign.stateAfter(index - 1), false);
    }

    // 조작 9(두 번째): 같은 결산을 정상으로 반영한 뒤 새 seq로 한 번 더 보낸다. 판이 이미 닫혀서 RUN_NOT_OPEN이다. 결산이 반영되므로 위치의 마지막에 한다
    private Attempt duplicateSettlement(String role) {
        int index = settlementByRole(role);
        ObjectNode again = GoldenCampaign.withSeq(campaign.log(index), index + 2L);
        return new Attempt(9, "같은 결산을 새 seq로 한 번 더 [" + role + "]", index, List.of(campaign.log(index), again), 200, index + 2L,
                "SETTLEMENT", "RUN_NOT_OPEN", index + 1L, campaign.stateAfter(index), true);
    }

    // 조작 5: 이번 판에서 나올 수 없는 항목을 kills에 더한다(a 크기 초과, b 없는 종류, c 황금 불가능, d 색 불가능). c와 d는 서버 콘텐츠에 그런 종류가 있을 때만
    private List<Attempt> impossibleKills() {
        int index = required(campaign.middleSettlement(), "중반 결산");
        RunSetup setup = setupAt(index);
        String enemyId = setup.startSupply().isEmpty() ? null : setup.startSupply().get(0).enemyId();
        if (enemyId == null) {
            throw new NotApplicable("이번 판의 시작 공급이 비어 있다");
        }

        List<Attempt> attempts = new ArrayList<>();
        attempts.add(rejectionAt(5, "kills 크기 초과 (maxSize + 1)", index,
                withExtraKill(index, enemyId, 0, setup.kind(enemyId).maxSize() + 1, false), "SETTLEMENT", "SETTLEMENT_INVALID_KILLS"));
        attempts.add(rejectionAt(5, "kills 없는 종류", index,
                withExtraKill(index, NO_SUCH_ENEMY, 0, 1, false), "SETTLEMENT", "SETTLEMENT_INVALID_KILLS"));

        // 황금이 나올 수 없는 종류(수거형 제외)가 서버 콘텐츠에 있을 때
        for (EnemyKindDefinition kind : battleContent.enemies()) {
            KindSetup kindSetup = setup.kind(kind.enemyId());
            if (!kind.pickup() && !kindSetup.goldenPossible()) {
                attempts.add(rejectionAt(5, "kills 황금 불가능 종류 (" + kind.enemyId() + ")", index,
                        withExtraKill(index, kind.enemyId(), 0, 1, true), "SETTLEMENT", "SETTLEMENT_INVALID_KILLS"));
                break;
            }
        }

        // 이번 판 질량에서 나올 수 없는 색 등급이 있을 때
        outer:
        for (EnemyKindDefinition kind : battleContent.enemies()) {
            if (kind.pickup()) {
                continue;
            }
            for (int tier = 0; tier < kind.tiers().size(); tier++) {
                if (!setup.kind(kind.enemyId()).tierPossible(tier)) {
                    attempts.add(rejectionAt(5, "kills 색 불가능 (" + kind.enemyId() + " tier " + tier + ")", index,
                            withExtraKill(index, kind.enemyId(), tier, 1, false), "SETTLEMENT", "SETTLEMENT_INVALID_KILLS"));
                    break outer;
                }
            }
        }
        return attempts;
    }

    // 조작 17: 중반 결산을 빼고 바로 다음 로그를 보낸다. 다음 로그가 SEQ_GAP이고 반영된 마지막 번호는 빠진 로그의 앞이다
    private Attempt skippedLog() {
        int index = required(campaign.middleSettlement(), "중반 결산");
        if (index + 1 >= campaign.size()) {
            throw new NotApplicable("중반 결산 뒤에 로그가 없다");
        }
        ObjectNode next = campaign.log(index + 1);
        return new Attempt(17, "로그 하나를 빼고 다음 로그", index, List.of(next), 200, index + 2L, campaign.type(index + 1), "SEQ_GAP", index,
                campaign.stateAfter(index - 1), false);
    }

    // 조작 18: 같은 요청 안에서 인접한 두 로그의 순서를 바꾼다. seq가 1씩 늘지 않아 요청 전체가 400이고 아무것도 적용하지 않는다
    private Attempt swappedLogs() {
        int index = required(campaign.middleSettlement(), "중반 결산");
        if (index + 1 >= campaign.size()) {
            throw new NotApplicable("중반 결산 뒤에 로그가 없다");
        }
        return new Attempt(18, "인접한 두 로그의 순서 바꿈", index, List.of(campaign.log(index + 1), campaign.log(index)), 400, 0, null,
                "INVALID_REQUEST", index, campaign.stateAfter(index - 1), false);
    }

    // 조작 21: 중반 판의 RUN_START에 이미 쓴 runId(처음 나온 RUN_START의 것)를 넣는다
    private Attempt duplicateRunId() {
        int middle = required(campaign.middleSettlement(), "중반 결산");
        int runStart = campaign.runStartBefore(middle);
        int first = -1;
        for (int i = 0; i < runStart; i++) {
            if ("RUN_START".equals(campaign.type(i))) {
                first = i;
                break;
            }
        }
        if (first < 0) {
            throw new NotApplicable("앞선 판이 없어 겹칠 runId가 없다");
        }
        String used = campaign.payload(first).get("runId").asString();
        return rejectionAt(21, "이미 쓴 runId", runStart, GoldenCampaign.withPayloadField(campaign.log(runStart), "runId", used), "RUN_START",
                "DUPLICATE_RUN_ID");
    }

    // ---------- 한 조작 실행 ----------

    // 접두 구간(seq 1 ~ position)을 새 계정에 보내고, 서버 진행도가 접두의 마지막 체크포인트와 같은지 확인한 계정을 돌려준다
    private FakeClient clientWithPrefix(int position) {
        FakeClient client = startedClient();
        for (int start = 0; start < position; start += REPLAY_CHUNK) {
            int end = Math.min(start + REPLAY_CHUNK, position);
            FakeClient.Response response = client.sync(campaign.jsons(start, end));
            assertThat(response.status()).as("접두 구간 seq %d~%d: %s", start + 1, end, response.body()).isEqualTo(200);
            assertThat(response.<Object>read("$.rejected")).as("접두 구간은 골든이라 거부되면 안 된다 (seq %d~%d)", start + 1, end).isNull();
        }
        assertThat(serverState(client.loadProgress().expect(200), "$")).as("접두 구간 뒤의 진행도").isEqualTo(campaign.stateAfter(position - 1));
        return client;
    }

    private void run(SoftAssertions soft, FakeClient client, Attempt attempt) {
        String label = "#%d %s (seq %d)".formatted(attempt.number(), attempt.name(), attempt.position() + 1);
        FakeClient.Response response = client.sync(attempt.logs().stream().map(ObjectNode::toString).toArray(String[]::new));

        soft.assertThat(response.status()).as("%s: 상태 코드, 본문 %s", label, response.body()).isEqualTo(attempt.status());
        if (response.status() == 200 && attempt.status() == 200) {
            soft.assertThat(response.number("$.rejected.seq")).as("%s: 거부된 seq", label).isEqualTo(attempt.rejectedSeq());
            soft.assertThat(response.text("$.rejected.code")).as("%s: 거부 코드", label).isEqualTo(attempt.code());
            soft.assertThat(response.text("$.rejected.type")).as("%s: 거부된 로그 타입", label).isEqualTo(attempt.rejectedType());
            soft.assertThat(response.number("$.appliedThroughSeq")).as("%s: 반영된 마지막 번호", label).isEqualTo(attempt.appliedThroughSeq());
        } else if (response.status() == attempt.status()) {
            soft.assertThat(response.errorCode()).as("%s: 오류 코드", label).isEqualTo(attempt.code());
        }

        State server = serverState(client.loadProgress().expect(200), "$");
        soft.assertThat(server).as("%s: 진행도(거부된 로그와 그 뒤의 로그는 반영되지 않는다)", label).isEqualTo(attempt.stateAfter());
    }

    // ---------- 값 계산(서버 콘텐츠) ----------

    // 결산 로그 index를 검증할 때 서버가 쓰는 판 설정: 그 시점의 산 노드와 열린 판(RUN_START)의 성장도
    private RunSetup setupAt(int settlementIndex) {
        State state = campaign.stateAfter(settlementIndex - 1);
        int stage = campaign.payload(campaign.runStartBefore(settlementIndex)).get("startGrowthStage").asInt();
        return rules.setupFor(stage, rules.upgradesFor(state.ranks()));
    }

    // 조작 6: 치명이 날 수 있는(황금이고 치명 확률이 0보다 큰 종류의) 처치 수
    private int eligibleCrits(int settlementIndex) {
        RunSetup setup = setupAt(settlementIndex);
        int total = 0;
        for (JsonNode kill : campaign.payload(settlementIndex).get("kills")) {
            if (kill.get("golden").asBoolean()
                    && setup.kind(kill.get("enemyId").asString()).golden().map(golden -> golden.critChance() > 0).orElse(false)) {
                total += kill.get("count").asInt();
            }
        }
        return total;
    }

    // 조작 7: 시간 추가가 날 수 있는(수거형이 아니고 시간 추가 확률이 0보다 큰 종류의) 처치 수
    private int eligibleTimeBonus(int settlementIndex) {
        RunSetup setup = setupAt(settlementIndex);
        int total = 0;
        for (JsonNode kill : campaign.payload(settlementIndex).get("kills")) {
            KindSetup kind = setup.kind(kill.get("enemyId").asString());
            if (!kind.pickup() && kind.timeChance() > 0) {
                total += kill.get("count").asInt();
            }
        }
        return total;
    }

    // 조작 8: 서버 S8 상한(SettlementValidator와 같은 순서와 타입)
    private double timeBound(int settlementIndex) {
        RunSetup setup = setupAt(settlementIndex);
        JsonNode payload = campaign.payload(settlementIndex);
        return (double) setup.timeLimitSeconds()
                + (double) (payload.get("reachedLevel").asInt() - setup.startLevel()) * setup.growthTimeSeconds()
                + (double) payload.get("timeBonusCount").asInt() * setup.killTimeBonusSeconds();
    }

    // 결산 index의 kills 끝에 항목 하나를 더한 로그(조작 5). 같은 칸이 이미 있으면 형식 오류가 되므로 기존에 없는 조합만 쓴다
    private ObjectNode withExtraKill(int settlementIndex, String enemyId, int tier, int size, boolean golden) {
        ObjectNode log = campaign.log(settlementIndex);
        ArrayNode kills = (ArrayNode) log.get("payload").get("kills");
        for (JsonNode kill : kills) {
            if (kill.get("enemyId").asString().equals(enemyId) && kill.get("tier").asInt() == tier
                    && kill.get("size").asInt() == size && kill.get("golden").asBoolean() == golden) {
                throw new NotApplicable("kills에 이미 같은 칸이 있다: " + enemyId + " tier " + tier + " size " + size);
            }
        }
        kills.add(GoldenCampaign.node(Map.of("enemyId", enemyId, "tier", tier, "size", size, "golden", golden, "count", 1)));
        return log;
    }

    // 구매 로그를 다른 노드의 구매로 바꾼다: 그 노드의 다음 Rank와 서버 비용(rank, cost는 서버 값이라 거부 사유는 노드 쪽에서만 난다)
    private ObjectNode replaceNode(int purchaseIndex, NodeDefinition node, State state) {
        int rank = Math.min(state.ranks().getOrDefault(node.id(), 0) + 1, node.maxRank());
        return GoldenCampaign.withPayloadFields(campaign.log(purchaseIndex),
                Map.of("nodeId", node.id(), "rank", rank, "cost", node.rankAt(rank).cost()));
    }

    // 조작 13: 그 시점에 아직 드러나지 않은(선으로 이어진 노드를 아직 하나도 사지 않은) 노드
    private Optional<NodeDefinition> hiddenNode(State state) {
        for (NodeDefinition node : nodeTree.nodes()) {
            boolean notOwned = !state.ranks().containsKey(node.id());
            if (notOwned && !nodeTree.graph().isRevealed(node.id(), state.ranks()::containsKey)) {
                return Optional.of(node);
            }
        }
        return Optional.empty();
    }

    // 조작 14: 드러났고 최대 Rank가 아니며 다음 Rank의 비용이 그 시점의 Gold보다 큰 노드
    private Optional<NodeDefinition> unaffordableNode(State state) {
        for (NodeDefinition node : nodeTree.nodes()) {
            int current = state.ranks().getOrDefault(node.id(), 0);
            if (current < node.maxRank() && nodeTree.graph().isRevealed(node.id(), state.ranks()::containsKey)
                    && node.rankAt(current + 1).cost() > state.gold()) {
                return Optional.of(node);
            }
        }
        return Optional.empty();
    }

    // 조작 15: 그 시점에 이미 최대 Rank까지 산 노드
    private Optional<NodeDefinition> maxedNode(State state) {
        for (NodeDefinition node : nodeTree.nodes()) {
            if (state.ranks().getOrDefault(node.id(), 0) >= node.maxRank()) {
                return Optional.of(node);
            }
        }
        return Optional.empty();
    }

    // 조작 24: 중반에서 가까운 순서로, 이정표가 아니고 처치 하나를 더해도 도달 Level이 그대로인 결산을 골라 일관되게 부풀린다
    private InflatedSettlement inflatedSettlement() {
        int middle = middleSettlementIndex();
        List<Integer> candidates = new ArrayList<>(campaign.battleSettlements());
        candidates.sort((a, b) -> Integer.compare(Math.abs(a - middle), Math.abs(b - middle)));

        for (int index : candidates) {
            JsonNode payload = campaign.payload(index);
            if (payload.get("reachedMilestone").asBoolean()) {
                continue;
            }
            RunSetup setup = setupAt(index);
            for (JsonNode kill : payload.get("kills")) {
                if (kill.get("golden").asBoolean()) {
                    continue;
                }
                String enemyId = kill.get("enemyId").asString();
                long extraExp = setup.kind(enemyId).expOf(kill.get("tier").asInt(), kill.get("size").asInt());
                long extraGold = setup.kind(enemyId).goldOf(kill.get("tier").asInt(), kill.get("size").asInt(), false);
                long exp = payload.get("exp").longValue() + extraExp;
                if (setup.levelForExp(exp) != payload.get("reachedLevel").asInt()) {
                    continue;
                }

                ObjectNode log = campaign.log(index);
                ObjectNode changed = (ObjectNode) log.get("payload");
                for (JsonNode item : changed.get("kills")) {
                    if (item.get("enemyId").asString().equals(enemyId) && item.get("tier").asInt() == kill.get("tier").asInt()
                            && item.get("size").asInt() == kill.get("size").asInt() && !item.get("golden").asBoolean()) {
                        ((ObjectNode) item).put("count", item.get("count").asInt() + 1);
                        break;
                    }
                }
                changed.put("exp", exp);
                changed.put("earnedGold", payload.get("earnedGold").longValue() + extraGold);
                changed.put("settledGold", payload.get("settledGold").longValue() + extraGold);
                return new InflatedSettlement(index, log, extraGold);
            }
        }
        throw new AssertionError("조작 24: 이정표가 아니고 도달 Level이 그대로인 결산이 없다");
    }

    // ---------- 위치 고르기 도우미 ----------

    private int settlementByRole(String role) {
        OptionalInt found = switch (role) {
            case "first" -> campaign.firstSettlement();
            case "milestone" -> campaign.milestoneSettlement();
            case "middle" -> campaign.middleSettlement();
            case "last" -> campaign.lastSettlement();
            case "golden" -> campaign.goldenSettlement();
            case "postClear" -> campaign.postClearSettlement();
            default -> throw new IllegalArgumentException("알 수 없는 역할: " + role);
        };
        return required(found, role + " 결산");
    }

    private int middleSettlementIndex() {
        return required(campaign.middleSettlement(), "중반 결산");
    }

    private static int required(OptionalInt found, String what) {
        if (found.isEmpty()) {
            throw new NotApplicable(what + "을(를) 고를 수 없다");
        }
        return found.getAsInt();
    }

    private long payloadLong(int index, String field) {
        return campaign.payload(index).get(field).longValue();
    }

    private boolean payloadBoolean(int index, String field) {
        return campaign.payload(index).get(field).asBoolean();
    }

    // ---------- 서버 상태 읽기 ----------

    // 응답의 진행도(path 아래: 동기화 응답은 "$.progress", GET /me/progress는 "$")를 State로 읽는다
    private static State serverState(FakeClient.Response response, String path) {
        String prefix = "$".equals(path) ? "$" : path;
        long gold = response.number(prefix + ".gold");
        int growthStage = (int) response.number(prefix + ".growthStage");
        List<Map<String, Object>> nodes = response.read(prefix + ".nodes");
        Map<String, Integer> ranks = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes) {
            ranks.put((String) node.get("nodeId"), ((Number) node.get("rank")).intValue());
        }
        return new State(gold, growthStage, ranks);
    }

    // 로그를 하나씩 보내 서버 진행도가 처음 체크포인트와 어긋나는 seq를 찾는다(진단용). endExclusive까지만 본다
    private String firstDivergence(int endExclusive) {
        FakeClient client = startedClient();
        for (int i = 0; i < endExclusive; i++) {
            FakeClient.Response response = client.sync(campaign.json(i));
            if (response.status() != 200 || response.<Object>read("$.rejected") != null) {
                return "진단: seq %d에서 거부되거나 오류가 났다. 응답: %s".formatted(i + 1, response.body());
            }
            State server = serverState(response, "$.progress");
            State expected = campaign.stateAfter(i);
            if (server.gold() != expected.gold() || server.growthStage() != expected.growthStage()
                    || !server.ranks().equals(expected.ranks())) {
                return "진단: seq %d(%s)에서 처음 어긋난다. 서버 %s, 클라이언트 %s".formatted(i + 1, campaign.type(i), server, expected);
            }
        }
        return "진단: 하나씩 보내면 어긋나지 않는다(여러 개를 한 요청으로 보낼 때만 어긋난다).";
    }

    // ---------- DB와 계정 ----------

    private FakeClient startedClient() {
        FakeClient client = new FakeClient("http://localhost:" + port);
        client.start();
        return client;
    }

    private long accountId(FakeClient client) {
        return client.call("GET", "/me", null).number("$.accountId");
    }

    private List<SyncLogResult> results(FakeClient client) {
        return logRepository.findAllByAccountIdOrderByIdAsc(accountId(client)).stream().map(SyncLogRecord::getResult).toList();
    }

    // 계정에 남은 의심 표시의 수(의심이 있는 판 하나당 1)
    private int suspiciousCount(FakeClient client) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT suspicious_count FROM player_progress WHERE account_id = ?", Integer.class, accountId(client));
        return Objects.requireNonNull(count);
    }

}
