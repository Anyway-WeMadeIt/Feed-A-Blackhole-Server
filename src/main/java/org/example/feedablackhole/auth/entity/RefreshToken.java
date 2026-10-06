package org.example.feedablackhole.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.feedablackhole.account.entity.Account;
import org.example.feedablackhole.common.entity.CreatedAtEntity;

/**
 * 로그인 유지를 담당
 * 액세스 토큰(JWT)은 서버가 상태를 저장하지 않는다
 *      발급 후 취소할 수 없다
 *      수명이 짧다
 *
 */
@Entity
// 스키마의 원본은 Flyway SQL(V1). 아래 제약은 그것을 코드에서도 읽을 수 있게 옮겨 적은 것이며, 이름을 SQL과 같게 유지한다.
// 실제 스키마와 어긋나도 ddl-auto=validate가 잡아주지 않는다. (이 어노테이션은 생성 시점에만 잡아준다)
// SQL의 제약 이름이나 구성을 바꾸면, 엔티티도 같이 고쳐야 한다.
// 같은 token_hash는 한 번만 존재한다. 갱신 요청이 토큰 해시로 행을 찾는 조회 경로다.
@Table(
        name = "refresh_token",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_refresh_token_token_hash",
                columnNames = "token_hash"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 계정 주인과의 관계 (누구의 토큰인가)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    // 토큰의 해시값 (원본은 저장하지 않음)
    // 유일성 제약이 걸려있음
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    // 만료 시각
    // 갱신 시 검사
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    // 폐기 시각
    // 폐기된 토큰의 행을 지우지 않고 남겨둬야 함
    // 누군가 폐기된 토큰을 "훔쳐서" 쓰고 있다는 것을 감지 (없는 토큰과 폐기된 토큰을 구분)
    @Column(name = "revoked_at")
    private Instant revokedAt;

    public static RefreshToken issue(Account account, String tokenHash, Instant expiresAt) {
        RefreshToken token = new RefreshToken();
        token.account = account;
        token.tokenHash = tokenHash;
        token.expiresAt = expiresAt;
        return token;
    }

    public boolean isUsable(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            this.revokedAt = now;
        }
    }

}
