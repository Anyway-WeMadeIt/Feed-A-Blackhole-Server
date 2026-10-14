package org.example.feedablackhole.sync.handler;

import static org.assertj.core.api.Assertions.assertThat;

import org.example.feedablackhole.battle.content.ContentBundle;
import org.example.feedablackhole.battle.content.ContentBundleLoader;
import org.example.feedablackhole.node.content.NodeTree;
import org.example.feedablackhole.sync.log.LogType;
import org.example.feedablackhole.sync.log.RejectCode;
import org.example.feedablackhole.sync.log.payload.NodePurchasePayload;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * NODE_PURCHASE 처리기가 노드 구매 규칙(최대 Rank, 노출, Gold, rank·cost 일치)을 검사하는지 확인하는 테스트.
 * 노드 트리는 샘플 콘텐츠 묶음(src/test/resources/content-sample)에서 읽고, 진행도는 가짜 SyncContext로 만든다.
 * 샘플의 timer-01은 Rank 1 하나뿐이고 비용 2, 시작 노드다.
 */
class NodePurchaseHandlerTests {

    // 클래스패스 기준 샘플 묶음 위치(src/test/resources 아래)
    private static final String SAMPLE = "/content-sample/";

    private static NodeTree tree;
    private static NodePurchaseHandler handler;

    // 샘플 묶음의 노드 트리로 처리기를 만든다(스프링 없이 직접 생성)
    @BeforeAll
    static void load() {
        ContentBundle bundle = ContentBundleLoader.load(NodePurchaseHandlerTests::bytes);
        tree = bundle.nodeTree();
        handler = new NodePurchaseHandler(tree);
    }

    // 엔진이 NODE_PURCHASE 로그를 이 처리기로 보내려면 타입과 payload 타입이 계약과 같아야 한다
    @Test
    void handlesTheNodePurchaseLogType() {
        assertThat(handler.type()).isEqualTo(LogType.NODE_PURCHASE);
        assertThat(handler.payloadType()).isEqualTo(NodePurchasePayload.class);
    }

    // 서버 콘텐츠에 없는 노드는 UNKNOWN_NODE로 거부하고, 진행도를 바꾸지 않는다
    @Test
    void unknownNodeIsRejected() {
        FakeSyncContext context = new FakeSyncContext().withGold(100);

        HandleResult result = handler.handle(new NodePurchasePayload("no-such-node", 1, 2L), context);

        assertThat(rejectCode(result)).isEqualTo(RejectCode.UNKNOWN_NODE);
        assertThat(context.changes()).isZero();
        assertThat(context.gold()).isEqualTo(100);
    }

    // 이미 최대 Rank까지 산 노드는 MAX_RANK_REACHED로 거부한다(timer-01은 Rank 1이 최대)
    @Test
    void maxRankNodeIsRejected() {
        FakeSyncContext context = new FakeSyncContext().withGold(100).withNode("timer-01", 1);

        HandleResult result = handler.handle(new NodePurchasePayload("timer-01", 2, 2L), context);

        assertThat(rejectCode(result)).isEqualTo(RejectCode.MAX_RANK_REACHED);
        assertThat(context.changes()).isZero();
        assertThat(context.gold()).isEqualTo(100);
    }

    // 노드가 없는 것이 최대 Rank 확인보다 먼저 걸린다(순서: 존재 -> 최대 Rank)
    @Test
    void unknownNodeIsCheckedBeforeMaxRank() {
        FakeSyncContext context = new FakeSyncContext().withNode("no-such-node", 5);

        HandleResult result = handler.handle(new NodePurchasePayload("no-such-node", 6, 1L), context);

        assertThat(rejectCode(result)).isEqualTo(RejectCode.UNKNOWN_NODE);
    }

    // 거부 결과에서 거부 코드를 꺼낸다. 거부가 아니면 null
    private static RejectCode rejectCode(HandleResult result) {
        return result instanceof HandleResult.Rejected rejected ? rejected.code() : null;
    }

    // 샘플 묶음 폴더에서 파일 바이트를 읽는다. 없으면 null
    private static byte[] bytes(String path) {
        try (InputStream in = NodePurchaseHandlerTests.class.getResourceAsStream(SAMPLE + path)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // 아직 드러나지 않은 노드(시작 노드가 아니고 산 노드와 이어지지 않음)는 NODE_HIDDEN으로 거부한다
    @Test
    void hiddenNodeIsRejected() {
        FakeSyncContext context = new FakeSyncContext().withGold(1_000_000_000_000_000L);

        HandleResult result = handler.handle(new NodePurchasePayload("timer-02", 1, 2_600_000_000_000L), context);

        assertThat(rejectCode(result)).isEqualTo(RejectCode.NODE_HIDDEN);
        assertThat(context.changes()).isZero();
    }

    // 시작 노드는 처음부터 드러나 있다: Gold가 없어서 거부되지, 숨겨져서 거부되지 않는다
    @Test
    void startNodeIsRevealedFromTheBeginning() {
        FakeSyncContext context = new FakeSyncContext();

        HandleResult result = handler.handle(new NodePurchasePayload("timer-01", 1, 2L), context);

        assertThat(rejectCode(result)).isEqualTo(RejectCode.NOT_ENOUGH_GOLD);
    }

    // 산 노드와 선으로 이어진 노드는 드러난다(timer-01을 산 뒤 breaker.damage-01, 비용 6)
    @Test
    void aNodeNextToAnOwnedNodeIsRevealed() {
        FakeSyncContext context = new FakeSyncContext().withNode("timer-01", 1);

        HandleResult result = handler.handle(new NodePurchasePayload("breaker.damage-01", 1, 6L), context);

        assertThat(rejectCode(result)).isEqualTo(RejectCode.NOT_ENOUGH_GOLD);
    }

    // Gold가 다음 Rank 비용(timer-01은 2)보다 적으면 NOT_ENOUGH_GOLD로 거부하고, 진행도를 바꾸지 않는다
    @Test
    void notEnoughGoldIsRejected() {
        FakeSyncContext context = new FakeSyncContext().withGold(1);

        HandleResult result = handler.handle(new NodePurchasePayload("timer-01", 1, 2L), context);

        assertThat(rejectCode(result)).isEqualTo(RejectCode.NOT_ENOUGH_GOLD);
        assertThat(context.changes()).isZero();
        assertThat(context.gold()).isEqualTo(1);
    }

    // 숨겨진 것이 Gold 부족보다 먼저 걸린다(순서: 존재 -> 최대 Rank -> 노출 -> Gold)
    @Test
    void hiddenIsCheckedBeforeGold() {
        FakeSyncContext context = new FakeSyncContext().withGold(0);

        HandleResult result = handler.handle(new NodePurchasePayload("timer-02", 1, 2_600_000_000_000L), context);

        assertThat(rejectCode(result)).isEqualTo(RejectCode.NODE_HIDDEN);
    }
}
