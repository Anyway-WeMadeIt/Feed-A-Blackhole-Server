package org.example.feedablackhole.e2e;

import org.example.feedablackhole.TestcontainersConfiguration;
import org.example.feedablackhole.support.FakeClient;
import org.example.feedablackhole.support.MutableClock;
import org.example.feedablackhole.support.SyncSamples;
import org.example.feedablackhole.sync.entity.SyncLogRecord;
import org.example.feedablackhole.sync.entity.SyncLogResult;
import org.example.feedablackhole.sync.repository.SyncLogRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.util.List;

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
        assertThat(response.number("$.appliedThroughSeq")).isEqualTo(6);
        assertThat(response.<Object>read("$.rejected")).isNull();

        // 진행도: 마지막 로그가 새 게임이므로 Gold 0, 성장도 0, 산 노드 없음. 반영한 마지막 번호만 6이다
        assertThat(response.number("$.progress.lastAppliedSeq")).isEqualTo(6);
        assertThat(response.number("$.progress.gold")).isEqualTo(0);
        assertThat(response.number("$.progress.growthStage")).isEqualTo(0);
        assertThat(response.<List<Object>>read("$.progress.nodes")).isEmpty();

        // sync_log에는 6건 모두 반영(APPLIED)으로 기록된다
        assertThat(results(client)).containsOnly(SyncLogResult.APPLIED).hasSize(6);
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
        long accountId = client.call("GET", "/me", null).number("$.accountId");

        return logRepository.findAllByAccountIdOrderByIdAsc(accountId)
                .stream().map(SyncLogRecord::getResult).toList();
    }

}
