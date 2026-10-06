package org.example.feedablackhole.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.feedablackhole.account.entity.Account;
import org.example.feedablackhole.common.entity.CreatedAtEntity;

/**
 * 계정에 접근하는 수단
 */
@Entity
// 스키마의 원본은 Flyway SQL(V1). 아래 제약은 그것을 코드에서도 읽을 수 있게 옮겨 적은 것이며, 이름을 SQL과 같게 유지한다.
// 실제 스키마와 어긋나도 ddl-auto=validate가 잡아주지 않는다.
// (이 어노테이션은 생성 시점에만 잡아주는데, 이 프로젝트는 Hibernate가 아닌 Flyway가 스키마를 만든다)
// SQL의 제약 이름이나 구성을 바꾸면, 엔티티도 같이 고쳐야 한다.
// 같은 (type, identifier)는 한 번만 존재한다. 로그인 조회 경로이자, 한 외부 계정이 두 계정에 붙는 것을 막는 장치다.
@Table(
        name = "auth_identity",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_auth_identity_type_identifier",
                columnNames = {"type", "identifier"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuthIdentity extends CreatedAtEntity {

    // 행 식별자
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 계정 주인과의 관계
    // 한 계정에 여러 행이 붙을 수 있음
    //      예를 들어, 한 계정이 구글과 애플 모두 연동되는 경우
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    // 연동 수단 구분자
    // 게스트, 구글, 애플 등
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuthIdentityType type;

    // 수단을 포함한 고유 식별자
    // 게스트
    //      서버가 발급한 guestId(UUID 36자)
    //      guestId는 신원 구분용
    //      자격 증명은 secret
    // 소셜(구글, 애플 등)
    //      제공자가 주는 사용자 ID
    //      외부에서 제공되는 값 (identifier가 PK가 될 수 없는 이유 중 하나)
    // (type, identifier)는 유일 제약이 걸려있음
    @Column(nullable = false, length = 255)
    private String identifier;

    // 게스트의 secret을 SHA-256으로 해시한 hex
    // 게스트 계정의 비밀번호와 같은 값
    // 어차피 랜덤생성이라 bcrypt나 salt 사용 불필요(위험성 낮음)
    // DB가 유출되어도 원본 secret 복원 불가능
    // 소셜 수단은 제공자가 증명하므로 NULL
    @Column(name = "secret_hash", length = 64)
    private String secretHash;

    public static AuthIdentity guest(Account account, String guestId, String secretHash) {
        AuthIdentity identity = new AuthIdentity();
        identity.account = account;
        identity.type = AuthIdentityType.GUEST;
        identity.identifier = guestId;
        identity.secretHash = secretHash;
        return identity;
    }

}
