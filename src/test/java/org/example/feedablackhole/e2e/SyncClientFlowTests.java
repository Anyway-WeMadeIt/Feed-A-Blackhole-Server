package org.example.feedablackhole.e2e;

import com.jayway.jsonpath.JsonPath;
import org.example.feedablackhole.TestcontainersConfiguration;
import org.example.feedablackhole.support.FakeClient;
import org.example.feedablackhole.support.MutableClock;
import org.example.feedablackhole.support.SyncSamples;
import org.example.feedablackhole.sync.entity.BattleRun;
import org.example.feedablackhole.sync.entity.BattleRunStatus;
import org.example.feedablackhole.sync.entity.SyncLogRecord;
import org.example.feedablackhole.sync.entity.SyncLogResult;
import org.example.feedablackhole.sync.repository.BattleRunRepository;
import org.example.feedablackhole.sync.repository.SyncLogRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동기화 API(POST /me/sync)를 실제 처리기, 실제 HTTP, 실제 커밋으로 끝에서 끝까지 확인한다(W1-8).
 * 서버를 실제 포트로 띄우고, FakeClient가 샘플 로그(SyncSamples)를 JSON 글자 그대로 보낸다.
 * 테스트 전체를 하나의 트랜잭션으로 묶지 않아 실제 커밋을 거치고, 테스트마다 새 계정을 만들어 서로 영향을 주지 않는다.
 * 스프링 테스트는 샘플 콘텐츠 묶음(contentVersion 4d6c398ad1f5e49c) 위에서 돈다.
 *
 * <p>Stage1ClientFlowTests와 @Import 구성을 같게 두어, 스프링 컨텍스트와 MySQL 컨테이너를 재사용한다(시간이 줄어든다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, Stage1ClientFlowTests.TestClockConfig.class})
class SyncClientFlowTests {

    // 서버가 뜬 임시 포트 번호
    @LocalServerPort
    private int port;

    // 토큰 만료를 재현하려고 시간을 앞당기는 시계(시나리오 7에서 쓴다)
    @Autowired
    private MutableClock clock;

    // sync_log의 처리 결과를 읽는 저장소. 결과는 HTTP 응답에 없어서 DB에서 직접 확인한다
    @Autowired
    private SyncLogRecordRepository logRepository;

    @Autowired
    private BattleRunRepository runRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ---------- 응답의 JSON 경로(동기화 응답: appliedThroughSeq, rejected, progress) ----------

    // 서버가 이 요청을 처리한 뒤 반영한 마지막 로그 번호(응답 최상위). 정상이면 보낸 마지막 seq, 거부되면 거부 앞까지다
    private static final String APPLIED_THROUGH_SEQ = "$.appliedThroughSeq";

    // 거부된 로그(seq, type, code, message). 거부가 없으면 null이다
    private static final String REJECTED = "$.rejected";

    // 진행도 안의 마지막으로 반영한 로그 번호(서버 DB의 값). 다음 로그는 이 값 + 1이어야 한다
    private static final String PROGRESS_LAST_APPLIED_SEQ = "$.progress.lastAppliedSeq";

    // 진행도의 Gold
    private static final String PROGRESS_GOLD = "$.progress.gold";

    // 진행도의 산 노드 개수(JsonPath의 length() 함수)
    private static final String PROGRESS_NODES_LENGTH = "$.progress.nodes.length()";

    // 진행도의 블랙홀 성장도(결산에서 이정표에 닿으면 오른다)
    private static final String PROGRESS_GROWTH_STAGE = "$.progress.growthStage";

    // 진행도의 산 노드 목록(처음 산 순서, 각 항목은 nodeId와 rank)
    private static final String PROGRESS_NODES = "$.progress.nodes";

