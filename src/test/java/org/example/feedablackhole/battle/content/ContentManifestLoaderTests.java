package org.example.feedablackhole.battle.content;

import org.example.feedablackhole.content.ContentManifest;
import org.example.feedablackhole.node.content.ContentLoadException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ContentManifestLoader가 목차(manifest.json)를 올바르게 읽고, 틀린 목차를 알리는지 확인하는 테스트.
 * 파일은 샘플 묶음(src/test/resources/content-sample)에서 읽고, 목차 JSON은 문자열로 직접 만들어 한 곳씩 틀리게 한다.
 */
class ContentManifestLoaderTests {

    // 클래스패스 기준 샘플 묶음 위치 (src/test/resources 아래)
    private static final String SAMPLE = "/content-sample/";

    // 샘플 묶음의 파일 목록(샘플 manifest.json과 같은 순서)
    private static final List<String> FILES = List.of(
            "nodes/UpgradeStats.csv", "nodes/Nodes.csv", "nodes/NodeCost.csv", "nodes/NodeEffects.csv",
            "nodes/layout.json", "battle/battle.json");

    /**
     * 샘플 묶음의 목차를 읽어, 값이 샘플 manifest.json에 적힌 그대로 만들어졌는지 본다.
     */
    @Test
    void readsTheSampleManifest() {
        ContentManifest manifest = ContentManifestLoader.load(sampleSource());

        assertThat(manifest.formatVersion()).isEqualTo(1);
        assertThat(manifest.contentVersion()).isEqualTo("4d6c398ad1f5e49c");
        // 파일 순서도 그대로여야 한다(이 순서로 버전을 계산한다)
        assertThat(manifest.files()).containsExactlyElementsOf(FILES);
    }

    /**
     * 목차 파일이 없으면 알리는지 확인한다(규칙 1).
     */
    @Test
    void missingManifestIsRejected() {
        // 어떤 경로를 물어도 null을 돌려주는 소스: 파일이 하나도 없는 묶음
        BundleSource empty = path -> null;

        assertThatThrownBy(() -> ContentManifestLoader.load(empty))
                .isInstanceOf(ContentLoadException.class)
                .hasMessageContaining("manifest.json")
                .hasMessageContaining("없다");
    }

    /**
     * 목차가 JSON 형식이 아니면 알리는지 확인한다(규칙 2).
     */
    @Test
    void malformedManifestIsRejected() {
        assertThatThrownBy(() -> ContentManifestLoader.load(sourceWithManifest("{ not json")))
                .isInstanceOf(ContentLoadException.class)
                .hasMessageContaining("JSON 형식");
    }

    /**
     * formatVersion이 지원하는 값이 아니면 알리는지 확인한다(규칙 3).
     */
    @Test
    void unsupportedFormatVersionIsRejected() {
        String json = manifestJson(2, "4d6c398ad1f5e49c", FILES);

        assertThatThrownBy(() -> ContentManifestLoader.load(sourceWithManifest(json)))
                .isInstanceOf(ContentLoadException.class)
                .hasMessageContaining("formatVersion")
                .hasMessageContaining("1이어야")
                .hasMessageContaining("문제 1개");
    }

    /**
     * contentVersion이 비어 있으면 알리는지 확인한다(규칙 4).
     */
    @Test
    void blankContentVersionIsRejected() {
        String json = manifestJson(1, "", FILES);

        assertThatThrownBy(() -> ContentManifestLoader.load(sourceWithManifest(json)))
                .isInstanceOf(ContentLoadException.class)
                .hasMessageContaining("contentVersion")
                .hasMessageContaining("비어 있지 않은");
    }

    /**
     * 로더가 읽는 파일이 목록에서 빠져 있으면 알리는지 확인한다(규칙 5).
     * 목록에 없으면 contentVersion이 그 파일을 덮지 못하기 때문이다.
     */
    @Test
    void requiredFileMissingFromTheListIsRejected() {
        // battle/battle.json만 뺀 목록
        List<String> withoutBattle = FILES.subList(0, FILES.size() - 1);
        String json = manifestJson(1, "4d6c398ad1f5e49c", withoutBattle);

        assertThatThrownBy(() -> ContentManifestLoader.load(sourceWithManifest(json)))
                .isInstanceOf(ContentLoadException.class)
                .hasMessageContaining("battle/battle.json")
                .hasMessageContaining("목록에 없다");
    }

    /**
     * 목록에 있는 파일이 실제로 없으면 알리고, 없는 파일을 모두 모아서 보고하는지 확인한다(규칙 6).
     */
    @Test
    void everyListedFileThatIsMissingIsReported() {
        // 샘플 묶음에서 두 파일을 일부러 없앤다
        BundleSource source = sourceWithout(Set.of("nodes/Nodes.csv", "battle/battle.json"));

        assertThatThrownBy(() -> ContentManifestLoader.load(source))
                .isInstanceOf(ContentLoadException.class)
                .hasMessageContaining("nodes/Nodes.csv")
                .hasMessageContaining("battle/battle.json")
                .hasMessageContaining("파일이 없다")
                // 두 문제가 한 번에 모여서 나온다
                .hasMessageContaining("문제 2개");
    }

    // ---------- 도우미 ----------

    // 샘플 묶음을 그대로 읽는 소스(목차도 샘플 manifest.json을 읽는다)
    private static BundleSource sampleSource() {
        return ContentManifestLoaderTests::sampleBytes;
    }

    // 목차만 주어진 JSON으로 바꾸고, 나머지 파일은 샘플에서 읽는 소스
    private static BundleSource sourceWithManifest(String manifestJson) {
        return path -> ContentManifest.FILE.equals(path)
                ? manifestJson.getBytes(StandardCharsets.UTF_8)
                : sampleBytes(path);
    }

    // 샘플 묶음에서 주어진 파일들만 없는 것처럼 보이는 소스
    private static BundleSource sourceWithout(Set<String> missing) {
        return path -> missing.contains(path) ? null : sampleBytes(path);
    }

    // formatVersion, contentVersion, 파일 목록으로 manifest.json 글자를 만든다
    private static String manifestJson(int formatVersion, String contentVersion, List<String> files) {
        String list = files.stream().map(f -> "\"" + f + "\"").collect(Collectors.joining(", "));
        return "{\"formatVersion\": " + formatVersion + ", \"contentVersion\": \"" + contentVersion
                + "\", \"files\": [" + list + "]}";
    }

    // 샘플 묶음의 파일 바이트. 없으면 null
    private static byte[] sampleBytes(String path) {
        try (InputStream in = ContentManifestLoaderTests.class.getResourceAsStream(SAMPLE + path)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
