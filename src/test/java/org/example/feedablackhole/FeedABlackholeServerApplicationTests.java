package org.example.feedablackhole;

import static org.assertj.core.api.Assertions.assertThat;

import org.example.feedablackhole.battle.content.BattleContent;
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
        assertThat(contentManifest.contentVersion()).matches("[0-9a-f]{16}");
    }

}
