package org.example.feedablackhole.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.example.feedablackhole.content.ContentVersion.ContentFile;
import org.junit.jupiter.api.Test;

/**
 * 콘텐츠 버전 계산(docs/server-sync-contracts.md 1.3)이 샘플 묶음(src/test/resources/content-sample)의 목차 값과 같은지 본다.
 * 샘플 묶음의 버전은 계약 문서의 계산법을 그대로 따르는 별도 구현(Python)으로 만들었으므로, 이 테스트가 두 구현의 일치를 확인한다.
 */
class ContentVersionTests {

    private static final String SAMPLE = "/content-sample/";

    @Test
    void sampleBundleVersionMatchesManifest() throws IOException {
        String manifest = read("manifest.json");

        List<ContentFile> files = new ArrayList<>();
        for (String path : listOf(manifest, "files")) {
            files.add(new ContentFile(path, bytes(path)));
        }

        assertThat(ContentVersion.of(files)).isEqualTo(stringOf(manifest, "contentVersion"));
    }

    @Test
    void versionChangesWhenAnyByteChanges() {
        String base = ContentVersion.of(List.of(new ContentFile("a.json", new byte[] {1, 2})));

        assertThat(ContentVersion.of(List.of(new ContentFile("a.json", new byte[] {1, 3})))).isNotEqualTo(base);
        assertThat(ContentVersion.of(List.of(new ContentFile("b.json", new byte[] {1, 2})))).isNotEqualTo(base);
        assertThat(base).matches("[0-9a-f]{16}");
    }

    @Test
    void fileBoundaryIsPartOfTheVersion() {
        String joined = ContentVersion.of(List.of(new ContentFile("a", new byte[] {1, 2})));
        String split = ContentVersion.of(List.of(
                new ContentFile("a", new byte[] {1}), new ContentFile("", new byte[] {2})));

        assertThat(split).isNotEqualTo(joined);
    }

    private static byte[] bytes(String path) throws IOException {
        try (InputStream in = ContentVersionTests.class.getResourceAsStream(SAMPLE + path)) {
            assertThat(in).as(path).isNotNull();
            return in.readAllBytes();
        }
    }

    private static String read(String path) throws IOException {
        return new String(bytes(path), StandardCharsets.UTF_8);
    }

    // 목차는 작은 고정 형식이라 테스트에서는 정규식으로 읽는다(로더는 W0-3에서 JSON 파서로 읽는다).
    private static String stringOf(String json, String key) {
        Matcher matcher = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static List<String> listOf(String json, String key) {
        Matcher array = Pattern.compile("\"" + key + "\"\\s*:\\s*\\[([^]]*)]").matcher(json);
        assertThat(array.find()).isTrue();
        Matcher item = Pattern.compile("\"([^\"]*)\"").matcher(array.group(1));
        List<String> items = new ArrayList<>();
        while (item.find()) {
            items.add(item.group(1));
        }
        return items;
    }

}