    private static final String START_GOLD_MISMATCH = "START_GOLD_MISMATCH";
    private static final String SETTLEMENT = "SETTLEMENT";
    private static final String SETTLEMENT_GOLD_MISMATCH = "SETTLEMENT_GOLD_MISMATCH";
    private static final String START_GOLD = "startGold";
    private static final String TIME_LIMIT_SECONDS = "timeLimitSeconds";
    private static final String INVALID_REQUEST = "INVALID_REQUEST";
    private static final String RUN_START = "RUN_START";
    private static final String DUPLICATE_RUN_ID = "DUPLICATE_RUN_ID";

    // ---------- 시나리오 ----------

    /**
     * 시나리오 1(순차 적용): 새 계정에 샘플 로그 6개(판 시작, 결산, 구매, 판 시작, 결산, 새 게임)를 한 번에 보내면
     * 서버가 순서대로 모두 반영한다. 마지막이 새 게임이라 진행도는 처음 상태로 돌아간다(계약 3.8의 sync-response-applied.json).
     */
    @Test
    void sampleRequestIsAppliedInOrder() {
        FakeClient client = startedClient();

        FakeClient.Response response = client.sync(SyncSamples.request()).expect(200);

        // 6개 모두 반영되고 거부된 로그는 없다
        assertThat(response.number(APPLIED_THROUGH_SEQ)).isEqualTo(6);
        assertThat(response.<Object>read(REJECTED)).isNull();

        // 진행도: 마지막 로그가 새 게임이므로 Gold 0, 성장도 0, 산 노드 없음. 반영한 마지막 번호만 6이다
        assertThat(response.number(PROGRESS_LAST_APPLIED_SEQ)).isEqualTo(6);
        assertThat(response.number(PROGRESS_GOLD)).isEqualTo(0);
        assertThat(response.number(PROGRESS_GROWTH_STAGE)).isEqualTo(0);
        assertThat(response.<List<Object>>read(PROGRESS_NODES)).isEmpty();

        // sync_log에는 6건 모두 반영(APPLIED)으로 기록된다
        assertThat(results(client)).containsOnly(SyncLogResult.APPLIED).hasSize(6);
    }

    /**
     * 시나리오 1b(나눠 보내기): 샘플 로그를 seq 1~3, 이어서 4~6의 두 요청으로 나눠 보내도 한 번에 보낸 것과 같은 결과다.
     * 1~3 뒤에는 결산으로 번 Gold 20에서 구매 비용 2를 뺀 18이 남고, 이 값이 seq 4(판 시작)의 startGold와 같다.
     */
    @Test
    void sampleRequestCanBeSentInTwoParts() {
        FakeClient client = startedClient();

        // 첫 번째 요청(seq 1~3): 판 시작, 결산(Gold 20), timer-01 구매(비용 2)
        FakeClient.Response first = client.sync(SyncSamples.logs(1, 3)).expect(200);

        assertThat(first.number(APPLIED_THROUGH_SEQ)).isEqualTo(3);
        assertThat(first.<Object>read(REJECTED)).isNull();
        assertThat(first.number(PROGRESS_LAST_APPLIED_SEQ)).isEqualTo(3);
        assertThat(first.number(PROGRESS_GOLD)).isEqualTo(18);
        assertThat(first.number(PROGRESS_NODES_LENGTH)).isEqualTo(1);
        assertThat(first.text("$.progress.nodes[0].nodeId")).isEqualTo("timer-01");
        assertThat(first.number("$.progress.nodes[0].rank")).isEqualTo(1);

        // 첫 요청 직후의 기록: seq 1~3이 반영된다
        assertThat(results(client)).containsOnly(SyncLogResult.APPLIED).hasSize(3);

        // 두 번째 요청(seq 4~6): 판 시작, 결산, 새 게임. 한 번에 보낸 것(시나리오 1)과 같은 결과다
        FakeClient.Response second = client.sync(SyncSamples.logs(4, 6)).expect(200);

        assertThat(second.number(APPLIED_THROUGH_SEQ)).isEqualTo(6);
        assertThat(second.<Object>read(REJECTED)).isNull();
        assertThat(second.number(PROGRESS_LAST_APPLIED_SEQ)).isEqualTo(6);
        assertThat(second.number(PROGRESS_GOLD)).isEqualTo(0);
        assertThat(second.number(PROGRESS_GROWTH_STAGE)).isEqualTo(0);
        assertThat(second.<List<Object>>read(PROGRESS_NODES)).isEmpty();

        // 두 번째 요청 직후의 기록: 앞의 3건에 이어 seq 4~6도 모두 반영으로 기록되어 6건이다
        assertThat(results(client)).containsOnly(SyncLogResult.APPLIED).hasSize(6);
    }

