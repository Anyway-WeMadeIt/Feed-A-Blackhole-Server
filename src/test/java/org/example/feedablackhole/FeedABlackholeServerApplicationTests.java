package org.example.feedablackhole;

import static org.assertj.core.api.Assertions.assertThat;

import org.example.feedablackhole.node.content.NodeTree;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class FeedABlackholeServerApplicationTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private NodeTree nodeTree;

    @Test
    void contextLoads() {
    }

    @Test
    void connectsToMysql() {
        String version = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);

        assertThat(version).startsWith("8.4");
    }

    @Test
    void nodeContentIsLoadedAtStartup() {
        assertThat(nodeTree.nodes()).hasSize(247);
        assertThat(nodeTree.nodeContentVersion()).matches("[0-9a-f]{16}");
    }

}
