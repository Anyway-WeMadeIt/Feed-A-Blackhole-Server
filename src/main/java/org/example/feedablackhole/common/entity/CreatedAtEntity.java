package org.example.feedablackhole.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 행이 만들어진 시각(created_at)을 가진 엔티티의 부모. 상속만 하면 저장할 때 값이 자동으로 채워진다.
 * 시각은 JpaAuditingConfig의 Clock을 따른다. 로그인·폐기처럼 업무상의 시각은 여기가 아니라 각 엔티티가 직접 가진다.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Getter
public abstract class CreatedAtEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

}