    /**
     * 시나리오 2(재전송): 이미 반영한 로그를 다시 보내도 이중으로 반영되지 않는다. 서버는 seq가 이미 반영한 번호 이하인 로그를
     * 처리기에 보내지 않고 SKIPPED로 기록한다. 일부만 겹치는 요청(seq 2~5)에서는 겹치는 앞부분(2, 3)만 건너뛰고 새 로그(4, 5)만 반영한다.
     */
    @Test
    void resendingAppliedLogsDoesNotApplyThemTwice() {
        FakeClient client = startedClient();
        FakeClient.Response first = client.sync(SyncSamples.logs(1, 3)).expect(200);

        assertThat(first.number(APPLIED_THROUGH_SEQ)).isEqualTo(3);
        assertThat(first.number(PROGRESS_GOLD)).isEqualTo(18);
        assertThat(first.number(PROGRESS_NODES_LENGTH)).isEqualTo(1);

        // 첫 요청 직후: seq 1~3 반영
        assertThat(results(client)).containsOnly(SyncLogResult.APPLIED).hasSize(3);

        // 같은 로그를 그대로 다시 보낸다(네트워크가 끊겨 응답을 못 받은 클라이언트가 다시 보내는 경우) -> 재전송
        FakeClient.Response resent = client.sync(SyncSamples.logs(1, 3)).expect(200);

        assertThat(resent.number(APPLIED_THROUGH_SEQ)).isEqualTo(3);
        assertThat(resent.<Object>read(REJECTED)).isNull();
        assertThat(resent.number(PROGRESS_LAST_APPLIED_SEQ)).isEqualTo(3);
        assertThat(resent.number(PROGRESS_GOLD)).isEqualTo(18);
        assertThat(resent.number(PROGRESS_NODES_LENGTH)).isEqualTo(1);
        assertThat(resent.number("$.progress.nodes[0].rank")).isEqualTo(1);

        // 재전송 직후: 앞의 3건에 이어 이미 반영한 seq 1~3이 건너뜀(SKIPPED)으로 기록된다
        assertThat(results(client)).containsExactly(
                SyncLogResult.APPLIED, SyncLogResult.APPLIED, SyncLogResult.APPLIED,
                SyncLogResult.SKIPPED, SyncLogResult.SKIPPED, SyncLogResult.SKIPPED
        );

        // 일부만 겹치는 요청: 2, 3은 이미 반영했으니 건너뛰고 4, 5만 반영한다
        FakeClient.Response overlapping = client.sync(SyncSamples.logs(2, 5)).expect(200);

        assertThat(overlapping.number(APPLIED_THROUGH_SEQ)).isEqualTo(5);
        assertThat(overlapping.<Object>read(REJECTED)).isNull();
        assertThat(overlapping.number(PROGRESS_LAST_APPLIED_SEQ)).isEqualTo(5);
        assertThat(overlapping.number(PROGRESS_GOLD)).isEqualTo(36);
        assertThat(overlapping.number(PROGRESS_NODES_LENGTH)).isEqualTo(1);

        // sync_log: 처음 3건(1~3) 반영, 재전송 3건(1~3) 건너뜀, 겹친 요청에서 2건(2~3) 건너뜀 + 2건(4~5) 반영
        assertThat(results(client)).containsExactly(
                SyncLogResult.APPLIED, SyncLogResult.APPLIED, SyncLogResult.APPLIED,
                SyncLogResult.SKIPPED, SyncLogResult.SKIPPED, SyncLogResult.SKIPPED,
                SyncLogResult.SKIPPED, SyncLogResult.SKIPPED,
                SyncLogResult.APPLIED, SyncLogResult.APPLIED
        );
    }

