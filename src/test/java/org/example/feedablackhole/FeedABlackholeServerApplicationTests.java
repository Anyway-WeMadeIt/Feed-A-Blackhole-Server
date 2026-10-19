package org.example.feedablackhole;

import static org.assertj.core.api.Assertions.assertThat;

import org.example.feedablackhole.battle.content.BattleContent;
import org.example.feedablackhole.battle.content.SupplyEntry;
import org.example.feedablackhole.battle.rules.BattleRules;
import org.example.feedablackhole.battle.rules.RunSetup;
import org.example.feedablackhole.content.ContentManifest;
import org.example.feedablackhole.node.content.NodeTree;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class FeedABlackholeServerApplicationTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private NodeTree nodeTree;

    @Autowired
    private ContentManifest contentManifest;

    @Autowired
    private BattleContent battleContent;

    @Autowired
    private BattleRules battleRules;

    @Test
    void battleRulesIsAvailableAsABean() {
        RunSetup setup = battleRules.setupFor(0, battleRules.upgradesFor(Map.of()));

        assertThat(setup.timeLimitSeconds()).isEqualTo(12f);
        assertThat(setup.startSupply()).isNotEmpty();
    }

    @Test
    void contextLoads() {
    }

    @Test
    void connectsToMysql() {
        String version = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);

        assertThat(version).startsWith("8.4");
    }

    @Test
    void contentBundleIsLoadedAtStartup() {
        assertThat(nodeTree.nodes()).hasSize(276);
        assertThat(battleContent.enemies()).hasSize(5);
        // 스프링을 띄우는 테스트는 샘플 콘텐츠 묶음 위에서 돈다(build.gradle의 test 설정)
        assertThat(contentManifest.contentVersion()).isEqualTo("4d6c398ad1f5e49c");
        assertThat(battleRules.setupFor(0, battleRules.upgradesFor(Map.of())).startSupply())
                .containsExactly(new SupplyEntry("asteroid", 20));
    }

}
