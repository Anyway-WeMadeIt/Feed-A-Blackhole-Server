package org.example.feedablackhole.battle.content;

import org.example.feedablackhole.node.content.ContentLoadException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BattleContentLoader가 battle.json을 BattleContent로 올바르게 읽는지 확인하는 테스트.
 * 샘플 묶음(src/test/resources/content-sample/battle/battle.json)의 실제 값으로 검사한다.
 */
class BattleContentLoaderTests {

    // 클래스패스 기준 샘플 파일 경로 (src/test/resources 아래)
    private static final String SAMPLE = "/content-sample/battle/battle.json";

    /**
     * 샘플 battle.json을 읽어, 각 부분이 기대한 값으로 만들어졌는지 본다.
     * 값 하나가 틀리면 로더가 해당 필드를 잘못 읽은 것이다.
     */
    @Test
    void readsTheSampleContent() throws IOException {
        // 파일 내용(글자)을 로더에 넘겨 BattleContent를 만든다
        BattleContent content = BattleContentLoader.load(read());

        // 적 종류 5개: asteroid, planet, star, comet, galaxy
        assertThat(content.enemies()).hasSize(5);

        // rules: 제한 시간 12초, 처치 시간 보너스 0.3초 (소수가 float으로 정확히 읽히는지)
        assertThat(content.rules()).isEqualTo(new BattleRulesDefinition(12f, 0.3f));

        // growth.milestones: 세 번째 이정표의 목표 Gold (long으로 읽는지)
        assertThat(content.growth().milestones().get(2).targetGold()).isEqualTo(420_000_000L);

        // 소행성의 성질별 생성 확률 statId (키와 값이 뒤바뀌지 않았는지)
        assertThat(content.enemy("asteroid").stats().traitChance())
                .containsEntry("golden", "goldenAsteroid.spawnChance");

        // 은하의 마지막 색 등급 Gold: int 범위를 넘는 값이 잘리지 않는지
        assertThat(content.enemy("galaxy").tiers().get(5).gold()).isEqualTo(22_000_000_000_000L);
    }

    // 샘플 파일을 클래스패스에서 읽어 글자로 돌려준다
    private static String read() throws IOException {
        try (InputStream in = BattleContentLoaderTests.class.getResourceAsStream(SAMPLE)) {
            // 파일이 없으면 null이므로, 경로가 틀렸을 때 바로 알 수 있게 확인한다
            assertThat(in).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * enemyId가 중복된 battle.json을 로더에 넣으면, 문제를 모아서 ContentLoadException으로 알리는지 확인한다.
     * 검증 규칙 1(enemyId 유일)과 규칙 2(upgradesTo가 존재하는 종류를 가리킴)가 함께 걸린다.
     */
    @Test
    void duplicateEnemyIdIsRejected() throws IOException {
        // 정상인 샘플 battle.json에서 planet의 id를 asteroid로 바꿔, 일부러 규칙을 어긴 입력을 만든다
        // (이제 asteroid가 두 개이고, planet은 없다)
        String broken = read().replace("\"enemyId\": \"planet\",", "\"enemyId\": \"asteroid\",");

        // load를 호출하면 예외가 던져져야 테스트가 통과한다. 던져지지 않으면 실패한다
        assertThatThrownBy(() -> BattleContentLoader.load(broken))
                // 던져진 예외의 종류가 ContentLoadException인지 확인 (NPE 등 다른 예외면 실패)
                .isInstanceOf(ContentLoadException.class)
                // 문제 1: asteroid가 두 번 나와서 "중복됐다"
                .hasMessageContaining("중복")
                // 문제 2: 소행성의 upgradesTo가 가리키던 planet이 없어져서 "변환 대상이 없다".
                // 이것까지 메시지에 있어야, 문제를 하나에서 멈추지 않고 모아서 보고한다는 뜻이다
                .hasMessageContaining("변환 대상");
    }
}
