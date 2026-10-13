package org.example.feedablackhole.sync.config;

import org.example.feedablackhole.node.content.NodeTree;
import org.example.feedablackhole.sync.service.ServerContentVersion;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 동기화가 쓰는 콘텐츠 버전.
 *
 * <p>임시: 지금은 노드만 본 옛 버전(NodeTree.nodeContentVersion())이다. 서버 콘텐츠 로드(W0-3)가 병합되면
 * 묶음 목차의 contentVersion(ContentManifest)으로 바꾼다. 그 전까지는 클라이언트 로그의 버전과 맞지 않아 실제 클라이언트의 로그는 거부된다.
 */
@Configuration
public class SyncConfig {

    @Bean
    ServerContentVersion serverContentVersion(NodeTree nodeTree) {
        String version = nodeTree.nodeContentVersion();
        return () -> version;
    }

}
