package org.example.feedablackhole.battle.rules;

import org.example.feedablackhole.battle.content.BattleContent;
import org.example.feedablackhole.battle.content.ContentBundle;
import org.example.feedablackhole.battle.content.ContentBundleLoader;
import org.example.feedablackhole.node.content.NodeTree;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * 전투 규칙 테스트가 쓰는 샘플 콘텐츠 묶음(src/test/resources/content-sample)을 한 번 읽어 돌려준다(계약 결정 C30).
 * 샘플은 값이 고정이라, 실제 콘텐츠(src/main/resources/content)가 바뀌어도 이 묶음으로 만든 테스트는 깨지지 않는다.
 */
final class SampleContent {

    // 클래스패스 기준 샘플 묶음 위치(src/test/resources 아래)
    private static final String ROOT = "/content-sample/";

    private static final ContentBundle BUNDLE = ContentBundleLoader.load(SampleContent::bytes);

    private SampleContent() {
    }

    // 샘플의 노드 트리(수치 정의, 노드 276개, 배치)
    static NodeTree tree() {
        return BUNDLE.nodeTree();
    }

    // 샘플의 전투 콘텐츠(규칙, 성장, 적 종류, 시작 공급 소행성 20마리)
    static BattleContent battle() {
        return BUNDLE.battle();
    }

    // 샘플 묶음 폴더에서 파일 바이트를 읽는다. 없으면 null
    private static byte[] bytes(String path) {
        try (InputStream in = SampleContent.class.getResourceAsStream(ROOT + path)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
