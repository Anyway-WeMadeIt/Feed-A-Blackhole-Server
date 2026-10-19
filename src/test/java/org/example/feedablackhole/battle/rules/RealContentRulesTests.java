package org.example.feedablackhole.battle.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import org.example.feedablackhole.battle.content.BattleContent;
import org.example.feedablackhole.battle.content.ContentBundle;
import org.example.feedablackhole.battle.content.ContentBundleLoader;
import org.example.feedablackhole.node.content.NodeDefinition;
import org.example.feedablackhole.node.content.NodeTree;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 서버가 실제로 쓰는 콘텐츠(src/main/resources/content)로도 전투 규칙이 판 설정을 만들 수 있는지 확인한다.
 * 규칙 계산 값은 샘플 콘텐츠(SampleContent)로 검사하고, 여기서는 실제 값에 기대지 않는 확인만 한다.
 * 그래서 콘텐츠를 고쳐도 이 테스트의 숫자를 고칠 일이 없고, 콘텐츠가 규칙과 맞지 않으면 실패해서 알려 준다.
 */
class RealContentRulesTests {

    // 클래스패스 기준 실제 콘텐츠 위치(src/main/resources 아래)
    private static final String CONTENT = "/content/";

    private static NodeTree tree;
    private static BattleContent battle;

    @BeforeAll
    static void load() {
        ContentBundle bundle = ContentBundleLoader.load(RealContentRulesTests::bytes);
        tree = bundle.nodeTree();
        battle = bundle.battle();
    }

    // 노드를 사지 않은 판은 모든 성장도에서 만들어지고, 시작 공급이 있다
    @Test
    void everyStageCanBeComposedWithoutNodes() {
        for (int stage = 0; stage <= battle.growth().maxStage(); stage++) {
            RunComposition run = RunComposition.of(battle, stage, NodeUpgradeValues.of(tree, Map.of()));

            assertThat(run.timeLimitSeconds()).as("성장도 %d", stage).isPositive();
            assertThat(run.startSupply()).as("성장도 %d", stage).isNotEmpty();
        }
    }

    // 모든 노드를 마지막 Rank까지 사도 모든 성장도에서 판 설정이 만들어진다(클라이언트 UpgradeContentCheck와 같은 확인)
    @Test
    void everyStageCanBeComposedWhenEveryNodeIsBought() {
        Map<String, Integer> everything = new HashMap<>();
        for (NodeDefinition node : tree.nodes()) {
            everything.put(node.id(), node.maxRank());
        }

        for (int stage = 0; stage <= battle.growth().maxStage(); stage++) {
            RunComposition run = RunComposition.of(battle, stage, NodeUpgradeValues.of(tree, everything));

            assertThat(run.timeLimitSeconds()).as("성장도 %d", stage).isPositive();
            assertThat(run.startSupply()).as("성장도 %d", stage).isNotEmpty();
        }
    }

    // 실제 콘텐츠 폴더에서 파일 바이트를 읽는다. 없으면 null
    private static byte[] bytes(String path) {
        try (InputStream in = RealContentRulesTests.class.getResourceAsStream(CONTENT + path)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
