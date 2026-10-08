package org.example.feedablackhole.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.example.feedablackhole.TestcontainersConfiguration;
import org.example.feedablackhole.support.FakeClient;
import org.example.feedablackhole.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * 1단계(인증, 진행 데이터)를 Unity 클라이언트 없이 검증한다.
 * 서버를 실제 포트로 띄우고, FakeClient가 명세(docs/api-spec.md)대로 요청을 조립해 실제 HTTP로 보낸다.
 * MockMvc 테스트와 달리 테스트 전체가 하나의 트랜잭션으로 묶이지 않아, 실제 커밋과 동시 요청까지 확인한다.
 * 시계는 테스트가 앞당길 수 있는 MutableClock으로 바꿔서 토큰 만료를 기다리지 않고 재현한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, Stage1ClientFlowTests.TestClockConfig.class})
class Stage1ClientFlowTests {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfig {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }

    }

    @LocalServerPort
    private int port;

    @Autowired
    private MutableClock clock;

    // ---------- 최초 실행 ----------

    @Test
    void firstLaunchRegistersLogsInAndLoadsTheInitialProgress() {
        FakeClient client = newClient();

        client.start();
        FakeClient.Response progress = client.loadProgress().expect(200);

        assertThat(client.log()).containsExactly(
                "POST /auth/guest/register -> 201",
                "POST /auth/guest/login -> 200",
                "GET /me/progress -> 200");
        assertThat(progress.number("$.revision")).isZero();
        assertThat(progress.number("$.gold")).isZero();
        assertThat(progress.number("$.growthStage")).isZero();
        assertThat(progress.<List<Object>>read("$.nodes")).isEmpty();
        assertThat(progress.text("$.updatedAt")).isNotBlank();
    }

    // ---------- 플레이 중 저장 ----------

    @Test
    void savesChainByRevisionAndTheLoadedStateMatchesTheLastSave() {
        FakeClient client = startedClient();

        client.saveProgress(1000, 0).expect(200);
        assertThat(client.revision()).isEqualTo(1);
        client.saveProgress(998, 0, "timer-01:1").expect(200);
        assertThat(client.revision()).isEqualTo(2);
        client.saveProgress(500, 1, "timer-01:1", "timer-02:1").expect(200);
        client.saveProgress(500, 1, "timer-01:2", "timer-02:1", "timer-03:1").expect(200);
        assertThat(client.revision()).isEqualTo(4);

        FakeClient.Response loaded = client.loadProgress().expect(200);
        assertThat(loaded.number("$.revision")).isEqualTo(4);
        assertThat(loaded.number("$.gold")).isEqualTo(500);
        assertThat(loaded.number("$.growthStage")).isEqualTo(1);
        assertThat(loaded.<List<String>>read("$.nodes[*].nodeId")).containsExactly("timer-01", "timer-02", "timer-03");
        assertThat(loaded.<List<Integer>>read("$.nodes[*].rank")).containsExactly(2, 1, 1);
    }

    // ---------- 앱 재시작 ----------

    @Test
    void restartedAppRefreshesFirstAndFindsTheSavedProgress() {
        FakeClient first = startedClient();
        first.saveProgress(777, 2, "timer-01:1").expect(200);
        String oldRefreshToken = first.refreshToken();

        FakeClient second = first.restartedApp();
        second.start();

        assertThat(second.log()).containsExactly("POST /auth/refresh -> 200");
        assertThat(second.refreshToken()).isNotEqualTo(oldRefreshToken);
        FakeClient.Response loaded = second.loadProgress().expect(200);
        assertThat(loaded.number("$.gold")).isEqualTo(777);
        assertThat(loaded.<List<String>>read("$.nodes[*].nodeId")).containsExactly("timer-01");
    }

    @Test
    void usedRefreshTokenFallsBackToLoginAndReuseRevokesTheOtherTokens() {
        FakeClient first = startedClient();
        first.saveProgress(777, 2, "timer-01:1").expect(200);
        FakeClient second = first.restartedApp();
        second.start();

        // 앱이 새 토큰을 저장하기 전에 꺼져서 옛(이미 쓴) 리프레시 토큰이 남은 경우
        FakeClient staleApp = first.restartedApp();
        staleApp.start();
        assertThat(staleApp.log()).containsExactly("POST /auth/refresh -> 401", "POST /auth/guest/login -> 200");
        assertThat(staleApp.loadProgress().expect(200).number("$.gold")).isEqualTo(777);

        // 재사용 감지로 계정의 리프레시 토큰이 모두 폐기되어, 정상 회전으로 받았던 토큰도 쓸 수 없다. 그래도 로그인으로 복구된다.
        FakeClient third = second.restartedApp();
        third.start();
        assertThat(third.log()).containsExactly("POST /auth/refresh -> 401", "POST /auth/guest/login -> 200");
        assertThat(third.loadProgress().expect(200).number("$.gold")).isEqualTo(777);
    }

    // ---------- 토큰 만료 ----------

    @Test
    void expiredAccessTokenIsRefreshedAndTheSameRequestIsRetried() {
        FakeClient client = startedClient();
        client.loadProgress().expect(200);

        clock.advance(Duration.ofMinutes(14).plusSeconds(59));
        client.clearLog();
        client.loadProgress().expect(200);
        assertThat(client.log()).containsExactly("GET /me/progress -> 200");

        clock.advance(Duration.ofMinutes(2));
        client.clearLog();
        client.loadProgress().expect(200);
        assertThat(client.log()).containsExactly(
                "GET /me/progress -> 401", "POST /auth/refresh -> 200", "GET /me/progress -> 200");
    }

    @Test
    void saveRejectedForAnExpiredTokenIsAppliedExactlyOnceAfterTheRetry() {
        FakeClient client = startedClient();
        client.loadProgress().expect(200);
        long before = client.revision();

        clock.advance(Duration.ofMinutes(16));
        client.clearLog();
        client.saveProgress(321, 0).expect(200);

        assertThat(client.log()).containsExactly(
                "PUT /me/progress -> 401", "POST /auth/refresh -> 200", "PUT /me/progress -> 200");
        FakeClient.Response loaded = client.loadProgress().expect(200);
        assertThat(loaded.number("$.revision")).isEqualTo(before + 1);
        assertThat(loaded.number("$.gold")).isEqualTo(321);
    }

    @Test
    void expiredRefreshTokenFallsBackToLoginAndTheSavedProgressSurvives() {
        FakeClient client = startedClient();
        client.saveProgress(555, 1, "timer-01:1").expect(200);
        FakeClient storedOnDevice = client.restartedApp();

        clock.advance(Duration.ofDays(31));
        storedOnDevice.start();

        assertThat(storedOnDevice.log()).containsExactly("POST /auth/refresh -> 401", "POST /auth/guest/login -> 200");
        assertThat(storedOnDevice.loadProgress().expect(200).number("$.gold")).isEqualTo(555);
    }

    @Test
    void everythingExpiredWhileTheAppIsRunningRecoversThroughLogin() {
        FakeClient client = startedClient();
        client.loadProgress().expect(200);

        clock.advance(Duration.ofDays(31));
        client.clearLog();
        client.loadProgress().expect(200);

        assertThat(client.log()).containsExactly(
                "GET /me/progress -> 401",
                "POST /auth/refresh -> 401",
                "POST /auth/guest/login -> 200",
                "GET /me/progress -> 200");
    }

    // ---------- 새 게임 ----------

    @Test
    void newGameResetsTheProgressAndRejectsSavesBasedOnTheOldRevision() {
        FakeClient client = startedClient();
        client.saveProgress(5000, 2, "timer-01:1").expect(200);
        long beforeReset = client.revision();

        FakeClient.Response reset = client.resetProgress().expect(200);
        assertThat(reset.number("$.revision")).isEqualTo(beforeReset + 1);
        assertThat(reset.number("$.gold")).isZero();
        assertThat(reset.number("$.growthStage")).isZero();
        assertThat(reset.<List<Object>>read("$.nodes")).isEmpty();

        client.saveProgressBasedOn(beforeReset, 5000, 2).expect(409);
        assertThat(client.loadProgress().number("$.gold")).isZero();

        // 초기화된 상태가 기준이므로 낮은 성장도로 새로 저장할 수 있다.
        client.saveProgress(10, 1, "timer-02:1").expect(200);
    }

    // ---------- 기기 두 대 ----------

    @Test
    void staleDeviceGetsAConflictReloadsAndSavesAgain() {
        FakeClient deviceA = startedClient();
        deviceA.loadProgress().expect(200);
        FakeClient deviceB = deviceA.anotherDevice();
        deviceB.start();
        assertThat(deviceB.log()).containsExactly("POST /auth/guest/login -> 200");
        deviceB.loadProgress().expect(200);

        deviceA.saveProgress(100, 0).expect(200);

        FakeClient.Response conflict = deviceB.saveProgress(999, 3).expect(409);
        assertThat(conflict.errorCode()).isEqualTo("STALE_PROGRESS");
        assertThat(deviceB.loadProgress().expect(200).number("$.gold")).isEqualTo(100);
        deviceB.saveProgress(150, 0).expect(200);

        assertThat(deviceA.loadProgress().expect(200).number("$.gold")).isEqualTo(150);
    }

    // ---------- 와이어 계약: 클라이언트가 실제로 보게 되는 응답 ----------

    @Test
    void unauthenticatedRequestsGetTheBearerChallengeInTheCommonErrorFormat() {
        FakeClient client = startedClient();

        FakeClient.Response noToken = client.sendRaw("GET", "/me/progress", null);
        assertThat(noToken.status()).isEqualTo(401);
        assertThat(noToken.errorCode()).isEqualTo("UNAUTHORIZED");
        assertThat(noToken.header("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(noToken.header("Content-Type")).startsWith("application/json");

        assertThat(client.sendRaw("GET", "/me/progress", null, "Authorization", "Bearer not-a-jwt").status())
                .isEqualTo(401);
        // "Bearer " 접두어를 빼먹은 경우
        assertThat(client.sendRaw("GET", "/me/progress", null, "Authorization", client.accessToken()).errorCode())
                .isEqualTo("UNAUTHORIZED");
        // 리프레시 토큰은 액세스 토큰으로 쓸 수 없다.
        assertThat(client.sendRaw("GET", "/me/progress", null, "Authorization", "Bearer " + client.refreshToken())
                .errorCode()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void malformedSaveRequestsGet400InTheCommonErrorFormat() {
        FakeClient client = startedClient();
        String auth = "Bearer " + client.accessToken();

        List<String> badBodies = List.of(
                "{not json",
                "{}",
                "{\"gold\":0}",
                "{\"baseRevision\":0,\"gold\":-1,\"growthStage\":0,\"nodes\":[]}",
                "{\"baseRevision\":0,\"gold\":0,\"growthStage\":-1,\"nodes\":[]}",
                "{\"baseRevision\":-1,\"gold\":0,\"growthStage\":0,\"nodes\":[]}",
                "{\"baseRevision\":0,\"gold\":0,\"growthStage\":0}",
                "{\"baseRevision\":0,\"gold\":0,\"growthStage\":0,\"nodes\":[{\"nodeId\":\"timer-01\",\"rank\":0}]}",
                "{\"baseRevision\":0,\"gold\":0,\"growthStage\":0,\"nodes\":[{\"nodeId\":\"\",\"rank\":1}]}",
                "{\"baseRevision\":0,\"gold\":0,\"growthStage\":0,\"nodes\":[{\"rank\":1}]}",
                "{\"baseRevision\":0,\"gold\":0,\"growthStage\":0,\"nodes\":["
                        + "{\"nodeId\":\"timer-01\",\"rank\":1},{\"nodeId\":\"timer-01\",\"rank\":2}]}",
                // Long 범위를 넘는 Gold
                "{\"baseRevision\":0,\"gold\":9223372036854775808,\"growthStage\":0,\"nodes\":[]}");

        for (String body : badBodies) {
            FakeClient.Response response = client.sendRaw("PUT", "/me/progress", body,
                    "Authorization", auth, "Content-Type", "application/json");
            assertThat(response.status()).as(body).isEqualTo(400);
            assertThat(response.errorCode()).as(body).isEqualTo("INVALID_REQUEST");
        }
        // 어떤 잘못된 요청도 진행 상태를 바꾸지 않았다.
        assertThat(client.loadProgress().number("$.revision")).isZero();
    }

    @Test
    void missingFieldErrorNamesTheField() {
        FakeClient client = startedClient();

        FakeClient.Response response = client.sendRaw("PUT", "/me/progress", "{\"gold\":0}",
                "Authorization", "Bearer " + client.accessToken(), "Content-Type", "application/json");

        assertThat(response.text("$.error.message")).contains("baseRevision");
    }

    @Test
    void wrongContentTypeMethodAndPathUseTheCommonErrorFormat() {
        FakeClient client = startedClient();
        String auth = "Bearer " + client.accessToken();
        String body = FakeClient.progressJson(0, 0, 0);

        FakeClient.Response textPlain = client.sendRaw("PUT", "/me/progress", body,
                "Authorization", auth, "Content-Type", "text/plain");
        assertThat(textPlain.status()).isEqualTo(415);
        assertThat(textPlain.errorCode()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");

        FakeClient.Response wrongMethod = client.sendRaw("GET", "/me/progress/reset", null, "Authorization", auth);
        assertThat(wrongMethod.status()).isEqualTo(405);
        assertThat(wrongMethod.errorCode()).isEqualTo("METHOD_NOT_ALLOWED");

        FakeClient.Response unknownPath = client.sendRaw("GET", "/no-such-api", null, "Authorization", auth);
        assertThat(unknownPath.status()).isEqualTo(404);
        assertThat(unknownPath.errorCode()).isEqualTo("NOT_FOUND");
    }

    @Test
    void headerVariantsUnityMaySendAreAccepted() {
        // UnityWebRequest.Post를 본문 없이 부르면 Content-Type이 form-urlencoded로 붙는다. 본문을 읽지 않는 API는 문제없어야 한다.
        FakeClient app = newClient();
        FakeClient.Response registered = app.sendRaw("POST", "/auth/guest/register", "",
                "Content-Type", "application/x-www-form-urlencoded", "Accept", "*/*");
        assertThat(registered.status()).isEqualTo(201);
        assertThat(registered.text("$.guestId")).isNotBlank();

        FakeClient client = startedClient();
        String auth = "Bearer " + client.accessToken();
        for (String contentType : List.of("application/json", "application/json; charset=utf-8", "application/json;charset=UTF-8")) {
            FakeClient.Response response = client.sendRaw("PUT", "/me/progress",
                    FakeClient.progressJson(client.revision(), 1, 0),
                    "Authorization", auth, "Content-Type", contentType, "Accept", "application/json");
            assertThat(response.status()).as(contentType).isEqualTo(200);
            assertThat(response.header("Content-Type")).as(contentType).startsWith("application/json");
            client.loadProgress();
        }
        assertThat(client.sendRaw("POST", "/me/progress/reset", "",
                "Authorization", auth, "Content-Type", "application/x-www-form-urlencoded").status()).isEqualTo(200);
    }

    @Test
    void koreanNodeIdsSurviveTheRoundTripAsUtf8() {
        FakeClient client = startedClient();

        client.saveProgress(0, 0, "타이머-01:1", "블랙홀-성장:3").expect(200);

        assertThat(client.loadProgress().<List<String>>read("$.nodes[*].nodeId"))
                .containsExactly("타이머-01", "블랙홀-성장");
    }

    @Test
    void goldUsesTheFull64BitRangeWithoutLosingPrecision() {
        FakeClient client = startedClient();

        // 2^53 + 1: 부동소수점(double)으로 변환되면 정확히 표현되지 않는 값
        client.saveProgress(9_007_199_254_740_993L, 0).expect(200);
        assertThat(client.loadProgress().number("$.gold")).isEqualTo(9_007_199_254_740_993L);

        client.saveProgress(Long.MAX_VALUE, 0).expect(200);
        assertThat(client.loadProgress().number("$.gold")).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void regressionsAndStaleSavesGetTheirOwnCodes() {
        FakeClient client = startedClient();
        client.saveProgress(0, 2, "timer-01:2", "timer-02:1").expect(200);

        assertThat(client.saveProgress(0, 1, "timer-01:2", "timer-02:1").errorCode()).isEqualTo("PROGRESS_REGRESSION");
        assertThat(client.saveProgress(0, 2, "timer-01:2").errorCode()).isEqualTo("PROGRESS_REGRESSION");
        assertThat(client.saveProgress(0, 2, "timer-01:1", "timer-02:1").errorCode()).isEqualTo("PROGRESS_REGRESSION");
        assertThat(client.saveProgressBasedOn(0, 0, 2, "timer-01:2", "timer-02:1").errorCode())
                .isEqualTo("STALE_PROGRESS");
    }

    // ---------- 실제 동시 요청 ----------

    @Test
    void ofTwoConcurrentSavesWithTheSameBaseRevisionExactlyOneWins() throws Exception {
        FakeClient owner = startedClient();
        FakeClient deviceA = owner.anotherDevice();
        FakeClient deviceB = owner.anotherDevice();
        deviceA.start();
        deviceB.start();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 15; round++) {
                long base = owner.loadProgress().number("$.revision");
                long goldA = 1000L + round;
                long goldB = 2000L + round;

                List<Integer> statuses = runTogether(pool,
                        () -> deviceA.saveProgressBasedOn(base, goldA, 0).status(),
                        () -> deviceB.saveProgressBasedOn(base, goldB, 0).status());

                Collections.sort(statuses);
                assertThat(statuses).as("라운드 %d", round).containsExactly(200, 409);
                FakeClient.Response after = owner.loadProgress();
                assertThat(after.number("$.revision")).as("라운드 %d", round).isEqualTo(base + 1);
                assertThat(after.number("$.gold")).as("라운드 %d", round).isIn(goldA, goldB);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void saveRacingWithResetLeavesAConsistentResetState() throws Exception {
        FakeClient owner = startedClient();
        FakeClient saver = owner.anotherDevice();
        FakeClient resetter = owner.anotherDevice();
        saver.start();
        resetter.start();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                owner.saveProgress(500, round + 1, "timer-01:1").expect(200);
                long base = owner.revision();

                List<Integer> statuses = runTogether(pool,
                        () -> saver.saveProgressBasedOn(base, 900, 9, "timer-01:1", "timer-02:1").status(),
                        () -> resetter.resetProgress().status());

                int saveStatus = statuses.get(0);
                int resetStatus = statuses.get(1);
                assertThat(resetStatus).as("라운드 %d", round).isEqualTo(200);
                assertThat(saveStatus).as("라운드 %d", round).isIn(200, 409);
                FakeClient.Response after = owner.loadProgress();
                // 저장이 먼저면 revision이 둘 오르고, 초기화가 먼저면 낡은 저장이 거부되어 하나만 오른다. 어느 쪽이든 초기화된 상태다.
                assertThat(after.number("$.revision")).as("라운드 %d", round)
                        .isEqualTo(saveStatus == 200 ? base + 2 : base + 1);
                assertThat(after.number("$.gold")).as("라운드 %d", round).isZero();
                assertThat(after.number("$.growthStage")).as("라운드 %d", round).isZero();
                assertThat(after.<List<Object>>read("$.nodes")).as("라운드 %d", round).isEmpty();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void ofTwoConcurrentRefreshesWithTheSameTokenExactlyOneSucceeds() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 8; round++) {
                FakeClient origin = startedClient();
                FakeClient x = origin.restartedApp();
                FakeClient y = origin.restartedApp();

                List<Integer> statuses = runTogether(pool, () -> x.refresh().status(), () -> y.refresh().status());

                List<Integer> sorted = new ArrayList<>(statuses);
                Collections.sort(sorted);
                assertThat(sorted).as("라운드 %d", round).containsExactly(200, 401);
                // 늦게 들어온 요청이 "이미 쓴 토큰의 재사용"으로 감지되어, 이긴 쪽이 받은 새 토큰도 폐기되었다(명세에 적힌 동작).
                FakeClient winner = statuses.get(0) == 200 ? x : y;
                assertThat(winner.refresh().status()).as("라운드 %d", round).isEqualTo(401);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- 도우미 ----------

    private FakeClient newClient() {
        return new FakeClient("http://localhost:" + port);
    }

    private FakeClient startedClient() {
        FakeClient client = newClient();
        client.start();
        return client;
    }

    // 두 작업을 거의 동시에 시작해, 각 결과를 작업을 넘긴 순서대로 돌려준다.
    private static List<Integer> runTogether(ExecutorService pool, Callable<Integer> first, Callable<Integer> second)
            throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        Future<Integer> a = pool.submit(() -> {
            go.await();
            return first.call();
        });
        Future<Integer> b = pool.submit(() -> {
            go.await();
            return second.call();
        });
        go.countDown();
        List<Integer> results = new ArrayList<>();
        results.add(a.get());
        results.add(b.get());
        return results;
    }

}