    /**
     * 시나리오 3(누락): 서버가 마지막으로 반영한 번호(lastAppliedSeq)가 0인 새 계정에 seq 3부터 보내면
     * 앞 로그(1, 2)가 빠졌으므로 SEQ_GAP으로 거부하고, 뒤의 로그는 처리하지 않는다(NOT_PROCESSED).
     * 이어서 빠진 로그부터(seq 1~6) 다시 보내면 정상 반영된다.
     */
    @Test
    void missingEarlierLogsAreRejectedAsSeqGapAndTheResendIsApplied() {
        FakeClient client = startedClient();

        // seq 3~6: 서버가 기다리는 번호는 1인데 3이 왔다
        FakeClient.Response gap = client.sync(SyncSamples.logs(3, 6)).expect(200);

        assertThat(gap.number(APPLIED_THROUGH_SEQ)).isEqualTo(0);
        assertThat(gap.number(REJECTED + ".seq")).isEqualTo(3);
        assertThat(gap.text(REJECTED + ".code")).isEqualTo("SEQ_GAP");
        assertThat(gap.number(PROGRESS_LAST_APPLIED_SEQ)).isEqualTo(0);

        // 처음 요청 직후의 기록: seq 3은 거부(SEQ_GAP), 뒤의 seq 4, 5, 6은 처리하지 않았다(NOT_PROCESSED)
        assertThat(results(client)).containsExactly(
                SyncLogResult.REJECTED, SyncLogResult.NOT_PROCESSED,
                SyncLogResult.NOT_PROCESSED, SyncLogResult.NOT_PROCESSED
        );

        // 빠진 로그부터 다시 보낸다(seq 1~6). 거부된 번호(3~6)가 다시 와도 이번에는 정상 처리된다
        FakeClient.Response resent = client.sync(SyncSamples.logs(1, 6)).expect(200);

        assertThat(resent.number(APPLIED_THROUGH_SEQ)).isEqualTo(6);
        assertThat(resent.<Object>read(REJECTED)).isNull();
        assertThat(resent.number(PROGRESS_LAST_APPLIED_SEQ)).isEqualTo(6);
        assertThat(resent.number(PROGRESS_GOLD)).isEqualTo(0);
        assertThat(resent.<List<Object>>read(PROGRESS_NODES)).isEmpty();

        // 처음 요청: seq 3은 거부(SEQ_GAP), 뒤의 seq 4, 5, 6은 처리하지 않았다(NOT_PROCESSED).
        // 재전송: seq 1~6이 모두 정상 반영(APPLIED)으로 이어서 기록된다
        assertThat(results(client)).containsExactly(
                SyncLogResult.REJECTED, SyncLogResult.NOT_PROCESSED,
                SyncLogResult.NOT_PROCESSED, SyncLogResult.NOT_PROCESSED,
                SyncLogResult.APPLIED, SyncLogResult.APPLIED, SyncLogResult.APPLIED,
                SyncLogResult.APPLIED, SyncLogResult.APPLIED, SyncLogResult.APPLIED
        );
    }

    /**
     * 시나리오 3(요청 안의 누락): 한 요청 안에서 seq가 1씩 증가하지 않으면(1, 3) 거부가 아니라 400 INVALID_REQUEST이고,
     * 아무것도 반영하지 않는다(계약 3.4). 서버의 진행도도 그대로다.
     */
    @Test
    void aGapInsideOneRequestIsABadRequestAndNothingIsApplied() {
        FakeClient client = startedClient();

        FakeClient.Response response = client.sync(SyncSamples.logs(1, 1)[0], SyncSamples.logs(3, 3)[0]);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.errorCode()).isEqualTo(INVALID_REQUEST);

