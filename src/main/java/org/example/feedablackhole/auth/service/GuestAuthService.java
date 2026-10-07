package org.example.feedablackhole.auth.service;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.example.feedablackhole.account.entity.Account;
import org.example.feedablackhole.account.service.AccountService;
import org.example.feedablackhole.auth.dto.GuestRegisterResponse;
import org.example.feedablackhole.auth.dto.TokenResponse;
import org.example.feedablackhole.auth.entity.AuthIdentity;
import org.example.feedablackhole.auth.entity.AuthIdentityType;
import org.example.feedablackhole.auth.repository.AuthIdentityRepository;
import org.example.feedablackhole.common.exception.ApiException;
import org.example.feedablackhole.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GuestAuthService {

    // 없는 guestId로 로그인할 때도 같은 비교를 수행하기 위한 자리 값(어떤 secret의 해시도 될 수 없는 값).
    private static final String UNKNOWN_IDENTITY_HASH = "0".repeat(64);

    private final AccountService accountService;
    private final AuthIdentityRepository authIdentityRepository;
    private final SecretCodec secretCodec;
    private final TokenService tokenService;
    private final Clock clock;

    /**
     * 새 계정과 게스트 인증 수단을 만든다. secret 원본은 반환값으로만 나가고 저장되지 않는다.
     */
    @Transactional
    public GuestRegisterResponse register() {
        String guestId = UUID.randomUUID().toString();
        String secret = secretCodec.newSecret();

        Account account = accountService.create();
        authIdentityRepository.save(AuthIdentity.guest(account, guestId, secretCodec.hash(secret)));

        return new GuestRegisterResponse(guestId, secret);
    }

    /**
     * guestId와 secret을 확인하고 로그인 상태(토큰 쌍)를 발급한다.
     * guestId가 없는 경우와 secret이 틀린 경우를 같은 오류로 응답해, 존재하는 guestId를 알아낼 수 없게 한다.
     */
    @Transactional
    public TokenResponse login(String guestId, String secret) {
        Optional<AuthIdentity> identity =
                authIdentityRepository.findByTypeAndIdentifier(AuthIdentityType.GUEST, guestId);
        String storedHash = identity.map(AuthIdentity::getSecretHash).orElse(UNKNOWN_IDENTITY_HASH);
        boolean secretMatches = secretCodec.matches(secret, storedHash);

        if (identity.isEmpty() || !secretMatches) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }

        Account account = identity.get().getAccount();
        account.markLoggedIn(clock.instant());
        return tokenService.issue(account);
    }

}
