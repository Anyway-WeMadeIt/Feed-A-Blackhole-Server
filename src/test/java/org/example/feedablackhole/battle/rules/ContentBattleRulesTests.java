package org.example.feedablackhole.battle.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.example.feedablackhole.battle.content.SupplyEntry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * ContentBattleRules가 upgradesFor와 setupFor를 이어서 판 설정을 계산하는지 확인하는 테스트.
 * 샘플 콘텐츠 묶음(src/test/resources/content-sample)으로 검사한다.
 */
class ContentBattleRulesTests {

    private static BattleRules rules;

    // 샘플 묶음의 노드 트리와 전투 콘텐츠로 규칙을 만든다(스프링 없이 직접 생성)
    @BeforeAll
    static void load() {
        rules = new ContentBattleRules(SampleContent.tree(), SampleContent.battle());
    }

    // 노드를 사지 않은 성장도 0: 시작 Level 0, 제한 시간 12초, 시작 적은 소행성 20마리
    @Test
    void setupWithoutNodes() {
        RunSetup setup = rules.setupFor(0, rules.upgradesFor(Map.of()));

        assertThat(setup.growthStage()).isZero();
        assertThat(setup.startLevel()).isZero();
        assertThat(setup.timeLimitSeconds()).isEqualTo(12f);
        assertThat(setup.startSupply()).containsExactly(new SupplyEntry("asteroid", 20));
    }

    // 산 노드가 수치 값을 거쳐 판 설정에 반영된다(timer-01은 제한 시간 +2초)
    @Test
    void boughtNodesReachTheSetup() {
        UpgradeValues upgrades = rules.upgradesFor(Map.of("timer-01", 1));
        RunSetup setup = rules.setupFor(0, upgrades);

        assertThat(upgrades.gainOf("timer")).isEqualTo(2f);
        assertThat(setup.timeLimitSeconds()).isEqualTo(14f);
    }

    // 성장도가 판 설정의 시작 Level과 목표 Level을 정한다
    @Test
    void growthStageDecidesTheLevels() {
        RunSetup setup = rules.setupFor(1, rules.upgradesFor(Map.of()));

        assertThat(setup.startLevel()).isEqualTo(10);
        assertThat(setup.goalLevel()).isEqualTo(20);
    }

    // 같은 입력이면 매번 같은 결과가 나온다(상태를 바꾸지 않는 빈)
    @Test
    void theRulesHoldNoStateBetweenCalls() {
        UpgradeValues upgrades = rules.upgradesFor(Map.of("timer-01", 1));

        assertThat(rules.setupFor(0, upgrades).timeLimitSeconds())
                .isEqualTo(rules.setupFor(0, upgrades).timeLimitSeconds());
        assertThat(rules.setupFor(0, rules.upgradesFor(Map.of())).timeLimitSeconds()).isEqualTo(12f);
    }

}
