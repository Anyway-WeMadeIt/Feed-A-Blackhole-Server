package org.example.feedablackhole.battle.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;

/**
 * 서버가 실제로 쓰는 콘텐츠 묶음(src/main/resources/content)이 모든 검증을 통과하는지 확인한다.
 * 콘텐츠를 고친 PR은 이 테스트로 검증된다: 목차, 버전, 노드, 전투 규칙 중 하나라도 어기면 불러오기가 실패하고,
 * 어느 파일의 어느 위치가 왜 틀렸는지 메시지에 모두 나온다.
 */
class RealContentBundleTests {

    // 클래스패스 기준 실제 콘텐츠 위치(src/main/resources 아래, 테스트에서도 읽힌다)
    private static final String CONTENT = "/content/";

    /**
     * 묶음을 읽어 목차, 버전 재계산, 노드, 전투 검증이 모두 통과하는지 본다.
     * 불러오기에 성공했다는 것 자체가 검증이다.
     */
    @Test
    void realContentBundlePassesEveryCheck() {
        ContentBundle bundle = ContentBundleLoader.load(RealContentBundleTests::bytes);

        assertThat(bundle.manifest().formatVersion()).isEqualTo(1);
        assertThat(bundle.manifest().contentVersion()).matches("[0-9a-f]{16}");
        assertThat(bundle.nodeTree().nodes()).isNotEmpty();
        assertThat(bundle.battle().enemies()).isNotEmpty();
    }

    // 실제 콘텐츠 폴더에서 파일 바이트를 읽는다. 없으면 null
    private static byte[] bytes(String path) {
        try (InputStream in = RealContentBundleTests.class.getResourceAsStream(CONTENT + path)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
