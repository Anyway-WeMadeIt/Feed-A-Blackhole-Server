package org.example.feedablackhole.battle.content;

import org.example.feedablackhole.content.ContentManifest;
import org.example.feedablackhole.node.content.ContentLoadException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ContentVersionVerifier가 목차의 contentVersion을 실제 파일 바이트로 다시 계산해 비교하는지 확인하는 테스트.
 * 샘플 묶음(src/test/resources/content-sample)을 쓰고, 파일 바이트나 목차를 한 곳씩 틀리게 한다.
 */
class ContentVersionVerifierTests {

    // 클래스패스 기준 샘플 묶음 위치 (src/test/resources 아래)
    private static final String SAMPLE = "/content-sample/";

    // 샘플 manifest.json에 적힌 contentVersion
    private static final String SAMPLE_VERSION = "4d6c398ad1f5e49c";

    /**
     * 샘플 묶음은 목차의 버전과 파일로 계산한 버전이 같아서 통과해야 한다.
     * (A가 만든 샘플의 버전과 이번 검사가 같은 계산을 한다는 확인이기도 하다)
     */
    @Test
    void sampleBundlePasses() {
        BundleSource source = ContentVersionVerifierTests::sampleBytes;
        ContentManifest manifest = ContentManifestLoader.load(source);

        // 예외가 던져지지 않아야 한다
        assertThatCode(() -> ContentVersionVerifier.verify(manifest, source)).doesNotThrowAnyException();
    }

    /**
     * 파일 내용이 한 바이트라도 달라지면 알리는지 확인한다(규칙 1).
     */
    @Test
    void changedFileBytesAreRejected() {
        ContentManifest manifest = ContentManifestLoader.load(ContentVersionVerifierTests::sampleBytes);

        // battle.json 끝에 바이트 하나를 더한 소스. 나머지 파일은 샘플 그대로다
        BundleSource edited = path -> {
            byte[] bytes = sampleBytes(path);
            return "battle/battle.json".equals(path) ? Arrays.copyOf(bytes, bytes.length + 1) : bytes;
        };

        assertThatThrownBy(() -> ContentVersionVerifier.verify(manifest, edited))
                .isInstanceOf(ContentLoadException.class)
                .hasMessageContaining("contentVersion")
                .hasMessageContaining("맞지 않다")
                // 메시지에 목차의 값이 들어 있어서, 어느 쪽 값이 기준인지 알 수 있다
                .hasMessageContaining(SAMPLE_VERSION)
                .hasMessageContaining("문제 1개");
    }

    /**
     * 파일은 그대로인데 목차의 파일 순서가 바뀌면 알리는지 확인한다(규칙 1).
     * 버전은 목차의 순서대로 파일을 이어 붙여 계산하기 때문이다.
     */
    @Test
    void reorderedFilesAreRejected() {
        ContentManifest sample = ContentManifestLoader.load(ContentVersionVerifierTests::sampleBytes);

        // 첫 두 파일의 순서를 서로 바꾼 목록
        List<String> reordered = new ArrayList<>(sample.files());
        reordered.set(0, sample.files().get(1));
        reordered.set(1, sample.files().get(0));
        ContentManifest manifest = new ContentManifest(sample.formatVersion(), sample.contentVersion(), reordered);

        assertThatThrownBy(() -> ContentVersionVerifier.verify(manifest, ContentVersionVerifierTests::sampleBytes))
                .isInstanceOf(ContentLoadException.class)
                .hasMessageContaining("맞지 않다")
                .hasMessageContaining("문제 1개");
    }

    /**
     * 목차에 있는 파일을 읽을 수 없으면 NPE가 아니라 진단으로 알리는지 확인한다(규칙 2).
     */
    @Test
    void unreadableFileIsReportedInsteadOfCrashing() {
        ContentManifest manifest = ContentManifestLoader.load(ContentVersionVerifierTests::sampleBytes);

        // nodes/Nodes.csv만 없는 것처럼 보이는 소스
        BundleSource missing = path -> "nodes/Nodes.csv".equals(path) ? null : sampleBytes(path);

        assertThatThrownBy(() -> ContentVersionVerifier.verify(manifest, missing))
                .isInstanceOf(ContentLoadException.class)
                .hasMessageContaining("nodes/Nodes.csv")
                .hasMessageContaining("파일이 없다")
                .hasMessageContaining("문제 1개");
    }

    // 샘플 묶음의 파일 바이트. 없으면 null
    private static byte[] sampleBytes(String path) {
        try (InputStream in = ContentVersionVerifierTests.class.getResourceAsStream(SAMPLE + path)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
