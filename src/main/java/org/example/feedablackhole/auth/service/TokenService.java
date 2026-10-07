package org.example.feedablackhole.auth.service;

import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.feedablackhole.account.entity.Account;
import org.example.feedablackhole.auth.config.JwtProperties;
import org.example.feedablackhole.auth.dto.TokenResponse;
import org.example.feedablackhole.auth.entity.RefreshToken;
import org.example.feedablackhole.auth.repository.RefreshTokenRepository;
import org.example.feedablackhole.common.exception.ApiException;
import org.example.feedablackhole.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인 상태(액세스 토큰 + 리프레시 토큰)의 발급, 갱신, 폐기. 로그인 수단(게스트, 이후 소셜)과 무관하게 같은 방식으로 동작한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final AccessTokenIssuer accessTokenIssuer;
    private final SecretCodec secretCodec;
    private final JwtProperties jwtProperties;
    private final Clock clock;

    /**
     * 로그인에 성공한 계정에게 새 토큰 쌍을 발급한다. 로그인할 때마다 새 리프레시 토큰이 생기므로 기기 여러 대에서 쓸 수 있다.
     */
    @Transactional
    public TokenResponse issue(Account account) {
        return issue(account, clock.instant());
    }

    /**
     * 리프레시 토큰으로 새 토큰 쌍을 받는다. 쓴 토큰은 폐기되고 새 리프레시 토큰으로 교체된다(회전).
     *
     * <p>이미 폐기된 토큰이 다시 들어오면 누군가 토큰을 복사해 쓰고 있다는 신호이므로, 그 계정의 모든 리프레시 토큰을 폐기한다.
     * 이 폐기가 오류 응답과 함께 롤백되지 않도록 ApiException에서는 롤백하지 않는다.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public TokenResponse refresh(String rawRefreshToken) {
        Instant now = clock.instant();
        RefreshToken token = refreshTokenRepository
                .findForUpdateByTokenHash(secretCodec.hash(rawRefreshToken))
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REFRESH_TOKEN));

        if (token.getRevokedAt() != null) {
            Long accountId = token.getAccount().getId();
            int revoked = refreshTokenRepository.revokeAllByAccountId(accountId, now);
            log.warn("폐기된 리프레시 토큰이 다시 사용되어 계정의 모든 토큰을 폐기했습니다. accountId={}, revoked={}",
                    accountId, revoked);
            throw new ApiException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        if (!token.isUsable(now)) {
            throw new ApiException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        token.revoke(now);
        return issue(token.getAccount(), now);
    }

    /**
     * 리프레시 토큰을 폐기한다. 없거나 이미 폐기된 토큰이어도 성공으로 취급한다.
     * 반복 호출해도 결과가 같고, 토큰의 존재 여부도 알리지 않는다.
     */
    @Transactional
    public void logout(String rawRefreshToken) {
        Instant now = clock.instant();
        refreshTokenRepository.findForUpdateByTokenHash(secretCodec.hash(rawRefreshToken))
                .ifPresent(token -> token.revoke(now));
    }

    private TokenResponse issue(Account account, Instant now) {
        String refreshToken = secretCodec.newSecret();
        refreshTokenRepository.save(RefreshToken.issue(
                account, secretCodec.hash(refreshToken), now.plus(jwtProperties.refreshTokenTtl())));

        String accessToken = accessTokenIssuer.issue(account.getId(), now);
        return new TokenResponse(accessToken, jwtProperties.accessTokenTtl().toSeconds(), refreshToken);
    }

}
