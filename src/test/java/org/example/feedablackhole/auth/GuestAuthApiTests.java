package org.example.feedablackhole.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.example.feedablackhole.TestcontainersConfiguration;
import org.example.feedablackhole.auth.entity.AuthIdentity;
import org.example.feedablackhole.auth.entity.AuthIdentityType;
import org.example.feedablackhole.auth.repository.AuthIdentityRepository;
import org.example.feedablackhole.auth.service.SecretCodec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 게스트 등록·로그인 API를 실제 MySQL까지 포함해 끝에서 끝까지 확인한다.
 * 테스트마다 트랜잭션이 롤백되어 서로 영향을 주지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class GuestAuthApiTests {

    private static final String REGISTER = "/api/v1/auth/guest/register";
    private static final String LOGIN = "/api/v1/auth/guest/login";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthIdentityRepository authIdentityRepository;

    @Autowired
    private SecretCodec secretCodec;

    @Test
    void registerIssuesGuestCredentials() throws Exception {
        mockMvc.perform(post(REGISTER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.guestId").isNotEmpty())
                .andExpect(jsonPath("$.guestSecret").isString());
    }

    @Test
    void registerIssuesDifferentCredentialsEachTime() throws Exception {
        String first = register();
        String second = register();

        assertThat(guestIdOf(first)).isNotEqualTo(guestIdOf(second));
        assertThat(guestSecretOf(first)).isNotEqualTo(guestSecretOf(second));
    }

    @Test
    void registerStoresOnlyTheHashOfTheSecret() throws Exception {
        String body = register();

        AuthIdentity identity = authIdentityRepository
                .findByTypeAndIdentifier(AuthIdentityType.GUEST, guestIdOf(body))
                .orElseThrow();

        assertThat(identity.getSecretHash())
                .isNotEqualTo(guestSecretOf(body))
                .isEqualTo(secretCodec.hash(guestSecretOf(body)));
    }

    @Test
    void loginSucceedsWithRegisteredCredentialsAndRecordsLoginTime() throws Exception {
        String body = register();
        AuthIdentity identity = authIdentityRepository
                .findByTypeAndIdentifier(AuthIdentityType.GUEST, guestIdOf(body))
                .orElseThrow();
        assertThat(identity.getAccount().getLastLoginAt()).isNull();

        mockMvc.perform(loginRequest(guestIdOf(body), guestSecretOf(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());

        assertThat(identity.getAccount().getLastLoginAt()).isNotNull();
    }

    @Test
    void loginFailsWithWrongSecret() throws Exception {
        String body = register();

        mockMvc.perform(loginRequest(guestIdOf(body), secretCodec.newSecret()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void loginFailureLooksTheSameForUnknownGuestIdAndWrongSecret() throws Exception {
        String body = register();

        String wrongSecret = mockMvc.perform(loginRequest(guestIdOf(body), secretCodec.newSecret()))
                .andReturn().getResponse().getContentAsString();
        String unknownGuest = mockMvc.perform(loginRequest("no-such-guest", secretCodec.newSecret()))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(unknownGuest).isEqualTo(wrongSecret);
    }

    @Test
    void loginRejectsBlankFields() throws Exception {
        mockMvc.perform(loginRequest("", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void loginRejectsMalformedJson() throws Exception {
        mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void wrongMethodUsesTheCommonErrorFormat() throws Exception {
        mockMvc.perform(get(REGISTER))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
    }

    private String register() throws Exception {
        return mockMvc.perform(post(REGISTER))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private MockHttpServletRequestBuilder loginRequest(String guestId, String guestSecret) {
        return post(LOGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"guestId\":\"%s\",\"guestSecret\":\"%s\"}".formatted(guestId, guestSecret));
    }

    private static String guestIdOf(String registerBody) {
        return JsonPath.read(registerBody, "$.guestId");
    }

    private static String guestSecretOf(String registerBody) {
        return JsonPath.read(registerBody, "$.guestSecret");
    }

}
