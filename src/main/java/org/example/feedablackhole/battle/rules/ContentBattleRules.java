package org.example.feedablackhole.battle.rules;

import lombok.RequiredArgsConstructor;
import org.example.feedablackhole.battle.content.BattleContent;
import org.example.feedablackhole.node.content.NodeTree;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 전투 규칙(계약 1.6)을 콘텐츠로 계산하는 스프링 빈. 서버가 시작할 때 ContentBundleConfig가 만든
 * NodeTree와 BattleContent를 받아 만들어진다. 판 시작 검증(RUN_START)과 결산 검증(SETTLEMENT)이 주입받아 쓴다.
 *
 * <p>필드를 읽기만 하고 호출마다 새 객체를 만들어 돌려주므로 상태가 없다(여러 요청이 동시에 불러도 안전하다).
 */
@Component
@RequiredArgsConstructor
public final class ContentBattleRules implements BattleRules {

    // 노드의 배치와 효과(산 노드로 수치 값을 계산할 때 쓴다)
    private final NodeTree nodeTree;

    // 전투 콘텐츠(성장, 규칙, 적 종류, 시작 공급. 판 설정을 계산할 때 쓴다)
    private final BattleContent content;

    // 산 노드 Rank(nodeId -> rank, 산 노드만)로 수치 값을 계산한다
    @Override
    public UpgradeValues upgradesFor(Map<String, Integer> nodeRanks) {
        return NodeUpgradeValues.of(nodeTree, nodeRanks);
    }

    // 성장도와 수치 값으로 이번 판의 설정을 계산한다
    @Override
    public RunSetup setupFor(int growthStage, UpgradeValues upgrades) {
        return RunComposition.of(content, growthStage, upgrades);
    }
}
