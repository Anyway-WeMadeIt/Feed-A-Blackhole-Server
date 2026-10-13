package org.example.feedablackhole.battle.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.example.feedablackhole.content.ContentVersion;
import org.example.feedablackhole.content.ContentVersion.ContentFile;
import org.example.feedablackhole.node.content.ContentLoadException;
import org.junit.jupiter.api.Test;

/**
 * ContentBundleLoader가 목차 검사, 버전 재계산, 노드·전투 콘텐츠 읽기를 순서대로 해서
 * 묶음을 만드는지 확인하는 테스트. 샘플 묶음(src/test/resources/content-sample)을 메모리에서 고쳐 쓴다.
 */
class ContentBundleLoaderTests {

    // 클래스패스 기준 샘플 묶음 위치 (src/test/resources 아래)
    private static final String SAMPLE = "/content-sample/";

    // 샘플 목차의 파일 목록(같은 순서)
    private static final List<String> FILES = List.of(
            "nodes/UpgradeStats.csv", "nodes/Nodes.csv", "nodes/NodeCost.csv", "nodes/NodeEffects.csv",
            "nodes/layout.json", "battle/battle.json");

    /**
     * 샘플 묶음을 읽어 세 가지(목차, 노드 트리, 전투 콘텐츠)가 모두 만들어지는지 본다.
     */
    @Test
    void loadsTheSampleBundle() {
        ContentBundle bundle = ContentBundleLoader.load(source(sampleFiles()));

        assertThat(bundle.manifest().contentVersion()).isEqualTo("4d6c398ad1f5e49c");
        assertThat(bundle.nodeTree().nodes()).hasSize(276);
        assertThat(bundle.nodeTree().content().stats()).hasSize(71);
        assertThat(bundle.battle().enemies()).hasSize(5);
    }

    /**
     * 파일은 고쳤는데 목차의 버전을 그대로 두면 시작을 막는지 확인한다(4단계 검사가 이어지는지 본다).
     */
    @Test
    void editedFileWithTheOldVersionIsRejected() {
        Map<String, byte[]> files = sampleFiles();
        files.put("battle/battle.json", Arrays.copyOf(files.get("battle/battle.json"), files.get("battle/battle.json").length + 1));

        assertThatThrownBy(() -> ContentBundleLoader.load(source(files)))
                .isInstanceOf(ContentLoadException.class)
                .hasMessageContaining("맞지 않다");
    }

    /**
     * battle.json의 statId가 노드 시트에 없으면 알리는지 확인한다(규칙 9가 묶음에서도 걸리는지 본다).
     */
    @Test
    void battleStatIdMissingFromTheNodeSheetIsRejected() {
        Map<String, byte[]> files = sampleFiles();
        String battle = new String(files.get("battle/battle.json"), StandardCharsets.UTF_8)
                .replace("\"asteroid.massScale\"", "\"asteroid.massScaleX\"");
        files.put("battle/battle.json", battle.getBytes(StandardCharsets.UTF_8));

        // 고친 파일에 맞게 목차의 버전을 다시 계산해서, 버전 검사는 통과하게 한다
        assertThatThrownBy(() -> ContentBundleLoader.load(source(withManifestFor(files))))
                .isInstanceOf(ContentLoadException.class)
                .hasMessageContaining("enemies[asteroid].stats.mass")
                .hasMessageContaining("UpgradeStats 시트에 없는");
    }

    // ---------- 도우미 ----------

    private static BundleSource source(Map<String, byte[]> files) {
        return files::get;
    }

    // 샘플 묶음의 모든 파일(목차 포함)을 바이트 그대로
    private static Map<String, byte[]> sampleFiles() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("manifest.json", bytes("manifest.json"));
        for (String path : FILES) {
            files.put(path, bytes(path));
        }
        return files;
    }

    // 파일 내용에 맞는 contentVersion으로 목차를 새로 써서 돌려준다
    private static Map<String, byte[]> withManifestFor(Map<String, byte[]> files) {
        List<ContentFile> contentFiles = new ArrayList<>();
        for (String path : FILES) {
            contentFiles.add(new ContentFile(path, files.get(path)));
        }
        String list = FILES.stream().map(path -> "\"" + path + "\"").collect(Collectors.joining(", "));
        String manifest = "{\"formatVersion\": 1, \"contentVersion\": \"" + ContentVersion.of(contentFiles)
                + "\", \"files\": [" + list + "]}";

        Map<String, byte[]> result = new LinkedHashMap<>(files);
        result.put("manifest.json", manifest.getBytes(StandardCharsets.UTF_8));
        return result;
    }

    private static byte[] bytes(String path) {
        try (InputStream in = ContentBundleLoaderTests.class.getResourceAsStream(SAMPLE + path)) {
            assertThat(in).as(path).isNotNull();
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 노드 콘텐츠와 전투 콘텐츠에 문제가 함께 있으면 둘을 한 번에 보고하는지 확인한다.
     */
    @Test
    void nodeAndBattleProblemsAreReportedTogether() {
        Map<String, byte[]> files = sampleFiles();

        // 노드 문제: Nodes.csv의 머리칸 이름을 틀리게 한다
        String nodes = new String(files.get("nodes/Nodes.csv"), StandardCharsets.UTF_8).replace("RankCount", "Rank 수");
        files.put("nodes/Nodes.csv", nodes.getBytes(StandardCharsets.UTF_8));

        // 전투 문제: 소행성의 변환 대상을 없는 종류로 바꾼다
        String battle = new String(files.get("battle/battle.json"), StandardCharsets.UTF_8)
                .replace("\"upgradesTo\": \"planet\",", "\"upgradesTo\": \"nebula\",");
        files.put("battle/battle.json", battle.getBytes(StandardCharsets.UTF_8));

        // 고친 파일에 맞게 목차의 버전을 다시 계산해서, 버전 검사는 통과하게 한다
        assertThatThrownBy(() -> ContentBundleLoader.load(source(withManifestFor(files))))
                .isInstanceOf(ContentLoadException.class)
                .hasMessageContaining("RankCount")          // 노드 문제
                .hasMessageContaining("enemies[asteroid].upgradesTo")   // 전투 문제
                .hasMessageContaining("문제 2개");
    }
}
