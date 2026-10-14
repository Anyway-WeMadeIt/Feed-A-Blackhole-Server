package org.example.feedablackhole.battle.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import org.example.feedablackhole.battle.content.BattleContent;
import org.example.feedablackhole.battle.content.ContentBundleLoader;
import org.example.feedablackhole.battle.content.EnemyKindDefinition;
import org.example.feedablackhole.battle.content.EnemyStatIds;
import org.junit.jupiter.api.Test;

/**
 * UpgradeChain이 변환 사슬에서 앞 종류가 먼저 오는 순서(종류 순서를 지키는 위상 정렬)를 만드는지 확인한다.
 */
class UpgradeChainTests {

    // 클래스패스 기준 실제 콘텐츠 위치(src/main/resources 아래)
    private static final String CONTENT = "/content/";

    // 실제 콘텐츠: 소행성 -> 행성 -> 항성 -> 은하로 이어지고, 혜성은 변환과 무관하다.
    // 변환되어 오는 종류가 없는 소행성과 혜성이 먼저 줄을 서고, 혜성이 행성보다 앞에 온다
    @Test
    void realContentChainOrder() {
        BattleContent battle = ContentBundleLoader.load(UpgradeChainTests::bytes).battle();

        assertThat(UpgradeChain.orderOf(battle.enemies()))
                .containsExactly("asteroid", "comet", "planet", "star", "galaxy");
    }

    // 변환이 없으면 입력한 순서 그대로다
    @Test
    void withoutAnyChainTheOrderIsUnchanged() {
        List<EnemyKindDefinition> enemies = List.of(kind("a", null), kind("b", null), kind("c", null));

        assertThat(UpgradeChain.orderOf(enemies)).containsExactly("a", "b", "c");
    }

    // 입력이 뒤집혀 있어도 앞 종류(a -> b -> c)가 먼저 온다
    @Test
    void earlierKindsComeFirstEvenIfListedLast() {
        List<EnemyKindDefinition> enemies = List.of(kind("c", null), kind("b", "c"), kind("a", "b"));

        assertThat(UpgradeChain.orderOf(enemies)).containsExactly("a", "b", "c");
    }

    // 두 종류가 같은 종류로 바뀌어도, 둘 다 처리된 뒤에야 그 종류가 온다
    @Test
    void aKindWaitsForEveryKindThatTurnsIntoIt() {
        List<EnemyKindDefinition> enemies = List.of(kind("x", "z"), kind("y", "z"), kind("z", null));

        assertThat(UpgradeChain.orderOf(enemies)).containsExactly("x", "y", "z");
    }

    // 종류가 없으면 빈 목록이다
    @Test
    void noKindsGiveAnEmptyOrder() {
        assertThat(UpgradeChain.orderOf(List.of())).isEmpty();
    }

    // 정렬에 필요한 이름(enemyId)과 변환 대상(upgradesTo)만 의미 있는 합성 종류
    private static EnemyKindDefinition kind(String enemyId, String upgradesTo) {
        return new EnemyKindDefinition(enemyId, false, upgradesTo, List.of(), List.of(), null,
                new EnemyStatIds(null, null, null, null, null, null, Map.of(), null, null, null));
    }

    // 실제 콘텐츠 폴더에서 파일 바이트를 읽는다. 없으면 null
    private static byte[] bytes(String path) {
        try (InputStream in = UpgradeChainTests.class.getResourceAsStream(CONTENT + path)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
