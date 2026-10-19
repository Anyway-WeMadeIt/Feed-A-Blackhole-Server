package org.example.feedablackhole.battle.content;

import java.util.List;
import java.util.Optional;

/**
 * 적 종류 하나(클라이언트 EnemyData 중 검증에 필요한 부분).
 *
 * @param enemyId    소문자 종류 id: asteroid, planet, star, comet, galaxy
 * @param pickup     수거형(주기 출현, 혜성) 여부. 수거형은 공급·질량·크기·시간 추가가 없다
 * @param upgradesTo 판 시작 때 변환되는 다음 종류의 id. 없으면 null
 * @param tiers      색 등급별 크기 1의 Gold·EXP. 순서가 tier 번호(0부터). 공급 종류는 6개, 수거형은 1개
 * @param traits     소문자 성질 id(golden, electric, moon, laser, supernova, comet, gravity). 종류의 성질 순서
 * @param golden     노드 반영 전 황금 성질 수치. traits에 golden이 없으면 null
 * @param stats      이 종류의 판 구성에 닿는 수치의 statId
 */
public record EnemyKindDefinition(
        String enemyId,
        boolean pickup,
        String upgradesTo,
        List<EnemyTier> tiers,
        List<String> traits,
        GoldenTrait golden,
        EnemyStatIds stats) {

    public static final String GOLDEN = "golden";

    public EnemyKindDefinition {
        tiers = List.copyOf(tiers);
        traits = List.copyOf(traits);
    }

    public Optional<String> upgradeTarget() {
        return Optional.ofNullable(upgradesTo);
    }

    public Optional<GoldenTrait> goldenTrait() {
        return Optional.ofNullable(golden);
    }

}
