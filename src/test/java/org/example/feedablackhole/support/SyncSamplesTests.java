package org.example.feedablackhole.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

/**
 * SyncSamples가 샘플 요청의 로그를 seq로 잘라 주고, 필드 하나만 바꾼 로그를 만드는지 확인한다.
 */
class SyncSamplesTests {

    // 샘플 요청에는 로그 6개가 있다
    @Test
    void requestHasAllSixLogs() {
        assertThat(SyncSamples.request()).hasSize(6);
    }

    // seq 범위로 자른다(양 끝 포함, seq 순서)
    @Test
    void logsAreSlicedBySeq() {
        String[] firstThree = SyncSamples.logs(1, 3);

        assertThat(firstThree).hasSize(3);
        assertThat(JsonPath.<Integer>read(firstThree[0], "$.seq")).isEqualTo(1);
        assertThat(JsonPath.<String>read(firstThree[1], "$.type")).isEqualTo("SETTLEMENT");
        assertThat(JsonPath.<String>read(firstThree[2], "$.type")).isEqualTo("NODE_PURCHASE");
    }

    // 하나만 필요하면 같은 seq로 자른다
    @Test
    void aSingleLogCanBeTaken() {
        String[] one = SyncSamples.logs(4, 4);

        assertThat(one).hasSize(1);
        assertThat(JsonPath.<String>read(one[0], "$.type")).isEqualTo("RUN_START");
    }

    // 같은 로그는 몇 번을 꺼내도 같은 글자다(재전송해도 같은 글자여야 한다)
    @Test
    void theSameLogIsAlwaysTheSameText() {
        assertThat(SyncSamples.logs(3, 3)).isEqualTo(SyncSamples.logs(3, 3));
    }

    // 필드 하나만 바꾼 로그는 나머지를 그대로 둔다
    @Test
    void changingOneFieldKeepsTheRest() {
        String original = SyncSamples.logs(1, 1)[0];

        String changedPayload = SyncSamples.withPayloadField(original, "startGold", 5);
        String changedLog = SyncSamples.withLogField(original, "contentVersion", "wrong");

        assertThat(JsonPath.<Integer>read(changedPayload, "$.payload.startGold")).isEqualTo(5);
        assertThat(JsonPath.<String>read(changedPayload, "$.payload.runId"))
                .isEqualTo(JsonPath.<String>read(original, "$.payload.runId"));
        assertThat(JsonPath.<String>read(changedLog, "$.contentVersion")).isEqualTo("wrong");
        assertThat(JsonPath.<Integer>read(changedLog, "$.seq")).isEqualTo(1);
    }

}
