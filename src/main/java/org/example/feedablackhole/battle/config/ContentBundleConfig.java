package org.example.feedablackhole.battle.config;

import lombok.extern.slf4j.Slf4j;
import org.example.feedablackhole.battle.content.BattleContent;
import org.example.feedablackhole.battle.content.ContentBundle;
import org.example.feedablackhole.battle.content.ContentBundleLoader;
import org.example.feedablackhole.content.ContentManifest;
import org.example.feedablackhole.node.content.NodeTree;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * 서버가 시작할 때 콘텐츠 묶음을 한 번 읽어 검증하고, 불변 객체를 빈으로 보관한다.
 * 콘텐츠에 문제가 하나라도 있으면 모든 문제를 알리고 서버를 시작하지 않는다(잘못된 콘텐츠로 구매·결산을 받는 일이 없게).
 * 요청을 처리하는 동안에는 파일을 읽지 않는다.
 *
 * <p>묶음의 위치는 설정 blackhole.content.location으로 바꿀 수 있다. 기본값은 실제 콘텐츠(classpath:content/)이고,
 * 테스트는 샘플 묶음(classpath:content-sample/)을 가리키도록 덮어쓴다(계약 결정 C30).
 *
 * <p>이후 코드(전투 규칙, 처리기)는 ContentManifest(버전), NodeTree, BattleContent를 주입받아 쓴다.
 */
@Slf4j
@Configuration
public class ContentBundleConfig {

    /**
     * 묶음을 읽는다. 검증에 실패하면 ContentLoadException이 던져져서 서버가 시작하지 않는다.
     *
     * @param location 묶음의 위치. 설정이 없으면 실제 콘텐츠(classpath:content/)
     */
    @Bean
    ContentBundle contentBundle(ResourceLoader resourceLoader,
            @Value("${blackhole.content.location:classpath:content/}") String location) {
        // 위치 뒤에 nodes/Nodes.csv 같은 경로를 이어 붙이므로, 위치는 /로 끝나야 한다
        String base = location.endsWith("/") ? location : location + "/";

        ContentBundle bundle = ContentBundleLoader.load(path -> read(resourceLoader, base, path));

        NodeTree tree = bundle.nodeTree();
        BattleContent battle = bundle.battle();
        log.info("콘텐츠 묶음을 불러왔다. location={}, contentVersion={}, 수치 {}개, 노드 {}개(배치 {}개), 선 {}개, 적 종류 {}개, 이정표 {}개",
                base, bundle.manifest().contentVersion(), tree.content().stats().size(), tree.content().nodes().size(),
                tree.nodes().size(), tree.graph().links().size(), battle.enemies().size(),
                battle.growth().milestones().size());
        // 콘텐츠에는 있지만 배치되지 않은 노드는 오류가 아니라 살 수 없는 노드다. 경고로만 알린다
        if (!tree.unplaced().isEmpty()) {
            log.warn("배치되지 않아 살 수 없는 노드 {}개: {}", tree.unplaced().size(), tree.unplaced());
        }
        return bundle;
    }

    // 아래 세 빈은 묶음에서 꺼낸 것이다. 다른 코드가 필요한 것만 골라 주입받을 수 있게 따로 등록한다

    @Bean
    ContentManifest contentManifest(ContentBundle bundle) {
        return bundle.manifest();
    }

    @Bean
    NodeTree nodeTree(ContentBundle bundle) {
        return bundle.nodeTree();
    }

    @Bean
    BattleContent battleContent(ContentBundle bundle) {
        return bundle.battle();
    }

    // 위치(base) 아래에서 파일 바이트를 읽는다. 없는 파일은 null로 넘겨, 목차 검사가 "파일이 없다"는 진단으로 모아서 알린다
    private static byte[] read(ResourceLoader resourceLoader, String base, String path) {
        Resource resource = resourceLoader.getResource(base + path);
        if (!resource.exists()) {
            return null;
        }
        try {
            return resource.getContentAsByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("콘텐츠 파일을 읽지 못했다: " + path, e);
        }
    }

}
