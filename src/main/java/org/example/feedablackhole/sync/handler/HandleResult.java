package org.example.feedablackhole.sync.handler;

import java.util.Objects;
import org.example.feedablackhole.sync.log.RejectCode;

/**
 * 처리기 하나의 결과: 반영했거나, 거부했다.
 */
public sealed interface HandleResult {

    static HandleResult applied() {
        return Applied.INSTANCE;
    }

    /**
     * @param message 사람이 읽는 설명(응답의 rejected.message). 형식은 정하지 않는다
     */
    static HandleResult rejected(RejectCode code, String message) {
        return new Rejected(code, message);
    }

    /** 반영했다. */
    final class Applied implements HandleResult {

        private static final Applied INSTANCE = new Applied();

        private Applied() {
        }

    }

    /** 거부했다. 진행도는 바뀌지 않았다. */
    record Rejected(RejectCode code, String message) implements HandleResult {

        public Rejected {
            Objects.requireNonNull(code, "code");
            message = message == null ? "" : message;
        }

    }

}
