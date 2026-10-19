package org.example.feedablackhole.battle.rules;

import java.util.Optional;
import org.example.feedablackhole.battle.content.GoldenTrait;

/**
 * 판 하나에서 적 종류 하나의 구성(클라이언트 EnemyComposition과 EnemyDefinition.StatsAt 중 검증에 필요한 부분).
 * 범위 밖 입력(없는 tier, maxSize보다 큰 size, 황금이 불가능한데 golden = true)은 IllegalArgumentException.
 */
public interface KindSetup {

    String enemyId();

    boolean pickup();

    /** 이 판에서 나올 수 있는 가장 큰 크기(1 ~ 20). 수거형은 1. */
    int maxSize();

    /** 이 판의 질량에서 이 색 등급이 나올 수 있는가(MassRule의 비율이 0보다 큰가). */
    boolean tierPossible(int tier);

    /** 이 판에서 황금이 나올 수 있는가(golden 성질이 있고 생성 확률이 0보다 큼). */
    boolean goldenPossible();

    /** 파괴될 때 시간 추가 확률(0 ~ 1). 수거형은 0. */
    float timeChance();

    /** 노드가 반영된 황금 수치. golden 성질이 없으면 비어 있다. */
    Optional<GoldenTrait> golden();

    /** 처치 하나의 Gold: round(tier Gold × 크기 배율), 황금이면 round(그 값 × 황금 배율). */
    long goldOf(int tier, int size, boolean golden);

    /** 처치 하나의 EXP: round(tier EXP × 크기 배율). */
    long expOf(int tier, int size);

    /** 황금 보너스 치명 한 번의 Gold: round(goldOf(tier, size, true) × critRewardScale). */
    long goldenCritBonusOf(int tier, int size);

}
