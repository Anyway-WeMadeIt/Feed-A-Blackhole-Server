package org.example.feedablackhole.sync.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.example.feedablackhole.progress.entity.PlayerProgress;
import org.example.feedablackhole.progress.repository.PlayerNodeRankRepository;
import org.example.feedablackhole.progress.repository.PlayerProgressRepository;
import org.example.feedablackhole.sync.dto.RejectedLogResponse;
import org.example.feedablackhole.sync.dto.SyncProgressResponse;
import org.example.feedablackhole.sync.dto.SyncResponse;
import org.example.feedablackhole.sync.entity.SyncLogRecord;
import org.example.feedablackhole.sync.entity.SyncLogResult;
import org.example.feedablackhole.sync.handler.HandleResult;
import org.example.feedablackhole.sync.handler.LogHandler;
import org.example.feedablackhole.sync.handler.SyncContext;
import org.example.feedablackhole.sync.log.RejectCode;
import org.example.feedablackhole.sync.repository.BattleRunRepository;
import org.example.feedablackhole.sync.repository.SyncLogBatchWriter;
import org.example.feedablackhole.sync.service.LogParser.ParsedLog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 동기화 엔진(docs/server-sync-contracts.md 4.1, API 명세 "적용 규칙").
 *
 * <p>계정 진행도 행을 잠그고 로그를 seq 순서대로 처리한다. 로그 하나마다:
 * 이미 반영됨 → 건너뜀, 누락 → SEQ_GAP, 형식 → MALFORMED_LOG, 콘텐츠 버전 → CONTENT_VERSION_MISMATCH, 그다음 타입별 처리기.
 * 거부되면 그 로그에서 멈추고, 그때까지 반영한 것은 커밋한다. 예상하지 못한 예외는 요청 전체를 롤백한다(500).
 * 받은 로그는 결과와 함께 sync_log에 남긴다. 응답에는 항상 서버 진행도가 담긴다.
 */
@Service
@RequiredArgsConstructor
public class SyncService {

    private final PlayerProgressRepository progressRepository;
    private final PlayerNodeRankRepository nodeRankRepository;
    private final BattleRunRepository runRepository;
    private final SyncLogBatchWriter logWriter;
    private final LogParser parser;
    private final LogHandlerRegistry handlers;
    private final ServerContentVersion contentVersion;
    private final Clock clock;

    @Transactional
    public SyncResponse sync(long accountId, List<IncomingLog> logs) {
        PlayerProgress progress = progressRepository.findForUpdateByAccountId(accountId)
                .orElseThrow(() -> new IllegalStateException("진행 상태가 없는 계정입니다. accountId=" + accountId));
        JpaSyncContext context = new JpaSyncContext(progress,
                nodeRankRepository.findAllByAccountIdOrderByIdAsc(accountId), nodeRankRepository, runRepository);
        String serverVersion = contentVersion.current();
        Instant receivedAt = clock.instant();

        List<SyncLogRecord> records = new ArrayList<>(logs.size());
        RejectedLogResponse rejected = null;

        for (IncomingLog log : logs) {
            ParsedLog parsed = parser.parse(log);

            // 한 번 거절된 로그가 나오면 그 뒤로는 처리 안함
            if (rejected != null) {
                records.add(record(progress, parsed, log, SyncLogResult.NOT_PROCESSED, null, receivedAt));
                continue;
            }
            // 이미 처리한 로그는 건너뜀
            if (log.seq() <= progress.getLastAppliedSeq()) {
                records.add(record(progress, parsed, log, SyncLogResult.SKIPPED, null, receivedAt));
                continue;
            }

            HandleResult result = check(parsed, progress.getLastAppliedSeq(), serverVersion); // 엔진 검사 (형식 검증)
            if (result == null) {
                result = handle(parsed, context); // 핸들러 검사 (내용 검증)
            }

            if (result instanceof HandleResult.Rejected(RejectCode code, String message)) {
                records.add(record(progress, parsed, log, SyncLogResult.REJECTED, code, receivedAt));
                rejected = new RejectedLogResponse(log.seq(), typeText(parsed), code.name(), message);
            } else {
                // 진행도 갱신
                progress.markApplied(log.seq());
                records.add(record(progress, parsed, log, SyncLogResult.APPLIED, null, receivedAt));
            }
        }

        logWriter.insertAll(progress.getAccountId(), records);
        progressRepository.flush(); // flush 해야 auditing이 일어나 엔티티의 updateAt이 갱신됨
        // 그래야 응답을 조립할 때 올바른 updateAt을 읽을 수 있음

        return new SyncResponse(progress.getLastAppliedSeq(), rejected, serverVersion,
                SyncProgressResponse.of(progress, context.nodeEntities()));
    }

    // 엔진이 거부하는 경우(처리기 전). 통과하면 null.
    private static HandleResult check(ParsedLog parsed, long lastAppliedSeq, String serverVersion) {
        if (parsed.seq() > lastAppliedSeq + 1) {
            return HandleResult.rejected(RejectCode.SEQ_GAP,
                    "seq " + parsed.seq() + " 앞의 로그가 없습니다. 다음 seq는 " + (lastAppliedSeq + 1) + "입니다.");
        }
        if (parsed.malformed()) {
            return HandleResult.rejected(RejectCode.MALFORMED_LOG, parsed.error());
        }
        if (!serverVersion.equals(parsed.contentVersion())) {
            return HandleResult.rejected(RejectCode.CONTENT_VERSION_MISMATCH,
                    "로그의 콘텐츠 버전 " + parsed.contentVersion() + "이 서버 " + serverVersion + "과 다릅니다.");
        }
        return null;
    }

    private HandleResult handle(ParsedLog parsed, JpaSyncContext context) {
        LogHandler<?> handler = handlers.find(parsed.type())
                .orElseThrow(() -> new IllegalStateException("로그 타입 " + parsed.type() + "의 처리기가 없다."));
        context.begin(parsed.seq());
        int before = context.changes();
        HandleResult result = invoke(handler, parsed.payload(), context);
        if (result == null) {
            throw new IllegalStateException(parsed.type() + " 처리기가 결과를 돌려주지 않았다.");
        }
        if (result instanceof HandleResult.Rejected && context.changes() != before) {
            throw new IllegalStateException(parsed.type() + " 처리기가 거부하면서 진행도를 바꿨다. seq=" + parsed.seq());
        }
        return result;
    }

    // LogHandler<?>의 payload 타입 P를 포착해, payload를 그 타입으로 확인한 뒤 넘긴다.
    // 등록소가 시작 때 payloadType()을 계약의 타입과 맞춰 두었으므로 정상이면 cast는 실패하지 않는다. 어긋나면 여기서 바로 드러난다.
    private static <P> HandleResult invoke(LogHandler<P> handler, Object payload, SyncContext context) {
        return handler.handle(handler.payloadType().cast(payload), context);
    }

    private static SyncLogRecord record(PlayerProgress progress, ParsedLog parsed, IncomingLog log,
            SyncLogResult result, RejectCode code, Instant receivedAt) {
        return SyncLogRecord.of(progress.getAccount(), log.seq(), parsed.typeText(), result, code,
                parsed.contentVersion(), parsed.buildVersion(), log.raw().toString(), receivedAt);
    }

    private static String typeText(ParsedLog parsed) {
        return parsed.typeText() == null || parsed.typeText().length() > SyncLogRecord.TYPE_LENGTH
                ? "" : parsed.typeText();
    }

}