        // 첫 로그(seq 1)도 반영되지 않았다
        FakeClient.Response progress = client.loadProgress().expect(200);

        assertThat(progress.number("$.lastAppliedSeq")).isEqualTo(0);
        assertThat(results(client)).isEmpty();
    }

    /**
     * 시나리오 4(N번째 거부): 한 요청의 중간 로그가 검증에 실패하면 거기서 멈춘다. 계약 샘플 sync-request-tampered.json은
     * seq 2 결산의 Gold를 20 대신 99999로 부풀린 것이라, 앞의 seq 1(판 시작)만 반영되고 seq 2는 SETTLEMENT_GOLD_MISMATCH로 거부되며
     * seq 3(구매)은 처리하지 않는다. 거부된 번호는 반영된 적이 없으므로, 고친 seq 2부터 다시 보내면 반영된다.
     */
    @Test
    void aTamperedSettlementIsRejectedAndTheRestIsNotProcessed() {
        FakeClient client = startedClient();

        FakeClient.Response rejected = client.sync(SyncSamples.tamperedLogs()).expect(200);

        assertThat(rejected.number(APPLIED_THROUGH_SEQ)).isEqualTo(1);
        assertThat(rejected.number(REJECTED + ".seq")).isEqualTo(2);
        assertThat(rejected.text(REJECTED + ".type")).isEqualTo(SETTLEMENT);
        assertThat(rejected.text(REJECTED + ".code")).isEqualTo(SETTLEMENT_GOLD_MISMATCH);

        // 진행도(계약 3.8의 sync-response-rejected.json): seq 1까지만 반영되었고 Gold와 노드는 그대로다
        assertThat(rejected.number(PROGRESS_LAST_APPLIED_SEQ)).isEqualTo(1);
        assertThat(rejected.number(PROGRESS_GOLD)).isEqualTo(0);
        assertThat(rejected.number(PROGRESS_GROWTH_STAGE)).isEqualTo(0);
        assertThat(rejected.<List<Object>>read(PROGRESS_NODES)).isEmpty();

        // seq 1은 반영, seq 2는 거부(SETTLEMENT_GOLD_MISMATCH), seq 3은 처리하지 않았다(NOT_PROCESSED)
        assertThat(results(client)).containsExactly(
                SyncLogResult.APPLIED, SyncLogResult.REJECTED, SyncLogResult.NOT_PROCESSED
        );

        // 고친(원본) seq 2부터 다시 보낸다. 거부된 번호를 다시 써도 이번에는 정상 반영된다
        FakeClient.Response resent = client.sync(SyncSamples.logs(2, 3)).expect(200);

        assertThat(resent.number(APPLIED_THROUGH_SEQ)).isEqualTo(3);
        assertThat(resent.<Object>read(REJECTED)).isNull();
        assertThat(resent.number(PROGRESS_LAST_APPLIED_SEQ)).isEqualTo(3);
        assertThat(resent.number(PROGRESS_GOLD)).isEqualTo(18);
        assertThat(resent.number(PROGRESS_NODES_LENGTH)).isEqualTo(1);

        // 처음 요청의 기록(seq 1 반영, 2 거부, 3 처리 안 함)에 이어, 고쳐서 다시 보낸 seq 2, 3이 반영으로 기록된다
        assertThat(results(client)).containsExactly(
                SyncLogResult.APPLIED, SyncLogResult.REJECTED, SyncLogResult.NOT_PROCESSED,
                SyncLogResult.APPLIED, SyncLogResult.APPLIED
        );
    }

    /**
     * 시나리오 5(RUN_START 불일치 후 계속): 판 시작의 값이 서버 계산과 다르면 거부하지 않고 의심 표시만 남긴 채 계속 처리한다(결정 D8).
     * seq 1의 startGold를 999로 바꾸면 서버의 Gold(0)와 달라 START_GOLD_MISMATCH가 남지만, 이어지는 결산과 구매는
     * 서버 값 기준으로 정상 처리된다. 판의 시작 Gold도 클라이언트가 보낸 999가 아니라 서버 값 0으로 기록된다.
     */
    @Test
    void aSuspiciousRunStartIsAppliedAndTheFollowingLogsUseServerValues() {
        FakeClient client = startedClient();
        String[] sample = SyncSamples.logs(1, 3);
        // 거짓 로그 생성 "payload": { "runId": "...", "startGold": 0, ... } "startGold": 0 -> 999로 수정
        String suspiciousStart = SyncSamples.withPayloadField(sample[0], START_GOLD, 999);

        FakeClient.Response response = client.sync(suspiciousStart, sample[1], sample[2]).expect(200);

        // 거부 없이 3건 모두 반영되고, 결산(20)과 구매(2)는 서버 값 기준이라 Gold 18이다
        assertThat(response.number(APPLIED_THROUGH_SEQ)).isEqualTo(3);
        assertThat(response.<Object>read(REJECTED)).isNull();
        assertThat(response.number(PROGRESS_GOLD)).isEqualTo(18);
        assertThat(response.number(PROGRESS_NODES_LENGTH)).isEqualTo(1);

        assertThat(results(client)).containsOnly(SyncLogResult.APPLIED).hasSize(3);

        // 판에는 의심 사유가 남고(결산으로 SETTLED), 판의 시작 Gold는 서버 값이며, 계정의 의심 표시는 1이다
        BattleRun run = runOf(client, runIdOf(sample[0]));

        assertThat(run.getSuspiciousReasons()).isEqualTo(START_GOLD_MISMATCH);
        assertThat(run.getStatus()).isEqualTo(BattleRunStatus.SETTLED);
        assertThat(run.getStartGold()).isEqualTo(0);
        assertThat(suspiciousCount(client)).isEqualTo(1);
    }

    /**
     * 시나리오 5(허용 오차): 제한 시간만 0.001초 이내로 다르면 같다고 보아 의심이 남지 않는다(계약 4.4, 결정 C22).
     */
    @Test
    void aTimeLimitWithinOneMillisecondLeavesNoSuspicion() {
        FakeClient client = startedClient();

        // 샘플의 판 시작 로그에서 timeLimitSeconds 하나만 서버 값(12)과 0.0005초 다르게 바꾼다. 나머지 값은 서버와 모두 같다
        String start = SyncSamples.withPayloadField(SyncSamples.logs(1, 1)[0], TIME_LIMIT_SECONDS, 12.0005);

        // 로그가 하나여도 배열로 넘겨야 {"logs":[...]}로 감싸진다(String 하나를 넘기면 요청 본문 전체로 보내진다)
        FakeClient.Response response = client.sync(new String[]{start}).expect(200);

        // 거부 없이 반영된다(어차피 의심은 거부 사유가 아니지만, 시나리오 5와 같은 확인을 둔다)
        assertThat(response.number(APPLIED_THROUGH_SEQ)).isEqualTo(1);
        assertThat(response.<Object>read(REJECTED)).isNull();

        // 허용 오차 안이라 판에 의심 사유가 없다(suspicious_reasons가 null). 판은 결산 전이라 OPEN 상태로 열려 있다
        BattleRun run = runOf(client, runIdOf(start));

        assertThat(run.getSuspiciousReasons()).isNull();

        // 계정에도 의심 표시가 쌓이지 않는다(의심이 있는 판 하나당 1씩 오르는 값이 0 그대로다)
        assertThat(suspiciousCount(client)).isEqualTo(0);
    }

    /**
     * 시나리오 5b(중복 runId): 이미 쓴 runId로 판 시작을 다시 보내면 DUPLICATE_RUN_ID로 거부하고 멈춘다.
     * 첫 판을 결산하지 않은 채 같은 runId가 또 오는 경우라서, 거부하면서 열린 판을 포기로 닫으면 안 된다(거부할 때는 진행도를 바꾸지 않는다).
     * 그래서 거부된 뒤에도 첫 판은 열려(OPEN) 있다.
     */
    @Test
    void aDuplicateRunIdIsRejectedAndTheFirstRunStaysOpen() {
        FakeClient client = startedClient();
        String start = SyncSamples.logs(1, 1)[0];
        String runId = runIdOf(start);

        // 첫 판 시작(seq 1): 정상 반영되고 판이 열린다. 로그가 하나여도 배열로 넘긴다
        FakeClient.Response first = client.sync(new String[]{start}).expect(200);

        assertThat(first.number(APPLIED_THROUGH_SEQ)).isEqualTo(1);
        assertThat(first.<Objects>read(REJECTED)).isNull();
        assertThat(results(client)).containsExactly(SyncLogResult.APPLIED);
        assertThat(runOf(client, runId).getStatus()).isEqualTo(BattleRunStatus.OPEN);

        // 같은 runId의 판 시작을 seq 2로 다시 보낸다. 첫 판은 아직 결산하지 않았다
        String duplication = SyncSamples.withLogField(start, "seq", 2);
        FakeClient.Response second = client.sync(new String[]{duplication}).expect(200);

        assertThat(second.number(APPLIED_THROUGH_SEQ)).isEqualTo(1);
        assertThat(second.number(REJECTED + ".seq")).isEqualTo(2);
        assertThat(second.text(REJECTED + ".type")).isEqualTo(RUN_START);
        assertThat(second.text(REJECTED + ".code")).isEqualTo(DUPLICATE_RUN_ID);
        assertThat(second.number(PROGRESS_LAST_APPLIED_SEQ)).isEqualTo(1);

        // 첫 요청의 반영 1건에 이어, 두 번째 요청의 거부 1건이 기록된다
        assertThat(results(client)).containsExactly(SyncLogResult.APPLIED, SyncLogResult.REJECTED);

        // 거부했으므로 첫 판은 닫히지 않고 그대로 열려 있다(포기로 닫히지 않았다)
        assertThat(runOf(client, runId).getStatus()).isEqualTo(BattleRunStatus.OPEN);
    }

    // ---------- 도우미 ----------

    // 서버 주소만 가진 클라이언트(아직 서버와 말하지 않음)
    private FakeClient newClient() {
        return new FakeClient("http://localhost:" + port);
    }

    // 새 계정을 만들어 로그인까지 끝낸 클라이언트(register → login). 테스트마다 새 계정을 쓴다
    private FakeClient startedClient() {
        FakeClient client = newClient();
        client.start();

        return client;
    }

    // 이 계정의 sync_log 처리 결과를 기록 순서대로 읽는다. 계정 번호는 GET /me로 알아낸다
    private List<SyncLogResult> results(FakeClient client) {
        return logRepository.findAllByAccountIdOrderByIdAsc(accountId(client)).stream()
                .map(SyncLogRecord::getResult)
                .toList();
    }

    // 이 계정의 번호. GET /me로 알아낸다
    private long accountId(FakeClient client) {
        return client.call("GET", "/me", null).number("$.accountId");
    }

    // 이 계정의 판(battle_run). HTTP로는 볼 수 없어서(조회 API가 없다) DB에서 읽는다
    private BattleRun runOf(FakeClient client, String runId) {
        return runRepository.findByAccountIdAndRunId(accountId(client), runId).orElseThrow();
    }

    // 계정에 남은 의심 표시의 수(의심이 있는 판 하나당 1)
    private int suspiciousCount(FakeClient client) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT suspicious_count FROM player_progress WHERE account_id = ?", Integer.class, accountId(client));

        return Objects.requireNonNull(count);
    }

    // 로그 글자에서 판의 runId를 꺼낸다
    private static String runIdOf(String logJson) {
        return JsonPath.read(logJson, "$.payload.runId");
    }

}
