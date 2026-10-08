package org.example.feedablackhole.node.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.example.feedablackhole.node.content.NodeContentFiles;
import org.example.feedablackhole.node.content.NodeTree;
import org.example.feedablackhole.node.content.NodeTreeFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * 서버가 시작할 때 노드 콘텐츠(src/main/resources/content/nodes)를 한 번 읽어 검증하고 불변 NodeTree로 보관한다.
 * 콘텐츠에 문제가 하나라도 있으면 모든 문제를 알리고 서버를 시작하지 않는다(잘못된 콘텐츠로 구매를 받는 일이 없게).
 * 요청을 처리하는 동안에는 파일을 읽지 않는다.
 */
@Slf4j
@Configuration
public class NodeContentConfig {

    private static final String LOCATION = "classpath:content/nodes/";

    @Bean
    NodeTree nodeTree(ResourceLoader resourceLoader) {
        NodeContentFiles files = new NodeContentFiles(
                read(resourceLoader, NodeContentFiles.STATS_FILE),
                read(resourceLoader, NodeContentFiles.NODES_FILE),
                read(resourceLoader, NodeContentFiles.COST_FILE),
                read(resourceLoader, NodeContentFiles.EFFECTS_FILE),
                read(resourceLoader, NodeContentFiles.LAYOUT_FILE));

        NodeTree tree = NodeTreeFactory.load(files);

        log.info("노드 콘텐츠를 불러왔다. nodeContentVersion={}, 수치 {}개, 노드 {}개(배치 {}개), 선 {}개",
                tree.nodeContentVersion(), tree.content().stats().size(), tree.content().nodes().size(),
                tree.nodes().size(), tree.graph().links().size());
        if (!tree.unplaced().isEmpty()) {
            log.warn("배치되지 않아 살 수 없는 노드 {}개: {}", tree.unplaced().size(), tree.unplaced());
        }
        return tree;
    }

    // 없는 파일은 null로 넘겨, 불러올 때 "파일이 없다"는 진단으로 모아서 알린다.
    private static String read(ResourceLoader resourceLoader, String name) {
        Resource resource = resourceLoader.getResource(LOCATION + name);
        if (!resource.exists()) {
            return null;
        }
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("콘텐츠 파일을 읽지 못했다: " + name, e);
        }
    }

}
