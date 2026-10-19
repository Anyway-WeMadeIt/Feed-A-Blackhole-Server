package org.example.feedablackhole.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import lombok.Getter;
import org.springframework.data.annotation.LastModifiedDate;

/**
 * 만들어진 시각(created_at)에 더해 마지막으로 바뀐 시각(updated_at)을 가진 엔티티의 부모.
 * updated_at은 저장할 때와 값이 바뀌어 UPDATE가 나갈 때 자동으로 갱신된다(JpaAuditingConfig의 Clock 기준).
 */
@MappedSuperclass
@Getter
public abstract class BaseTimeEntity extends CreatedAtEntity {

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

}
