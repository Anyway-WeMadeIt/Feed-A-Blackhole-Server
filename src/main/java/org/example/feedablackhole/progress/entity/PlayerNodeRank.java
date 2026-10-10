package org.example.feedablackhole.progress.entity;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.feedablackhole.account.entity.Account;
import org.example.feedablackhole.common.entity.BaseTimeEntity;

/**
 * 계정이 산 노드와 그 Rank. 아직 사지 않은 노드는 행이 없다.
 * id가 자동 증가라 id 순서가 곧 "처음 산 순서"다(클라이언트가 저장 순서로 기억하는 값).
 */
@Entity
// 스키마의 원본은 Flyway SQL(V2). 아래 제약은 그것을 코드에서도 읽을 수 있게 옮겨 적은 것이며, 이름과 컬럼 구성(순서 포함)을 SQL과 같게 유지한다.
// 유니크 제약은 ddl-auto=validate가 SQL과 같은지 검증한다. (application.properties의 unique_key_validation=ALL, Hibernate 7.3+)
// 이름이나 컬럼 구성이 어긋나면 서버가 시작되지 않는다.
// 컬럼 길이, NOT NULL, 외래키는 validate가 검증하지 않으므로, SQL을 바꾸면 엔티티도 직접 같이 고친다.
// 계정당 노드 하나. 저장할 때 이미 있는 노드인지 찾는 경로이기도 하다.
@Table(
        name = "player_node_rank",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_player_node_rank_account_node",
                columnNames = {"account_id", "node_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlayerNodeRank extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    // 게임 콘텐츠의 노드 ID(예: timer-01). 서버에 노드 테이블이 생기기 전이라 FK 없이 문자열로 둔다.
    @Column(name = "node_id", nullable = false, length = 64)
    private String nodeId;

    // 산 Rank. 1 이상(DB CHECK 제약). RANK가 MySQL 예약어라 컬럼 이름은 node_rank다.
    @Column(name = "node_rank", nullable = false)
    private int rank;

    public static PlayerNodeRank of(Account account, String nodeId, int rank) {
        PlayerNodeRank nodeRank = new PlayerNodeRank();
        nodeRank.account = account;
        nodeRank.nodeId = nodeId;
        nodeRank.rank = rank;
        return nodeRank;
    }

    public void changeRank(int rank) {
        this.rank = rank;
    }

}
