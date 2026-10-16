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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

    // 판(battle_run)을 읽는 저장소. 판의 상태와 의심 사유는 HTTP 응답에 없어서 DB에서 직접 확인한다
    @Autowired
    private BattleRunRepository runRepository;

    // 계정의 의심 표시 수(player_progress.suspicious_count)를 SQL로 읽는 데 쓴다
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
    // 의심 표시를 남길 판의 시작 Gold가 서버 값과 다를 때의 의심 사유(SuspicionReason의 이름 그대로)
    private static final String START_GOLD_MISMATCH = "START_GOLD_MISMATCH";
    // 거부된 로그의 type 값(결산 로그)
    private static final String SETTLEMENT = "SETTLEMENT";
    // 결산의 Gold가 처치 장부로 계산한 값과 다를 때의 거부 코드
    private static final String SETTLEMENT_GOLD_MISMATCH = "SETTLEMENT_GOLD_MISMATCH";
    // 판 시작 로그의 payload 필드 이름: 클라이언트가 말하는 시작 Gold
    private static final String START_GOLD = "startGold";
    // 판 시작 로그의 payload 필드 이름: 클라이언트가 말하는 제한 시간(초)
    private static final String TIME_LIMIT_SECONDS = "timeLimitSeconds";
    // 요청 형식이 잘못되었을 때의 오류 코드(예: 한 요청 안에서 seq가 1씩 증가하지 않음)
    private static final String INVALID_REQUEST = "INVALID_REQUEST";
    // 거부된 로그의 type 값(판 시작 로그)
    private static final String RUN_START = "RUN_START";
    // 이미 쓴 runId로 판 시작을 보냈을 때의 거부 코드
    private static final String DUPLICATE_RUN_ID = "DUPLICATE_RUN_ID";
    // 로그의 contentVersion이 서버가 읽은 콘텐츠 묶음의 버전과 다를 때의 거부 코드
    private static final String CONTENT_VERSION_MISMATCH = "CONTENT_VERSION_MISMATCH";
    // 스프링 테스트가 읽는 샘플 콘텐츠 묶음의 버전. 서버가 응답의 contentVersion으로 돌려주는 값이다
    private static final String SAMPLE_CONTENT_VERSION = "4d6c398ad1f5e49c";

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

        // 응답 전체가 계약 샘플과 같다(updatedAt은 시각이라 제외). 위의 필드별 확인이 놓치는 추가·누락 필드도 잡는다
        assertThat(SyncSamples.comparable(response.body())).isEqualTo(SyncSamples.appliedResponse());

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

        // 응답 전체가 계약 샘플과 같다(updatedAt은 시각, message는 문장이라 제외)
        assertThat(SyncSamples.comparable(rejected.body())).isEqualTo(SyncSamples.rejectedResponse());

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

        FakeClient.Response response = client.sync(start).expect(200);

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

        // 첫 판 시작(seq 1): 정상 반영되고 판이 열린다
        FakeClient.Response first = client.sync(start).expect(200);

        assertThat(first.number(APPLIED_THROUGH_SEQ)).isEqualTo(1);
        assertThat(first.<Object>read(REJECTED)).isNull();
        assertThat(results(client)).containsExactly(SyncLogResult.APPLIED);
        assertThat(runOf(client, runId).getStatus()).isEqualTo(BattleRunStatus.OPEN);

        // 같은 runId의 판 시작을 seq 2로 다시 보낸다. 첫 판은 아직 결산하지 않았다
        String duplication = SyncSamples.withLogField(start, "seq", 2);
        FakeClient.Response second = client.sync(duplication).expect(200);

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

    /**
     * 시나리오 6(동시 요청: 같은 로그): 같은 계정을 쓰는 두 기기가 같은 로그(seq 1~3)를 동시에 보내도 이중으로 반영되지 않는다.
     * 서버는 계정의 진행도 행을 잠가 요청을 한 번에 하나씩 처리하므로, 먼저 온 쪽이 3건을 반영하고 늦게 온 쪽은
     * 이미 반영된 로그로 보아 모두 건너뛴다(SKIPPED). 이중 적용이면 결산 Gold가 두 번 들어가거나 구매가 두 번 처리되어 값이 어긋난다.
     * 한 번 통과로는 우연일 수 있어서 라운드마다 새 계정으로 15번 반복한다.
     */
    @Test
    void twoDevicesSendingTheSameLogsAtOnceApplyThemOnlyOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 같은 계정에 로그인한 두 기기(자격증명만 같고 토큰은 따로 받는다)
            for (int round = 0; round < 15; round++) {
                // deviceA와 deviceB는 보내는 일만 하고, origin은 같은 계정의 결과를 읽는 관찰자
                FakeClient origin = startedClient();        // 새 계정을 만들고 로그인 (계정 X)
                FakeClient deviceA = origin.anotherDevice(); // 계정 X의 자격증명을 복사한 다른 기기
                FakeClient deviceB = origin.anotherDevice(); // 계정 X의 자격증명을 복사한 또 다른 기기
                deviceA.start();
                deviceB.start();

                // 두 기기가 같은 seq 1~3을 동시에 보낸다
                List<FakeClient.Response> responses = runTogether(pool,
                        () -> deviceA.sync(SyncSamples.logs(1, 3)),
                        () -> deviceB.sync(SyncSamples.logs(1, 3)));

                // 둘 다 정상 응답이고 거부된 로그는 없다
                for (FakeClient.Response response : responses) {
                    assertThat(response.status()).as("라운드 %d", round).isEqualTo(200);
                    assertThat(response.<Object>read(REJECTED)).as("라운드 %d", round).isNull();
                    assertThat(response.number(APPLIED_THROUGH_SEQ)).as("라운드 %d", round).isEqualTo(3);
                }

                // 최종 진행도: 한 번만 반영된 값(Gold 20 - 2 = 18, timer-01 Rank 1)이다
                FakeClient.Response progress = origin.loadProgress().expect(200);

                assertThat(progress.number("$.lastAppliedSeq")).as("라운드 %d", round).isEqualTo(3);
                assertThat(progress.number("$.gold")).as("라운드 %d", round).isEqualTo(18);
                assertThat(progress.number("$.nodes.length()")).as("라운드 %d", round).isEqualTo(1);
                assertThat(progress.number("$.nodes[0].rank")).as("라운드 %d", round).isEqualTo(1);

                // sync_log: seq마다 반영(APPLIED)은 정확히 1건이고, 늦게 온 쪽의 3건은 건너뜀(SKIPPED)이다
                // 같은 seq에 로그가 2줄씩(A의 것, B의 것) 있고, 그중 한 줄은 APPLIED, 다른 한 줄은 SKIPPED인지를 확인하는 것
                for (long seq = 1; seq <= 3; seq++) {
                    assertThat(countOf(origin, seq, SyncLogResult.APPLIED)).as("라운드 %d, seq %d", round, seq).isEqualTo(1);
                    assertThat(countOf(origin, seq, SyncLogResult.SKIPPED)).as("라운드 %d, seq %d", round, seq).isEqualTo(1);
                }
                assertThat(results(origin)).as("라운드 %d", round).hasSize(6);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 시나리오 6b(동시 요청: 겹치는 범위): 한 기기는 seq 1~3, 다른 기기는 seq 1~5를 동시에 보낸다.
     * 서버는 요청을 한 번에 하나씩 처리하므로, 어느 쪽이 먼저든 최종 결과는 같다. 1~3이 먼저면 1~5 요청이 4, 5만 반영하고,
     * 1~5가 먼저면 1~3 요청은 모두 건너뛴다. 어느 경우든 반영(APPLIED)은 seq마다 정확히 1건이고 이중 적용이 없다.
     * 한 번 통과로는 우연일 수 있어서 라운드마다 새 계정으로 15번 반복한다.
     */
    @Test
    void overlappingRangesSentAtOnceEndInTheSameState() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 같은 계정에 로그인한 두 기기(자격증명만 같고 토큰은 따로 받는다)
            for (int round = 0; round < 15; round++) {
                // deviceA와 deviceB는 보내는 일만 하고, origin은 같은 계정의 결과를 읽는 관찰자
                FakeClient origin = startedClient();        // 새 계정을 만들고 로그인 (계정 X)
                FakeClient deviceA = origin.anotherDevice(); // 계정 X의 자격증명을 복사한 다른 기기
                FakeClient deviceB = origin.anotherDevice(); // 계정 X의 자격증명을 복사한 또 다른 기기
                deviceA.start();
                deviceB.start();

                // 한 기기는 seq 1~3, 다른 기기는 seq 1~5를 동시에 보낸다
                List<FakeClient.Response> responses = runTogether(pool,
                        () -> deviceA.sync(SyncSamples.logs(1, 3)),
                        () -> deviceB.sync(SyncSamples.logs(1, 5)));

                // 둘 다 정상 응답이고 거부된 로그는 없다. 1~3 요청의 appliedThroughSeq는 처리 순서에 따라 3 또는 5, 1~5 요청은 항상 5다
                FakeClient.Response partial = responses.get(0);
                FakeClient.Response full = responses.get(1);

                assertThat(partial.status()).as("라운드 %d", round).isEqualTo(200);
                assertThat(partial.<Object>read(REJECTED)).as("라운드 %d", round).isNull();
                assertThat(partial.number(APPLIED_THROUGH_SEQ)).as("라운드 %d", round).isIn(3L, 5L);
                assertThat(full.status()).as("라운드 %d", round).isEqualTo(200);
                assertThat(full.<Object>read(REJECTED)).as("라운드 %d", round).isNull();
                assertThat(full.number(APPLIED_THROUGH_SEQ)).as("라운드 %d", round).isEqualTo(5);

                // 최종 진행도: 어느 순서든 seq 1~5가 한 번씩만 반영된 값이다(Gold 18 + 결산 18 = 36, timer-01 Rank 1)
                FakeClient.Response progress = origin.loadProgress().expect(200);

                assertThat(progress.number("$.lastAppliedSeq")).as("라운드 %d", round).isEqualTo(5);
                assertThat(progress.number("$.gold")).as("라운드 %d", round).isEqualTo(36);
                assertThat(progress.number("$.nodes.length()")).as("라운드 %d", round).isEqualTo(1);
                assertThat(progress.number("$.nodes[0].rank")).as("라운드 %d", round).isEqualTo(1);

                // A가 먼저일 때:  A(1~3) APPLIED×3  →  B(1~5) SKIPPED×3(1,2,3) + APPLIED×2(4,5)
                // B가 먼저일 때:  B(1~5) APPLIED×5  →  A(1~3) SKIPPED×3(1,2,3)
                // sync_log: seq 1~5마다 반영(APPLIED)은 정확히 1건이고, 건너뜀(SKIPPED)은 겹친 seq 1~3에 한 번씩 총 3건이다
                for (long seq = 1; seq <= 5; seq++) {
                    assertThat(countOf(origin, seq, SyncLogResult.APPLIED)).as("라운드 %d, seq %d", round, seq).isEqualTo(1);
                }
                for (long seq = 1; seq <= 3; seq++) {
                    assertThat(countOf(origin, seq, SyncLogResult.SKIPPED)).as("라운드 %d, seq %d", round, seq).isEqualTo(1);
                }
                assertThat(results(origin)).as("라운드 %d", round).hasSize(8);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 시나리오 7(인증과 동기화): 액세스 토큰이 만료된 채로 동기화를 보내면 401 UNAUTHORIZED로 거절되고, 클라이언트가 토큰을 갱신해
     * 같은 요청을 다시 보내 한 번만 반영된다. 401은 인증 단계에서 막혀 동기화 엔진까지 가지 않으므로, 거절된 첫 요청의 로그는 처리되지 않았다.
     * 시계(MutableClock)를 앞당겨 토큰 만료를 기다리지 않고 재현한다(액세스 토큰은 15분).
     */
    @Test
    void aSyncWithAnExpiredAccessTokenIsRefreshedAndAppliedOnlyOnce() {
        FakeClient client = startedClient();

        // 액세스 토큰이 만료되도록 시계를 16분 앞당긴다(리프레시 토큰은 아직 유효하다)
        clock.advance(Duration.ofMinutes(16));
        client.clearLog();

        FakeClient.Response response = client.sync(SyncSamples.logs(1, 3)).expect(200);

        // 요청 기록: 만료된 토큰으로 401, 토큰 갱신, 같은 요청 재시도 성공
        // 클라이언트가 보낸 요청의 기록을 확인하는 줄
        assertThat(client.log()).containsExactly(
                "POST /me/sync -> 401", "POST /auth/refresh -> 200", "POST /me/sync -> 200"
        );

        // 재시도한 요청이 정상 반영된다
        assertThat(response.number(APPLIED_THROUGH_SEQ)).isEqualTo(3);
        assertThat(response.<Object>read(REJECTED)).isNull();
        assertThat(response.number(PROGRESS_GOLD)).isEqualTo(18);
        assertThat(response.number(PROGRESS_NODES_LENGTH)).isEqualTo(1);

        // 401로 막힌 첫 요청은 동기화 엔진까지 가지 않아 sync_log에 남지 않고, 재시도한 seq 1~3이 반영으로 한 번씩만 기록된다
        assertThat(results(client)).containsOnly(SyncLogResult.APPLIED).hasSize(3);
    }

    /**
     * 시나리오 7(토큰 없는 요청): 액세스 토큰 없이 동기화를 보내면 401이고, 공통 오류 형식(UNAUTHORIZED)과 WWW-Authenticate: Bearer 헤더가 온다.
     * 토큰이 없으니 클라이언트의 자동 갱신(call)을 거치지 않고 sendRaw로 그대로 보낸다.
     */
    @Test
    void aSyncWithoutAnAccessTokenIsUnauthorized() {
        FakeClient client = startedClient();

        // 토큰 없이 동기화를 보냈을 때 서버가 401로 막는지
        FakeClient.Response response = client.sendRaw("POST", "/me/sync", "{\"logs\":[]}",
                "Content-Type", "application/json"
        );

        // 상태 코드가 401
        // 에러 코드($.error.code)가 UNAUTHORIZED
        // WWW-Authenticate 헤더가 Bearer
        // Content-Type이 application/json으로 시작 일 때 테스트 통과
        assertThat(response.status()).isEqualTo(401);
        assertThat(response.errorCode()).isEqualTo("UNAUTHORIZED");
        assertThat(response.header("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(response.header("Content-Type")).startsWith("application/json");

        // 토큰이 없어서 요청이 엔진까지 가지 않았으므로 아무것도 기록되지 않았다
        assertThat(results(client)).isEmpty();
    }

    /**
     * 시나리오 8(콘텐츠 버전): 한 로그의 contentVersion만 서버와 다르면 그 로그를 CONTENT_VERSION_MISMATCH로 거부하고 멈춘다.
     * 클라이언트와 서버의 콘텐츠가 달라진 상태에서 계산한 로그를 믿지 않으려는 것이다. 앞의 로그는 이미 반영되어 그대로 남고,
     * 뒤의 로그는 처리하지 않는다. 서버 버전과 같은 원본 로그로 다시 보내면 거부된 번호도 정상 반영된다.
     */
    @Test
    void aLogWithAnotherContentVersionIsRejectedAndTheEarlierLogsStay() {
        FakeClient client = startedClient();
        String[] sample = SyncSamples.logs(1, 3);

        // seq 2(결산)의 contentVersion만 서버의 것(4d6c398ad1f5e49c)과 다르게 바꾼다. 형식은 맞는 16자리 16진수다
        String otherVersion = SyncSamples.withLogField(sample[1], "contentVersion", "0000000000000000");

        FakeClient.Response rejected = client.sync(sample[0], otherVersion, sample[2]).expect(200);

        // seq 1은 반영되었고, seq 2는 버전 불일치로 거부되며, 진행도는 seq 1까지의 상태다
        assertThat(rejected.number(APPLIED_THROUGH_SEQ)).isEqualTo(1);
        assertThat(rejected.number(REJECTED + ".seq")).isEqualTo(2);
        assertThat(rejected.text(REJECTED + ".type")).isEqualTo(SETTLEMENT);
        assertThat(rejected.text(REJECTED + ".code")).isEqualTo(CONTENT_VERSION_MISMATCH);
        assertThat(rejected.number(PROGRESS_LAST_APPLIED_SEQ)).isEqualTo(1);
        assertThat(rejected.number(PROGRESS_GOLD)).isEqualTo(0);

        // seq 1은 반영, seq 2는 거부(CONTENT_VERSION_MISMATCH), seq 3은 처리하지 않았다(NOT_PROCESSED)
        assertThat(results(client)).containsExactly(
                SyncLogResult.APPLIED, SyncLogResult.REJECTED, SyncLogResult.NOT_PROCESSED
        );

        // 서버 버전과 같은 원본 seq 2부터 다시 보낸다. 거부된 번호를 다시 써도 이번에는 정상 반영된다
        FakeClient.Response resent = client.sync(SyncSamples.logs(2, 3)).expect(200);

        assertThat(resent.number(APPLIED_THROUGH_SEQ)).isEqualTo(3);
        assertThat(resent.<Object>read(REJECTED)).isNull();
        assertThat(resent.number(PROGRESS_GOLD)).isEqualTo(18);

        // 처음 요청의 기록(반영, 거부, 처리 안 함)에 이어, 다시 보낸 seq 2, 3이 반영으로 기록된다
        assertThat(results(client)).containsExactly(
                SyncLogResult.APPLIED, SyncLogResult.REJECTED, SyncLogResult.NOT_PROCESSED,
                SyncLogResult.APPLIED, SyncLogResult.APPLIED
        );
    }

    /**
     * 시나리오 9(빈 요청): 로그가 없는 요청은 아무것도 처리하지 않고 현재 진행도만 돌려준다. 클라이언트가 서버가 어디까지 반영했는지
     * 알아볼 때 쓴다(appliedThroughSeq가 서버의 lastAppliedSeq와 같다). 새 계정이든 이미 반영한 계정이든 진행도는 바뀌지 않는다.
     */
    @Test
    void anEmptyRequestReturnsOnlyTheProgress() {
        FakeClient client = startedClient();

        // 새 계정: 반영한 로그가 없어서 appliedThroughSeq와 lastAppliedSeq가 모두 0이고, 진행도는 처음 상태다
        FakeClient.Response fresh = client.syncRaw(SyncSamples.emptyRequest()).expect(200);

        assertThat(fresh.number(APPLIED_THROUGH_SEQ)).isEqualTo(0);
        assertThat(fresh.<Object>read(REJECTED)).isNull();
        assertThat(fresh.text("$.contentVersion")).isEqualTo(SAMPLE_CONTENT_VERSION);
        assertThat(fresh.number(PROGRESS_LAST_APPLIED_SEQ)).isEqualTo(0);
        assertThat(fresh.number(PROGRESS_GOLD)).isEqualTo(0);
        assertThat(fresh.<List<Object>>read(PROGRESS_NODES)).isEmpty();
        assertThat(results(client)).isEmpty();

        // seq 1~3을 반영한 뒤: 빈 요청이 현재 진행도(Gold 18, timer-01 Rank 1)를 그대로 돌려주고, appliedThroughSeq는 3이다
        // ① 로그 1~3을 보낸다           → 서버 상태가 바뀜 (Gold 18, 노드 1개, seq 3)
        client.sync(SyncSamples.logs(1, 3)).expect(200);

        // ② 빈 요청을 보낸다 (applied)  → 서버가 "지금 상태"를 응답에 담아 줌 → applied의 값이 ①에서 만든 상태와 같은지 확인
        FakeClient.Response applied = client.syncRaw(SyncSamples.emptyRequest()).expect(200);

        assertThat(applied.number(APPLIED_THROUGH_SEQ)).isEqualTo(3);
        assertThat(applied.<Object>read(REJECTED)).isNull();
        assertThat(applied.number(PROGRESS_LAST_APPLIED_SEQ)).isEqualTo(3);
        assertThat(applied.number(PROGRESS_GOLD)).isEqualTo(18);
        assertThat(applied.number(PROGRESS_NODES_LENGTH)).isEqualTo(1);

        // 빈 요청은 sync_log에 아무것도 더하지 않는다(앞의 반영 3건뿐이다)
        assertThat(results(client)).containsOnly(SyncLogResult.APPLIED).hasSize(3);
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

    // 두 작업을 거의 동시에 시작해, 각 결과를 작업을 넘긴 순서대로 돌려준다
    private static <T> List<T> runTogether(ExecutorService pool, Callable<T> first, Callable<T> second)
            throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        Future<T> a = pool.submit(() -> {
            go.await();
            return first.call();
        });
        Future<T> b = pool.submit(() -> {
            go.await();
            return second.call();
        });
        go.countDown();
        List<T> results = new ArrayList<>();
        results.add(a.get());
        results.add(b.get());

        return results;
    }

    // 이 계정의 sync_log에서 seq에 대한 기록 중 처리 결과가 result인 것의 수
    private long countOf(FakeClient client, long seq, SyncLogResult result) {
        return logRepository.findAllByAccountIdOrderByIdAsc(accountId(client)).stream()
                .filter(record -> record.getSeq() == seq && record.getResult() == result)
                .count();
    }

}
