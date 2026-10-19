package org.example.feedablackhole.account.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.feedablackhole.common.entity.CreatedAtEntity;

/**
 * 게임 속 한 플레이어의 신원 앵커
 * 영구적이고 거의 변하지 않음
 */
@Entity
@Table(name = "account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account extends CreatedAtEntity {

    // 테이블 내부 PK
    // 유저에 접근해야 하는 다른 모든 테이블의 FK 대상
    // JWT의 사용자 식별자
    // 순차 정수(인덱스, 조인이 가벼움)
    // ID 추측이 쉬워 타 사용자의 데이터를 조회하는 것을 조심해야 함
    // 외부 노출용 ID를 별도로 두는 것 검토
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 계정 생성(가입) 시점
    // 비즈니스 로직에 직접 사용되지는 않음
    // JPA Auditing으로 자동 기록
    // 마지막 로그인 시점
    // 버려진 계정 정리 및 유지율 분석에 활용 가능
    // 게스트 방식에서 특히 필요
    // 등록 직후에는 NULL일 수 있어 NULL 허용 (실제 로그인을 해야 첫 기록이 이루어짐)
    // 명시적으로 기록해야 함(JPA Auditing X)
    //      LastModifiedData는 "아무 필드나 바뀐 시각"을 기록한다
    //      이건 "로그인했다"는 비즈니스 사건을 기록해야 한다
    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    public static Account create() {
        return new Account();
    }

    public void markLoggedIn(Instant now) {
        this.lastLoginAt = now;
    }

}
