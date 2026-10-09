package org.example.feedablackhole.progress.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.feedablackhole.account.entity.Account;
import org.example.feedablackhole.common.entity.BaseTimeEntity;

/**
 * 계정의 게임 진행 상태(Gold, 블랙홀 성장도). 계정당 한 행이며, 계정을 만들 때 처음 상태로 함께 만든다.
 * 스키마의 원본은 Flyway SQL(V2)이고, 이 엔티티는 그것을 코드에서 읽을 수 있게 옮겨 적은 것이다.
 *
 * <p>클라이언트의 PlayerState(Gold, 성장도, 산 노드)에 해당한다. 산 노드는 PlayerNodeRank가 가진다.
 */
@Entity
@Table(name = "player_progress")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlayerProgress extends BaseTimeEntity {

    /** 새 진행의 성장도. 클라이언트의 HqGrowthDefinition.StartStage와 같아야 한다. */
    public static final int INITIAL_GROWTH_STAGE = 0;

    // PK이면서 FK: 계정의 ID를 그대로 쓴다(account와 1:1). @MapsId가 account의 ID를 이 값으로 채운다.
    @Id
    @Column(name = "account_id")
    private Long accountId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "account_id")
    private Account account;

    // 원작의 금액은 T(조) 단위까지 오르므로 long. 0 이상(DB CHECK 제약)
    @Column(nullable = false)
    private long gold;

    // 도달한 이정표의 수. 0 이상이고, 저장으로는 줄지 않는다(처음으로 되돌리는 reset만 예외).
    @Column(name = "growth_stage", nullable = false)
    private int growthStage;

    // 진행 상태를 바꿀 때마다 1 오른다. 저장 요청이 읽은 버전(baseRevision)과 같을 때만 저장을 받아서,
    // 다른 요청이 먼저 저장한 것을 덮어쓰지 않게 한다.
    @Column(nullable = false)
    private long revision;

    /** 처음 상태(Gold 0, 성장도 0, revision 0)의 진행 상태. */
    public static PlayerProgress initial(Account account) {
        PlayerProgress progress = new PlayerProgress();
        progress.account = account;
        progress.gold = 0;
        progress.growthStage = INITIAL_GROWTH_STAGE;
        progress.revision = 0;
        return progress;
    }

    /** 새 값으로 바꾸고 revision을 올린다. 값이 같아도 올린다(저장이 일어났다는 기록). */
    public void update(long gold, int growthStage) {
        this.gold = gold;
        this.growthStage = growthStage;
        this.revision++;
    }

    /** 새 게임: 처음 상태로 되돌리고 revision을 올린다. */
    public void reset() {
        update(0, INITIAL_GROWTH_STAGE);
    }

}
