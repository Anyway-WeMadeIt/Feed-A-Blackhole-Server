package org.example.feedablackhole.support;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Unity 클라이언트를 흉내 내는 테스트용 클라이언트. 실제 HTTP로 서버에 말하고, 요청은 서버의 DTO 클래스를 쓰지 않고
 * docs/api-spec.md의 예시처럼 JSON 문자열로 직접 조립한다(서버 코드와 계약이 같이 틀려지는 일을 막는다).
 *
 * <p>명세의 "클라이언트 처리 흐름"을 그대로 구현한 기준 구현이기도 하다.
 * <pre>
 * 앱 시작   : 자격증명이 없으면 register → login, 리프레시 토큰이 있으면 refresh(실패하면 login), 없으면 login
 * API 호출  : 401 UNAUTHORIZED → refresh → 같은 요청을 한 번 재시도, refresh가 401 INVALID_REFRESH_TOKEN → login 후 재시도
 * </pre>
 * 액세스 토큰은 메모리에만 두고, 파일에 남기는 값(guestId, guestSecret, refreshToken)은 {@link #restartedApp()}이 넘겨받는다.
 */
public final class FakeClient {

    private static final String API = "/api/v1";

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final String baseUrl;
    private final List<String> log = new ArrayList<>();

    // 기기에 저장되는 값
    private String guestId;
    private String guestSecret;
    private String refreshToken;

    // 메모리에만 있는 값
    private String accessToken;
    private long revision;

    public FakeClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** 이 클라이언트가 보낸 요청의 기록. "METHOD 경로 -> 상태 코드" 형식이다. */
    public List<String> log() {
        return List.copyOf(log);
    }

    public void clearLog() {
        log.clear();
    }

    public String guestId() {
        return guestId;
    }

    public String refreshToken() {
        return refreshToken;
    }

    public String accessToken() {
        return accessToken;
    }

    /** 마지막으로 받은 진행 상태의 revision. 저장할 때 baseRevision으로 보낸다. */
    public long revision() {
        return revision;
    }

    /** 앱을 껐다 켠 클라이언트: 기기에 저장된 값만 이어받고 액세스 토큰과 revision은 없다. */
    public FakeClient restartedApp() {
        FakeClient next = new FakeClient(baseUrl);
        next.guestId = guestId;
        next.guestSecret = guestSecret;
        next.refreshToken = refreshToken;
        return next;
    }

    /** 같은 계정으로 로그인하는 다른 기기: 자격증명(guestId, guestSecret)만 같고 토큰은 따로 받는다. */
    public FakeClient anotherDevice() {
        FakeClient next = new FakeClient(baseUrl);
        next.guestId = guestId;
        next.guestSecret = guestSecret;
        return next;
    }

    // ---------- 앱 시작과 인증 흐름 ----------

    public void start() {
        if (guestId == null) {
            register();
            login();
        } else if (refreshToken != null) {
            refreshOrLogin();
        } else {
            login();
        }
    }

    public void register() {
        Response response = send("POST", "/auth/guest/register", null, null);
        response.expect(201);
        guestId = response.text("$.guestId");
        guestSecret = response.text("$.guestSecret");
    }

    public void login() {
        Response response = send("POST", "/auth/guest/login",
                "{\"guestId\":\"%s\",\"guestSecret\":\"%s\"}".formatted(guestId, guestSecret), null);
        response.expect(200);
        storeTokens(response);
    }

    public Response refresh() {
        Response response = send("POST", "/auth/refresh",
                "{\"refreshToken\":\"%s\"}".formatted(refreshToken), null);
        if (response.status() == 200) {
            storeTokens(response);
        }
        return response;
    }

    public Response logout() {
        return send("POST", "/auth/logout", "{\"refreshToken\":\"%s\"}".formatted(refreshToken), null);
    }

    private void refreshOrLogin() {
        Response response = refresh();
        if (response.status() == 401 && "INVALID_REFRESH_TOKEN".equals(response.errorCode())) {
            login();
            return;
        }
        response.expect(200);
    }

    private void storeTokens(Response response) {
        accessToken = response.text("$.accessToken");
        refreshToken = response.text("$.refreshToken");
    }

    // ---------- 보호된 API 호출 (401이면 갱신 후 한 번 재시도) ----------

    public Response call(String method, String path, String jsonBody) {
        Response response = send(method, path, jsonBody, accessToken);
        if (response.status() == 401 && "UNAUTHORIZED".equals(response.errorCode())) {
            refreshOrLogin();
            response = send(method, path, jsonBody, accessToken);
        }
        return response;
    }

    // ---------- 진행 데이터 ----------

    /** 진행 상태를 불러오고, 성공하면 응답의 revision을 기억한다. */
    public Response loadProgress() {
        return remember(call("GET", "/me/progress", null));
    }

    /** 기억한 revision을 baseRevision으로 저장한다. 성공(200)하면 응답의 revision을 기억한다. nodes는 "노드ID:Rank" 형식. */
    public Response saveProgress(long gold, int growthStage, String... nodes) {
        return saveProgressBasedOn(revision, gold, growthStage, nodes);
    }

    public Response saveProgressBasedOn(long baseRevision, long gold, int growthStage, String... nodes) {
        return remember(call("PUT", "/me/progress", progressJson(baseRevision, gold, growthStage, nodes)));
    }

    public Response resetProgress() {
        return remember(call("POST", "/me/progress/reset", null));
    }

    private Response remember(Response response) {
        if (response.status() == 200) {
            revision = response.number("$.revision");
        }
        return response;
    }

    public static String progressJson(long baseRevision, long gold, int growthStage, String... nodes) {
        String nodesJson = Arrays.stream(nodes)
                .map(node -> {
                    int colon = node.lastIndexOf(':');
                    return "{\"nodeId\":\"%s\",\"rank\":%s}".formatted(node.substring(0, colon), node.substring(colon + 1));
                })
                .collect(Collectors.joining(",", "[", "]"));
        return "{\"baseRevision\":%d,\"gold\":%d,\"growthStage\":%d,\"nodes\":%s}"
                .formatted(baseRevision, gold, growthStage, nodesJson);
    }

    // ---------- 저수준: 요청 한 건 ----------

    /** 헤더를 직접 정해 보낸다(Unity가 보내는 헤더 변형을 재현하거나 잘못된 요청을 만들 때). */
    public Response sendRaw(String method, String path, String body, String... headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + API + path))
                .timeout(Duration.ofSeconds(10))
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return execute(method, path, request.build());
    }

    private Response send(String method, String path, String jsonBody, String bearerToken) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + API + path))
                .timeout(Duration.ofSeconds(10))
                .method(method, jsonBody == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        if (jsonBody != null) {
            request.header("Content-Type", "application/json");
        }
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        return execute(method, path, request.build());
    }

    private Response execute(String method, String path, HttpRequest request) {
        try {
            HttpResponse<String> raw = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            log.add("%s %s -> %d".formatted(method, path, raw.statusCode()));
            return new Response(raw);
        } catch (IOException e) {
            throw new IllegalStateException("요청 실패: " + method + " " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("요청 중단: " + method + " " + path, e);
        }
    }

    /** 서버의 응답. 본문은 JSON 경로(JsonPath)로 읽는다. */
    public static final class Response {

        private final HttpResponse<String> raw;

        Response(HttpResponse<String> raw) {
            this.raw = raw;
        }

        public int status() {
            return raw.statusCode();
        }

        public String body() {
            return raw.body();
        }

        public String header(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }

        public String text(String path) {
            return JsonPath.read(raw.body(), path);
        }

        public long number(String path) {
            Object value = JsonPath.read(raw.body(), path);
            return ((Number) value).longValue();
        }

        public <T> T read(String path) {
            return JsonPath.read(raw.body(), path);
        }

        /** 오류 응답의 code. 오류 형식이 아니면 null. */
        public String errorCode() {
            try {
                return JsonPath.read(raw.body(), "$.error.code");
            } catch (RuntimeException e) {
                return null;
            }
        }

        public Response expect(int expectedStatus) {
            if (status() != expectedStatus) {
                throw new AssertionError("상태 코드 %d를 기대했지만 %d였다. 본문: %s"
                        .formatted(expectedStatus, status(), body()));
            }
            return this;
        }

    }

}
