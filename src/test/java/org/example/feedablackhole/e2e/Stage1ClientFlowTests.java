package org.example.feedablackhole.e2e;

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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 1단계(인증, 진행 데이터 조회)를 Unity 클라이언트 없이 검증한다.
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
        assertThat(progress.number("$.lastAppliedSeq")).isZero();
        assertThat(progress.number("$.gold")).isZero();
        assertThat(progress.number("$.growthStage")).isZero();
        assertThat(progress.<List<Object>>read("$.nodes")).isEmpty();
        assertThat(progress.text("$.updatedAt")).isNotBlank();
    }

    // ---------- 앱 재시작 ----------

    @Test
    void restartedAppRefreshesFirstAndLoadsTheProgress() {
        FakeClient first = startedClient();
        String oldRefreshToken = first.refreshToken();

        FakeClient second = first.restartedApp();
        second.start();

        assertThat(second.log()).containsExactly("POST /auth/refresh -> 200");
        assertThat(second.refreshToken()).isNotEqualTo(oldRefreshToken);
        second.loadProgress().expect(200);
    }

    @Test
    void usedRefreshTokenFallsBackToLoginAndReuseRevokesTheOtherTokens() {
        FakeClient first = startedClient();
        FakeClient second = first.restartedApp();
        second.start();

        // 앱이 새 토큰을 저장하기 전에 꺼져서 옛(이미 쓴) 리프레시 토큰이 남은 경우
        FakeClient staleApp = first.restartedApp();
        staleApp.start();
        assertThat(staleApp.log()).containsExactly("POST /auth/refresh -> 401", "POST /auth/guest/login -> 200");
        staleApp.loadProgress().expect(200);

        // 재사용 감지로 계정의 리프레시 토큰이 모두 폐기되어, 정상 회전으로 받았던 토큰도 쓸 수 없다. 그래도 로그인으로 복구된다.
        FakeClient third = second.restartedApp();
        third.start();
        assertThat(third.log()).containsExactly("POST /auth/refresh -> 401", "POST /auth/guest/login -> 200");
        third.loadProgress().expect(200);
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
    void expiredRefreshTokenFallsBackToLogin() {
        FakeClient client = startedClient();
        FakeClient storedOnDevice = client.restartedApp();

        clock.advance(Duration.ofDays(31));
        storedOnDevice.start();

        assertThat(storedOnDevice.log()).containsExactly("POST /auth/refresh -> 401", "POST /auth/guest/login -> 200");
        storedOnDevice.loadProgress().expect(200);
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
    void wrongContentTypeMethodAndPathUseTheCommonErrorFormat() {
        FakeClient client = startedClient();
        String auth = "Bearer " + client.accessToken();

        FakeClient.Response textPlain = client.sendRaw("POST", "/me/sync", "{\"logs\":[]}",
                "Authorization", auth, "Content-Type", "text/plain");
        assertThat(textPlain.status()).isEqualTo(415);
        assertThat(textPlain.errorCode()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");

        // 옛 저장 API(PUT)는 없어졌다: 경로는 있지만 GET만 받는다
        FakeClient.Response wrongMethod = client.sendRaw("PUT", "/me/progress", "{}",
                "Authorization", auth, "Content-Type", "application/json");
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

        // 동기화 요청은 Content-Type의 charset 표기가 달라도 받아야 한다(로그 없는 요청은 진행도만 돌려준다).
        FakeClient client = startedClient();
        String auth = "Bearer " + client.accessToken();
        for (String contentType : List.of("application/json", "application/json; charset=utf-8", "application/json;charset=UTF-8")) {
            FakeClient.Response response = client.sendRaw("POST", "/me/sync", "{\"logs\":[]}",
                    "Authorization", auth, "Content-Type", contentType, "Accept", "application/json");
            assertThat(response.status()).as(contentType).isEqualTo(200);
            assertThat(response.header("Content-Type")).as(contentType).startsWith("application/json");
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
