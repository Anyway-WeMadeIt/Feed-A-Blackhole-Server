package org.example.feedablackhole.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import javax.crypto.spec.SecretKeySpec;
import org.example.feedablackhole.TestcontainersConfiguration;
import org.example.feedablackhole.account.entity.Account;
import org.example.feedablackhole.account.repository.AccountRepository;
import org.example.feedablackhole.auth.config.JwtProperties;
import org.example.feedablackhole.auth.entity.RefreshToken;
import org.example.feedablackhole.auth.repository.RefreshTokenRepository;
import org.example.feedablackhole.auth.service.SecretCodec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 토큰 발급·갱신·폐기와 액세스 토큰으로 보호되는 API를 실제 MySQL까지 포함해 끝에서 끝까지 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class TokenApiTests {

    private static final String REGISTER = "/api/v1/auth/guest/register";
    private static final String LOGIN = "/api/v1/auth/guest/login";
    private static final String REFRESH = "/api/v1/auth/refresh";
    private static final String LOGOUT = "/api/v1/auth/logout";
    private static final String ME = "/api/v1/me";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private Clock clock;

    @Autowired
    private SecretCodec secretCodec;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    // ---------- 로그인과 토큰 발급 ----------

    @Test
    void loginIssuesAnAccessTokenAndARefreshToken() throws Exception {
        String tokens = loginAsNewGuest();

        assertThat(accessTokenOf(tokens).split("\\.")).hasSize(3);
        assertThat(JsonPath.<Integer>read(tokens, "$.expiresIn"))
                .isEqualTo((int) jwtProperties.accessTokenTtl().toSeconds());
        assertThat(refreshTokenOf(tokens)).hasSize(43);
    }

    @Test
    void refreshTokenIsStoredOnlyAsAHash() throws Exception {
        String tokens = loginAsNewGuest();

        assertThat(refreshTokenRepository.findByTokenHash(secretCodec.hash(refreshTokenOf(tokens)))).isPresent();
        assertThat(refreshTokenRepository.findByTokenHash(refreshTokenOf(tokens))).isEmpty();
    }

    // ---------- 액세스 토큰으로 보호되는 API ----------

    @Test
    void validAccessTokenReachesProtectedApi() throws Exception {
        String tokens = loginAsNewGuest();

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(accessTokenOf(tokens))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").isNumber());
    }

    @Test
    void protectedApiRejectsRequestWithoutToken() throws Exception {
        mockMvc.perform(get(ME))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void protectedApiRejectsGarbageToken() throws Exception {
        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer("not-a-jwt")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void protectedApiRejectsExpiredToken() throws Exception {
        Instant longAgo = clock.instant().minus(Duration.ofHours(2));

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(tokenSignedWith(jwtEncoder, "1", longAgo))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void protectedApiRejectsTokenSignedWithAnotherKey() throws Exception {
        JwtEncoder strangerEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(
                "another-signing-key-that-is-long-enough-0123456789".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256")));

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION,
                        bearer(tokenSignedWith(strangerEncoder, "1", clock.instant()))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void unknownPathUsesTheCommonErrorFormatForAuthenticatedUsers() throws Exception {
        String tokens = loginAsNewGuest();

        mockMvc.perform(get("/api/v1/nothing-here").header(HttpHeaders.AUTHORIZATION, bearer(accessTokenOf(tokens))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ---------- 갱신(회전) ----------

    @Test
    void refreshRotatesTheTokensAndTheNewOnesWork() throws Exception {
        String first = loginAsNewGuest();

        String second = mockMvc.perform(refreshRequest(refreshTokenOf(first)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(refreshTokenOf(second)).isNotEqualTo(refreshTokenOf(first));
        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(accessTokenOf(second))))
                .andExpect(status().isOk());
    }

    @Test
    void usedRefreshTokenCannotBeUsedAgainAndTheWholeAccountIsLoggedOut() throws Exception {
        String first = loginAsNewGuest();
        String second = mockMvc.perform(refreshRequest(refreshTokenOf(first)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 이미 쓴(폐기된) 토큰이 다시 들어옴 → 거부하고, 탈취를 의심해 그 계정의 모든 리프레시 토큰을 폐기한다.
        mockMvc.perform(refreshRequest(refreshTokenOf(first)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));

        // 정상 회전으로 받은 새 토큰도 함께 폐기되었다.
        mockMvc.perform(refreshRequest(refreshTokenOf(second)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void refreshRejectsUnknownToken() throws Exception {
        mockMvc.perform(refreshRequest(secretCodec.newSecret()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void refreshRejectsExpiredToken() throws Exception {
        Account account = accountRepository.save(Account.create());
        String raw = secretCodec.newSecret();
        refreshTokenRepository.saveAndFlush(
                RefreshToken.issue(account, secretCodec.hash(raw), clock.instant().minusSeconds(1)));

        mockMvc.perform(refreshRequest(raw))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void refreshRejectsBlankToken() throws Exception {
        mockMvc.perform(refreshRequest(""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    // ---------- 로그아웃 ----------

    @Test
    void logoutRevokesTheRefreshToken() throws Exception {
        String tokens = loginAsNewGuest();

        mockMvc.perform(logoutRequest(refreshTokenOf(tokens)))
                .andExpect(status().isNoContent());

        mockMvc.perform(refreshRequest(refreshTokenOf(tokens)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void logoutSucceedsEvenForUnknownToken() throws Exception {
        mockMvc.perform(logoutRequest(secretCodec.newSecret()))
                .andExpect(status().isNoContent());
    }

    // ---------- 도우미 ----------

    private String loginAsNewGuest() throws Exception {
        String credentials = mockMvc.perform(post(REGISTER))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"guestId\":\"%s\",\"guestSecret\":\"%s\"}".formatted(
                                JsonPath.<String>read(credentials, "$.guestId"),
                                JsonPath.<String>read(credentials, "$.guestSecret"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String tokenSignedWith(JwtEncoder encoder, String subject, Instant issuedAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .subject(subject)
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(jwtProperties.accessTokenTtl()))
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private static MockHttpServletRequestBuilder refreshRequest(String refreshToken) {
        return post(REFRESH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"%s\"}".formatted(refreshToken));
    }

    private static MockHttpServletRequestBuilder logoutRequest(String refreshToken) {
        return post(LOGOUT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"%s\"}".formatted(refreshToken));
    }

    private static String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }

    private static String accessTokenOf(String tokens) {
        return JsonPath.read(tokens, "$.accessToken");
    }

    private static String refreshTokenOf(String tokens) {
        return JsonPath.read(tokens, "$.refreshToken");
    }

}
